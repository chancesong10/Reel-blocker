package com.example.reelblocker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView

// The black "blocked" screen. The app underneath has already been sent back
// off the Reel/Short, so closing this (tap, Back, or Home) is safe.
class BlockedActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blocked)
        showMessage(intent)
        findViewById<View>(android.R.id.content).setOnClickListener { finish() }
    }

    // Blocked again while already showing: reuse this screen.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showMessage(intent)
    }

    private fun showMessage(intent: Intent) {
        findViewById<TextView>(R.id.blockedText).text =
            intent.getStringExtra(EXTRA_MESSAGE) ?: "Blocked"
    }

    companion object {
        const val EXTRA_MESSAGE = "message"
    }
}
