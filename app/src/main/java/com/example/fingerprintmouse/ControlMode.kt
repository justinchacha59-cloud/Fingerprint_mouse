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
 * FingerprintMouseService (reader) agree on where settings live, without
 * either needing to know about the other's internals.
 */
object ControlModePrefs {
    private const val PREFS_NAME = "fingerprint_mouse_prefs"
    const val MODE_KEY = "control_mode"
    const val SPEED_KEY = "speed_multiplier"

    // Default is deliberately above 1.0x: the base tuning in
    // FingerprintMouseService is calibrated conservatively, and most people
    // want movement noticeably faster than that right out of the box.
    const val DEFAULT_SPEED_MULTIPLIER = 1.5f
    const val MIN_SPEED_MULTIPLIER = 0.5f
    const val MAX_SPEED_MULTIPLIER = 3.0f

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMode(context: Context): ControlMode {
        val raw = prefs(context).getString(MODE_KEY, ControlMode.FINGERPRINT.name)
        return runCatching { ControlMode.valueOf(raw ?: ControlMode.FINGERPRINT.name) }
            .getOrDefault(ControlMode.FINGERPRINT)
    }

    fun setMode(context: Context, mode: ControlMode) {
        prefs(context).edit().putString(MODE_KEY, mode.name).apply()
    }

    fun getSpeedMultiplier(context: Context): Float =
        prefs(context).getFloat(SPEED_KEY, DEFAULT_SPEED_MULTIPLIER)
            .coerceIn(MIN_SPEED_MULTIPLIER, MAX_SPEED_MULTIPLIER)

    fun setSpeedMultiplier(context: Context, value: Float) {
        prefs(context).edit()
            .putFloat(SPEED_KEY, value.coerceIn(MIN_SPEED_MULTIPLIER, MAX_SPEED_MULTIPLIER))
            .apply()
    }

    /** The service uses this to notice changes made from MainActivity while it's already running. */
    fun registerListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }
}
