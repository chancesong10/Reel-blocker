package com.example.reelblocker

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.enableButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        val enabled = isServiceEnabled()
        findViewById<TextView>(R.id.status).text =
            if (enabled) "✅ Blocking is ON\nReels and Shorts are blocked."
            else "Blocking is OFF\nTap below, then enable \"Reel Blocker\" in Accessibility settings."
        // No off switch in the app on purpose: once enabled, it stays on.
        findViewById<Button>(R.id.enableButton).visibility =
            if (enabled) View.GONE else View.VISIBLE
    }

    private fun isServiceEnabled(): Boolean {
        val me = ComponentName(this, ReelBlockerService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }
}
