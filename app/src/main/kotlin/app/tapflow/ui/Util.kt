package app.tapflow.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import app.tapflow.R
import app.tapflow.model.StopReason
import app.tapflow.service.ClickerService
import java.util.Locale

enum class A11yStatus { OFF, STALE, ON }

/** True if the user switched the service on in system settings (it may still be starting). */
fun isServiceEnabledInSettings(ctx: Context): Boolean {
    val am = ctx.getSystemService(AccessibilityManager::class.java) ?: return false
    return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
        val s = it.resolveInfo.serviceInfo
        s.packageName == ctx.packageName && s.name == ClickerService::class.java.name
    }
}

fun Context.tryStart(intent: Intent): Boolean = try {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}

fun Context.openAccessibilitySettings() = tryStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

fun Context.openAppInfo() = tryStart(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
)

fun Context.openBatterySettings() =
    tryStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) || openAppInfo()

fun stopReasonRes(reason: StopReason): Int = when (reason) {
    StopReason.USER -> R.string.stop_user
    StopReason.COUNT_REACHED -> R.string.stop_count
    StopReason.TIME_REACHED -> R.string.stop_time
    StopReason.SCREEN_OFF -> R.string.stop_screen_off
    StopReason.GESTURE_FAILED -> R.string.stop_gesture_failed
    StopReason.NO_TARGETS -> R.string.stop_no_targets
    StopReason.SERVICE_LOST -> R.string.stop_service_lost
    StopReason.VOLUME_KEY -> R.string.stop_volume
}

/** Compact, locale-aware interval text: "250 ms", "1.5 s", "2 min". */
fun formatMs(ms: Long): String = when {
    ms < 1000 -> "$ms ms"
    ms < 60_000 -> "${trim(ms / 1000.0)} s"
    else -> "${trim(ms / 60_000.0)} min"
}

fun formatElapsed(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

private fun trim(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')
