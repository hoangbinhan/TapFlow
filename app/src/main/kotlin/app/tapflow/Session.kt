package app.tapflow

import app.tapflow.model.RunConfig
import app.tapflow.model.RunState
import app.tapflow.model.Script
import app.tapflow.model.Stats
import app.tapflow.model.Target
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-process state shared by the UI and the accessibility service (they live in the same process).
 * The working draft (targets + config) is persisted by [app.tapflow.data.Store] so a process kill
 * does not lose the user's setup.
 */
object Session {
    val targets = MutableStateFlow<List<Target>>(emptyList())
    val config = MutableStateFlow(RunConfig())

    /** Bumped when config is replaced from outside the editor (script load) so text fields reset. */
    val configEpoch = MutableStateFlow(0)
    val loadedScriptId = MutableStateFlow<String?>(null)

    val run = MutableStateFlow<RunState>(RunState.Idle)
    val stats = MutableStateFlow(Stats())

    val serviceConnected = MutableStateFlow(false)
    val overlayVisible = MutableStateFlow(false)

    private var nextTargetId = System.nanoTime()

    @Synchronized
    fun newTargetId(): Long = nextTargetId++

    val isActive: Boolean
        get() = run.value.let { it is RunState.Countdown || it is RunState.Running }

    val isBusy: Boolean
        get() = run.value.let { it is RunState.Countdown || it is RunState.Running || it is RunState.Paused }

    fun updateConfig(transform: (RunConfig) -> RunConfig) = config.update { transform(it).sanitized() }

    fun loadScript(script: Script) {
        targets.value = script.targets.map { it.copy(id = newTargetId()) }
        config.value = script.config
        configEpoch.update { it + 1 }
        loadedScriptId.value = script.id
    }

    fun restoreDraft(draftTargets: List<Target>, draftConfig: RunConfig) {
        targets.value = draftTargets.map { it.copy(id = newTargetId()) }
        config.value = draftConfig
        configEpoch.update { it + 1 }
    }
}
