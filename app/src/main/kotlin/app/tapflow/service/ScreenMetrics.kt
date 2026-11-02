package app.tapflow.service

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

data class ScreenSize(val width: Int, val height: Int)

/** Full display bounds in pixels (including system bars), in the current orientation. */
fun Context.screenSize(): ScreenSize {
    val wm = getSystemService(WindowManager::class.java)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val b = wm.currentWindowMetrics.bounds
        ScreenSize(b.width(), b.height())
    } else {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        ScreenSize(m.widthPixels, m.heightPixels)
    }
}
