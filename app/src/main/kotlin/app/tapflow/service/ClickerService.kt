package app.tapflow.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.LocaleManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.tapflow.AppSettings
import app.tapflow.MainActivity
import app.tapflow.R
import app.tapflow.Session
import app.tapflow.model.RunState
import app.tapflow.model.StopReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Accessibility service that owns the overlay and the click engine. It requests no event types and
 * never inspects window content: it only injects the gestures the user configured.
 */
class ClickerService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var engine: ClickEngine
        private set
    private var overlay: OverlayController? = null
    private var receiversRegistered = false
    private val handler = Handler(Looper.getMainLooper())

    /**
     * ACTION_USER_PRESENT is not reliable on every device (it never arrived on the emulator), so while the
     * screen is on but locked the keyguard state is polled until it clears.
     */
    private val waitForUnlock = object : Runnable {
        override fun run() {
            when {
                !isLockedOrOff() -> overlay?.setSuppressed(false)
                getSystemService(PowerManager::class.java).isInteractive -> handler.postDelayed(this, UNLOCK_POLL_MS)
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // Also sent when the display drops into the always-on display (doze).
                Intent.ACTION_SCREEN_OFF -> {
                    if (Session.isBusy) engine.stop(StopReason.SCREEN_OFF)
                    handler.removeCallbacks(waitForUnlock)
                    overlay?.setSuppressed(true)
                }
                // Turning the screen on shows the lock screen first; wait for the unlock in that case.
                Intent.ACTION_SCREEN_ON -> {
                    val locked = isLockedOrOff()
                    overlay?.setSuppressed(locked)
                    if (locked) handler.postDelayed(waitForUnlock, UNLOCK_POLL_MS)
                }
                Intent.ACTION_USER_PRESENT -> overlay?.setSuppressed(false)
                ACTION_STOP -> engine.stop(StopReason.USER)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        engine = ClickEngine(this, scope)
        overlay = OverlayController(this).also { it.setSuppressed(isLockedOrOff()) }
        if (isLockedOrOff()) handler.postDelayed(waitForUnlock, UNLOCK_POLL_MS)

        ContextCompat.registerReceiver(
            this, receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(ACTION_STOP)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiversRegistered = true

        // A previous process may have died mid-run; never leave the UI claiming it is still running.
        if (Session.isBusy) Session.run.value = RunState.Finished(StopReason.SERVICE_LOST)
        Session.serviceConnected.value = true

        scope.launch { Session.targets.collect { overlay?.onTargetsChanged(it) } }
        scope.launch {
            combine(Session.run, Session.stats) { r, s -> r to s }.collect { overlay?.render() }
        }
        // Posting on every click would hit the system's notification rate limit, so only react to
        // coarse state changes (none / active / paused) and to the setting itself.
        scope.launch {
            combine(Session.run.map { it.notificationKind() }, AppSettings.stopNotification) { k, on -> k to on }
                .distinctUntilChanged()
                .collect { (kind, on) -> updateNotification(kind, on) }
        }
    }

    private enum class NotifKind { NONE, ACTIVE, PAUSED }

    private fun RunState.notificationKind() = when (this) {
        is RunState.Countdown, RunState.Running -> NotifKind.ACTIVE
        RunState.Paused -> NotifKind.PAUSED
        else -> NotifKind.NONE
    }

    /**
     * Context whose resources follow the per-app language (Android 13+). A service otherwise gets the
     * system language, so the overlay would ignore the language chosen for TapFlow in system settings.
     */
    fun localizedContext(): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = getSystemService(LocaleManager::class.java)?.applicationLocales
            if (locales != null && !locales.isEmpty) {
                val config = Configuration(resources.configuration).apply { setLocales(locales) }
                return createConfigurationContext(config)
            }
        }
        return this
    }

    /** True while the screen is off or dozing (always-on display), or the lock screen is up. */
    private fun isLockedOrOff(): Boolean =
        !getSystemService(PowerManager::class.java).isInteractive || getSystemService(KeyguardManager::class.java).isKeyguardLocked

    fun showOverlay() = overlay?.show()
    fun hideOverlay() = overlay?.hide()
    fun toggleRun() = overlay?.toggleRun()
    fun startFromApp(): Boolean = overlay?.startFromApp() ?: false
    fun playPause() = overlay?.onPlayPause()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Emergency stop: Volume Down halts an active run (and is swallowed only in that case).
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN || !AppSettings.volumeStop.value || !Session.isBusy) return false
        if (event.action == KeyEvent.ACTION_DOWN) engine.stop(StopReason.VOLUME_KEY)
        return true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.relayout()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (instance === this) instance = null
        if (::engine.isInitialized && Session.isBusy) engine.stop(StopReason.SERVICE_LOST)
        handler.removeCallbacks(waitForUnlock)
        overlay?.hide()
        overlay = null
        if (receiversRegistered) {
            runCatching { unregisterReceiver(receiver) }
            receiversRegistered = false
        }
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        Session.serviceConnected.value = false
        scope.cancel()
    }

    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled()
    private fun updateNotification(kind: NotifKind, enabled: Boolean) {
        val nm = NotificationManagerCompat.from(this)
        if (!enabled || kind == NotifKind.NONE || !nm.areNotificationsEnabled()) {
            nm.cancel(NOTIFICATION_ID)
            return
        }
        val ctx = localizedContext()
        val channel = NotificationChannel(CHANNEL_ID, ctx.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)

        val stopIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_STOP).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (kind == NotifKind.PAUSED) R.string.notif_paused else R.string.notif_running
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_tap)
                .setContentTitle(ctx.getString(R.string.app_name))
                .setContentText(ctx.getString(text))
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .addAction(0, ctx.getString(R.string.notif_stop), stopIntent)
                .build(),
        )
    }

    companion object {
        @Volatile
        var instance: ClickerService? = null
            private set

        const val ACTION_STOP = "app.tapflow.action.STOP"
        private const val CHANNEL_ID = "run_state"
        private const val NOTIFICATION_ID = 1
        private const val UNLOCK_POLL_MS = 500L
    }
}
