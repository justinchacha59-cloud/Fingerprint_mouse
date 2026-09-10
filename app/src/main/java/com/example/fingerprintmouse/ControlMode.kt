package com.example.fingerprintmouse

import android.content.Context
import android.content.SharedPreferences

/** The two input schemes the user can pick between on the main screen. */
enum class ControlMode {
    FINGERPRINT,
    TILT
}

/**
 * Tiny wrapper around SharedPreferences so MainActivity (writer) and
 * FingerprintMouseService (reader) agree on where the selected mode lives,
 * without either needing to know about the other's internals.
 */
object ControlModePrefs {
    private const val PREFS_NAME = "fingerprint_mouse_prefs"
    const val KEY = "control_mode"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMode(context: Context): ControlMode {
        val raw = prefs(context).getString(KEY, ControlMode.FINGERPRINT.name)
        return runCatching { ControlMode.valueOf(raw ?: ControlMode.FINGERPRINT.name) }
            .getOrDefault(ControlMode.FINGERPRINT)
    }

    fun setMode(context: Context, mode: ControlMode) {
        prefs(context).edit().putString(KEY, mode.name).apply()
    }

    /** The service uses this to notice a mode switch made from MainActivity while it's running. */
    fun registerListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }
}
