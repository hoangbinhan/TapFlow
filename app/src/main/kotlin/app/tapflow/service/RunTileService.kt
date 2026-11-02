package app.tapflow.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.tapflow.MainActivity
import app.tapflow.R
import app.tapflow.Session
import app.tapflow.model.RunState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: start/stop the current targets without opening the app.
 * If the accessibility service is not running there is nothing to drive, so it opens the app instead.
 */
class RunTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch { combine(Session.run, Session.serviceConnected) { r, c -> r to c }.collect { refresh() } }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        val svc = ClickerService.instance
        if (svc == null) {
            openApp()
            return
        }
        // The tile can be tapped on the lock screen; never start (or show the overlay) over the keyguard.
        if (isLocked) {
            unlockAndRun { svc.toggleRun() }
            return
        }
        svc.toggleRun()
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val connected = Session.serviceConnected.value
        val run = Session.run.value
        val active = run is RunState.Countdown || run is RunState.Running
        tile.state = if (connected && (active || run is RunState.Paused)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                when {
                    !connected -> R.string.tile_off
                    active -> R.string.run_running
                    run is RunState.Paused -> R.string.run_paused
                    else -> R.string.tile_idle
                },
            )
        }
        tile.updateTile()
    }

    // The Intent overload is only used below API 34, where the PendingIntent one does not exist.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
