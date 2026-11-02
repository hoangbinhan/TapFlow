package app.tapflow

import android.app.Application
import app.tapflow.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class TapFlowApp : Application() {
    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        Store.init(this)
        Store.loadDraft()?.let { (targets, config) -> Session.restoreDraft(targets, config) }

        // Persist the working draft so a killed process (OEM task killers) loses nothing.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            combine(Session.targets, Session.config) { t, c -> t to c }
                .drop(1)
                .debounce(500)
                .collect { (t, c) -> Store.saveDraft(t, c) }
        }
    }
}
