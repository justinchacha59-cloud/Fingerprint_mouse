package com.example.fingerprintmouse

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.math.roundToInt

/**
 * There is no public API that lets an app silently turn on an Accessibility
 * Service for itself — that has always required an explicit, user-driven
 * step in system Settings, by design (it's a very powerful permission).
 * So this screen's job is just: show current status, and route the user
 * to the right settings screen when they want to change it. It also holds
 * the control-mode picker and speed slider that FingerprintMouseService
 * reads via ControlModePrefs.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var serviceSwitch: MaterialSwitch
    private lateinit var statusText: TextView
    private lateinit var modeRadioGroup: RadioGroup
    private lateinit var modeHintText: TextView
    private lateinit var speedSeekBar: SeekBar
    private lateinit var speedValueText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serviceSwitch = findViewById(R.id.switchService)
        statusText = findViewById(R.id.textStatus)
        modeRadioGroup = findViewById(R.id.radioGroupMode)
        modeHintText = findViewById(R.id.textModeHint)
        speedSeekBar = findViewById(R.id.seekBarSpeed)
        speedValueText = findViewById(R.id.textSpeedValue)
    }

    override fun onResume() {
        super.onResume()
        // The user can only actually change the service's state on the system
        // Settings screen, which we navigate away to — so re-check on every
        // return to this screen rather than trusting the switch's last state.
        refreshStatusFromSystem()
        refreshModeFromPrefs()
        refreshSpeedFromPrefs()
    }

    private fun refreshStatusFromSystem() {
        val enabled = isAccessibilityServiceEnabled()

        // Swap the listener out while we set the checked state programmatically,
        // so this sync doesn't itself trigger openAccessibilitySettings().
        serviceSwitch.setOnCheckedChangeListener(null)
        serviceSwitch.isChecked = enabled
        serviceSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked != enabled) {
                openAccessibilitySettings()
            }
        }

        statusText.text = getString(if (enabled) R.string.status_enabled else R.string.status_disabled)
    }

    /** Reads the saved control mode and reflects it in the radio buttons + hint text. */
    private fun refreshModeFromPrefs() {
        val mode = ControlModePrefs.getMode(this)

        // Same defensive pattern as the service switch above: set state first,
        // attach the listener after, so restoring state never fires a write.
        modeRadioGroup.setOnCheckedChangeListener(null)
        modeRadioGroup.check(if (mode == ControlMode.TILT) R.id.radioTilt else R.id.radioFingerprint)
        modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val newMode = if (checkedId == R.id.radioTilt) ControlMode.TILT else ControlMode.FINGERPRINT
            ControlModePrefs.setMode(this, newMode)
            updateModeHint(newMode)
        }

        updateModeHint(mode)
    }

    private fun updateModeHint(mode: ControlMode) {
        modeHintText.text = getString(
            if (mode == ControlMode.TILT) R.string.mode_hint_tilt else R.string.mode_hint_fingerprint
        )
    }

    /** Reads the saved speed multiplier and reflects it in the slider + label. */
    private fun refreshSpeedFromPrefs() {
        val speed = ControlModePrefs.getSpeedMultiplier(this)

        speedSeekBar.setOnSeekBarChangeListener(null)
        speedSeekBar.progress = (speed * 100).roundToInt()
        updateSpeedLabel(speed)

        speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val newSpeed = progress / 100f
                updateSpeedLabel(newSpeed)
                ControlModePrefs.setSpeedMultiplier(this@MainActivity, newSpeed)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun updateSpeedLabel(speed: Float) {
        speedValueText.text = getString(R.string.speed_value_format, speed)
    }

    /** Reads Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES and checks for our service. */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, FingerprintMouseService::class.java)

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)

        while (splitter.hasNext()) {
            val component = ComponentName.unflattenFromString(splitter.next())
            if (component == expected) return true
        }
        return false
    }

    private fun openAccessibilitySettings() {
        Toast.makeText(this, R.string.toast_find_service, Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}
