package app.tapflow

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

object AppSettings {
    private lateinit var prefs: SharedPreferences

    val volumeStop = MutableStateFlow(true)
    val stopNotification = MutableStateFlow(false)
    val haptics = MutableStateFlow(true)
    val onboarded = MutableStateFlow(false)

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        volumeStop.value = prefs.getBoolean(K_VOLUME, true)
        stopNotification.value = prefs.getBoolean(K_NOTIF, false)
        haptics.value = prefs.getBoolean(K_HAPTICS, true)
        onboarded.value = prefs.getBoolean(K_ONBOARDED, false)
    }

    fun setVolumeStop(v: Boolean) = set(K_VOLUME, v, volumeStop)
    fun setStopNotification(v: Boolean) = set(K_NOTIF, v, stopNotification)
    fun setHaptics(v: Boolean) = set(K_HAPTICS, v, haptics)
    fun setOnboarded(v: Boolean) = set(K_ONBOARDED, v, onboarded)

    private fun set(key: String, v: Boolean, flow: MutableStateFlow<Boolean>) {
        flow.value = v
        prefs.edit().putBoolean(key, v).apply()
    }

    private const val K_VOLUME = "volume_stop"
    private const val K_NOTIF = "stop_notification"
    private const val K_HAPTICS = "haptics"
    private const val K_ONBOARDED = "onboarded"
}
