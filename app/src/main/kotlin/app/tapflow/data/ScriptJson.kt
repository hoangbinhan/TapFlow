package app.tapflow.data

import app.tapflow.model.Limits
import app.tapflow.model.RunConfig
import app.tapflow.model.Script
import app.tapflow.model.StopMode
import app.tapflow.model.Target
import app.tapflow.model.TargetKind
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Thrown for any file that is not a valid TapFlow export. The message is for logs, not the UI. */
class InvalidScriptException(message: String) : Exception(message)

object ScriptJson {
    private const val APP = "tapflow"
    private const val VERSION = 1

    fun scriptToJson(s: Script): JSONObject = JSONObject()
        .put("id", s.id)
        .put("name", s.name)
        .put("updatedAt", s.updatedAt)
        .put("config", configToJson(s.config))
        .put("targets", targetsToJson(s.targets))

    fun configToJson(c: RunConfig): JSONObject = JSONObject()
        .put("intervalMs", c.intervalMs)
        .put("timeJitterPct", c.timeJitterPct)
        .put("posJitterPx", c.posJitterPx)
        .put("stopMode", c.stopMode.name)
        .put("stopCount", c.stopCount)
        .put("stopDurationSec", c.stopDurationSec)
        .put("startDelaySec", c.startDelaySec)

    fun targetsToJson(list: List<Target>): JSONArray = JSONArray().also { arr ->
        list.forEach { t ->
            arr.put(
                JSONObject()
                    .put("kind", t.kind.name)
                    .put("x", t.x.toDouble()).put("y", t.y.toDouble())
                    .put("x2", t.x2.toDouble()).put("y2", t.y2.toDouble())
                    .put("durationMs", t.durationMs)
                    .put("delayAfterMs", t.delayAfterMs)
                    .put("step", t.step).put("taps", t.taps).put("tapGapMs", t.tapGapMs),
            )
        }
    }

    fun export(scripts: List<Script>): String = JSONObject()
        .put("app", APP)
        .put("version", VERSION)
        .put("scripts", JSONArray().also { arr -> scripts.forEach { arr.put(scriptToJson(it)) } })
        .toString(2)

    /** Parses an export (bundle or single script). Invalid input throws [InvalidScriptException]. */
    fun parseExport(
        text: String,
        newId: () -> String,
        newTargetId: () -> Long,
        keepIds: Boolean = false,
    ): List<Script> {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw InvalidScriptException("not json")
        }
        if (root.optString("app") != APP) throw InvalidScriptException("not a tapflow file")
        if (root.optInt("version", -1) !in 1..VERSION) throw InvalidScriptException("unsupported version")
        val items = root.optJSONArray("scripts") ?: throw InvalidScriptException("no scripts")
        if (items.length() == 0 || items.length() > Limits.MAX_SCRIPTS) throw InvalidScriptException("bad count")
        return (0 until items.length()).map { i ->
            val o = items.optJSONObject(i) ?: throw InvalidScriptException("bad script $i")
            val id = if (keepIds) o.optString("id").ifBlank { newId() } else newId()
            parseScript(o, id, newTargetId)
        }
    }

    fun parseScript(o: JSONObject, id: String, newTargetId: () -> Long): Script {
        val name = o.optString("name").trim().take(Limits.MAX_NAME_LENGTH).ifBlank { "Script" }
        val targets = parseTargets(o.optJSONArray("targets") ?: throw InvalidScriptException("no targets"), newTargetId)
        val config = parseConfig(o.optJSONObject("config"))
        return Script(id, name, targets, config, o.optLong("updatedAt", System.currentTimeMillis()))
    }

    fun parseTargets(arr: JSONArray, newTargetId: () -> Long): List<Target> {
        if (arr.length() > Limits.MAX_TARGETS) throw InvalidScriptException("too many targets")
        return (0 until arr.length()).map { i ->
            val o = arr.optJSONObject(i) ?: throw InvalidScriptException("bad target $i")
            val kind = runCatching { TargetKind.valueOf(o.optString("kind")) }
                .getOrElse { throw InvalidScriptException("bad kind") }
            val x = o.optDouble("x", Double.NaN).toFloat()
            val y = o.optDouble("y", Double.NaN).toFloat()
            if (x.isNaN() || y.isNaN()) throw InvalidScriptException("bad coordinates")
            Target(
                id = newTargetId(),
                kind = kind,
                x = x, y = y,
                x2 = o.optDouble("x2", x.toDouble()).toFloat(),
                y2 = o.optDouble("y2", y.toDouble()).toFloat(),
                durationMs = o.optLong(
                    "durationMs",
                    if (kind == TargetKind.TAP) Limits.DEFAULT_TAP_MS else Limits.DEFAULT_SWIPE_MS,
                ),
                delayAfterMs = o.optLong("delayAfterMs", Target.USE_GLOBAL_DELAY),
                // Files written before steps existed run strictly in list order.
                step = o.optInt("step", i + 1),
                taps = o.optInt("taps", 1),
                tapGapMs = o.optLong("tapGapMs", Limits.DEFAULT_TAP_GAP_MS),
            ).sanitized()
        }
    }

    fun parseConfig(o: JSONObject?): RunConfig {
        if (o == null) return RunConfig()
        val d = RunConfig()
        return RunConfig(
            intervalMs = o.optLong("intervalMs", d.intervalMs),
            timeJitterPct = o.optInt("timeJitterPct", d.timeJitterPct),
            posJitterPx = o.optInt("posJitterPx", d.posJitterPx),
            stopMode = runCatching { StopMode.valueOf(o.optString("stopMode")) }.getOrDefault(d.stopMode),
            stopCount = o.optInt("stopCount", d.stopCount),
            stopDurationSec = o.optInt("stopDurationSec", d.stopDurationSec),
            startDelaySec = o.optInt("startDelaySec", d.startDelaySec),
        ).sanitized()
    }
}
