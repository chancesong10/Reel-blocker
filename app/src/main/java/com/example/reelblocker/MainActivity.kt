package com.example.reelblocker

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {

    // Minimum time blocking must stay on before "Unblock" becomes available.
    private val lockOptions = listOf(
        "No minimum" to 0L,
        "15 minutes" to 15 * MINUTE,
        "30 minutes" to 30 * MINUTE,
        "1 hour" to 60 * MINUTE,
        "2 hours" to 2 * 60 * MINUTE,
        "4 hours" to 4 * 60 * MINUTE,
        "8 hours" to 8 * 60 * MINUTE,
        "24 hours" to 24 * 60 * MINUTE,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    private lateinit var lockSpinner: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.setupButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        lockSpinner = findViewById(R.id.lockSpinner)
        lockSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, lockOptions.map { it.first }
        )

        findViewById<Button>(R.id.toggleButton).setOnClickListener {
            if (BlockState.isBlocking(this)) {
                BlockState.unblock(this)
            } else {
                BlockState.block(this, lockOptions[lockSpinner.selectedItemPosition].second)
            }
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun render() {
        val serviceOn = isServiceEnabled()
        val blocking = BlockState.isBlocking(this)
        val remaining = BlockState.lockRemainingMs(this)

        findViewById<TextView>(R.id.status).text = when {
            !serviceOn -> "Not set up yet\nTap below, then turn on \"Snoozy\"."
            blocking -> "✅ Reels and Shorts are blocked"
            else -> "Reels and Shorts are allowed"
        }
        findViewById<View>(R.id.setupGroup).visibility = if (serviceOn) View.GONE else View.VISIBLE
        findViewById<View>(R.id.controlGroup).visibility = if (serviceOn) View.VISIBLE else View.GONE

        // The lock length is only chosen when turning blocking on.
        val pickVisibility = if (blocking) View.GONE else View.VISIBLE
        findViewById<View>(R.id.lockLabel).visibility = pickVisibility
        lockSpinner.visibility = pickVisibility

        val toggle = findViewById<Button>(R.id.toggleButton)
        toggle.text = if (blocking) "Unblock" else "Block Reels & Shorts"
        toggle.isEnabled = !blocking || remaining == 0L

        findViewById<TextView>(R.id.lockText).text =
            if (blocking && remaining > 0) "You can unblock in ${formatDuration(remaining)}" else ""
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms + 999) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun isServiceEnabled(): Boolean {
        val me = ComponentName(this, ReelBlockerService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    companion object {
        private const val MINUTE = 60_000L
    }
}
