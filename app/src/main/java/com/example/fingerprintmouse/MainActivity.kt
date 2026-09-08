package com.example.fingerprintmouse

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * There is no public API that lets an app silently turn on an Accessibility
 * Service for itself — that has always required an explicit, user-driven
 * step in system Settings, by design (it's a very powerful permission).
 * So this screen's job is just: show current status, and route the user
 * to the right settings screen when they want to change it.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var serviceSwitch: MaterialSwitch
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serviceSwitch = findViewById(R.id.switchService)
        statusText = findViewById(R.id.textStatus)
    }

    override fun onResume() {
        super.onResume()
        // The user can only actually change the service's state on the system
        // Settings screen, which we navigate away to — so re-check on every
        // return to this screen rather than trusting the switch's last state.
        refreshStatusFromSystem()
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
