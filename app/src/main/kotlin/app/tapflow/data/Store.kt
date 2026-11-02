package app.tapflow.data

import android.content.Context
import app.tapflow.Session
import app.tapflow.model.Limits
import app.tapflow.model.RunConfig
import app.tapflow.model.Script
import app.tapflow.model.Target
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Saved scripts + the working draft, stored as small JSON files in app-private storage. */
object Store {
    private lateinit var dir: File
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    val scripts = MutableStateFlow<List<Script>>(emptyList())

    fun init(context: Context) {
        dir = File(context.filesDir, "data").apply { mkdirs() }
        scripts.value = runCatching {
            val file = File(dir, SCRIPTS_FILE)
            if (!file.exists()) emptyList()
            else ScriptJson.parseExport(file.readText(), { UUID.randomUUID().toString() }, Session::newTargetId, keepIds = true)
        }.getOrElse { emptyList() } // a corrupt file must never crash start-up
    }

    fun saveScript(name: String, targets: List<Target>, config: RunConfig, overwriteId: String? = null): Script {
        val clean = name.trim().take(Limits.MAX_NAME_LENGTH)
        val script = Script(
            id = overwriteId ?: UUID.randomUUID().toString(),
            name = clean,
            targets = targets,
            config = config,
            updatedAt = System.currentTimeMillis(),
        )
        scripts.update { list ->
            if (overwriteId != null && list.any { it.id == overwriteId }) list.map { if (it.id == overwriteId) script else it }
            else list + script
        }
        persist()
        return script
    }

    fun rename(id: String, name: String) {
        val clean = name.trim().take(Limits.MAX_NAME_LENGTH)
        if (clean.isEmpty()) return
        scripts.update { list -> list.map { if (it.id == id) it.copy(name = clean, updatedAt = System.currentTimeMillis()) else it } }
        persist()
    }

    fun delete(id: String) {
        scripts.update { list -> list.filterNot { it.id == id } }
        if (Session.loadedScriptId.value == id) Session.loadedScriptId.value = null
        persist()
    }

    fun duplicate(id: String, copySuffix: String) {
        val src = scripts.value.firstOrNull { it.id == id } ?: return
        if (scripts.value.size >= Limits.MAX_SCRIPTS) return
        saveScript("${src.name} $copySuffix".take(Limits.MAX_NAME_LENGTH), src.targets, src.config)
    }

    fun find(id: String): Script? = scripts.value.firstOrNull { it.id == id }

    fun exportText(ids: List<String>): String =
        ScriptJson.export(scripts.value.filter { it.id in ids })

    /** Returns the number of scripts imported, or throws [InvalidScriptException]. */
    fun importText(text: String): Int {
        if (text.length > Limits.MAX_IMPORT_BYTES) throw InvalidScriptException("too large")
        val parsed = ScriptJson.parseExport(text, { UUID.randomUUID().toString() }, Session::newTargetId)
        val room = Limits.MAX_SCRIPTS - scripts.value.size
        if (room <= 0) throw InvalidScriptException("library full")
        val accepted = parsed.take(room)
        scripts.update { it + accepted }
        persist()
        return accepted.size
    }

    // ---- working draft -------------------------------------------------------------------------

    fun saveDraft(targets: List<Target>, config: RunConfig) {
        val json = JSONObject()
            .put("targets", ScriptJson.targetsToJson(targets))
            .put("config", ScriptJson.configToJson(config))
            .toString()
        io.launch { synchronized(lock) { writeAtomically(File(dir, DRAFT_FILE), json) } }
    }

    fun loadDraft(): Pair<List<Target>, RunConfig>? = runCatching {
        val file = File(dir, DRAFT_FILE)
        if (!file.exists()) return null
        val o = JSONObject(file.readText())
        val targets = ScriptJson.parseTargets(o.getJSONArray("targets"), Session::newTargetId)
        targets to ScriptJson.parseConfig(o.optJSONObject("config"))
    }.getOrNull()

    // ---- io ------------------------------------------------------------------------------------

    private fun persist() {
        io.launch {
            synchronized(lock) {
                // Read the latest state inside the lock so concurrent writes can never go backwards.
                writeAtomically(File(dir, SCRIPTS_FILE), ScriptJson.export(scripts.value))
            }
        }
    }

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private const val SCRIPTS_FILE = "scripts.json"
    private const val DRAFT_FILE = "draft.json"
}
