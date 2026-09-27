package com.snoozy.app

import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    // Minimum time blocking must stay on before it can be turned off, in
    // minutes. "None" (0) plus the options set on the Snooze options page.
    private var lockOptions = listOf(0)

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    private val chips = mutableListOf<TextView>()
    private var selectedMinutes = 0

    private lateinit var ring: TimerRingView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ring = findViewById(R.id.ring)

        findViewById<View>(R.id.setupButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        selectedMinutes = (BlockState.lastChoiceMs(this, 60 * MINUTE) / MINUTE).toInt()

        findViewById<View>(R.id.toggleButton).setOnClickListener {
            if (BlockState.isBlocking(this)) {
                BlockState.unblock(this)
                render()
            } else if (selectedMinutes == 0) {
                BlockState.block(this, 0L)
                render()
            } else {
                confirmSnooze(selectedMinutes * MINUTE)
            }
        }

        findViewById<View>(R.id.openFriends).setOnClickListener {
            startActivity(Intent(this, FriendsActivity::class.java))
        }
        findViewById<View>(R.id.openOptions).setOnClickListener {
            startActivity(Intent(this, SnoozeOptionsActivity::class.java))
        }
    }

    private fun label(minutes: Int) = if (minutes == 0) "None" else SnoozeOptions.label(minutes)

    // A lock can't be undone, so double-check before starting one.
    private fun confirmSnooze(lockMs: Long) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_confirm, null)
        val dialog = Dialog(this)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val until = System.currentTimeMillis() + lockMs
        var time = DateFormat.getTimeFormat(this).format(until)
        if (!DateUtils.isToday(until)) time = "tomorrow at $time"
        view.findViewById<TextView>(R.id.confirmTitle).text =
            "Snooze for ${label(selectedMinutes)}?"
        view.findViewById<TextView>(R.id.confirmMessage).text =
            "Reels & Shorts will stay blocked until $time. You won't be able to turn it off before then, not even from this app."

        view.findViewById<View>(R.id.confirmYes).setOnClickListener {
            BlockState.block(this, lockMs)
            dialog.dismiss()
            render()
        }
        view.findViewById<View>(R.id.confirmNo).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    // Rows of 4 chips. The last row is padded with empty space so every chip
    // keeps the same width.
    private fun buildChips() {
        lockOptions = listOf(0) + SnoozeOptions.get(this)
        if (selectedMinutes !in lockOptions) selectedMinutes = 0

        val rows = findViewById<LinearLayout>(R.id.chipRows)
        rows.removeAllViews()
        chips.clear()
        val inflater = LayoutInflater.from(this)
        for (group in lockOptions.chunked(CHIPS_PER_ROW)) {
            val row = LinearLayout(this)
            for (minutes in group) {
                val chip = inflater.inflate(R.layout.item_chip, row, false) as TextView
                chip.text = label(minutes)
                chip.tag = minutes
                chip.setOnClickListener {
                    selectedMinutes = minutes
                    render()
                }
                row.addView(chip)
                chips += chip
            }
            repeat(CHIPS_PER_ROW - group.size) {
                val spacer = inflater.inflate(R.layout.item_chip, row, false)
                spacer.visibility = View.INVISIBLE
                row.addView(spacer)
            }
            rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onResume() {
        super.onResume()
        buildChips()
        val friends = FriendsStore.all(this)
        findViewById<TextView>(R.id.friendsSummary).text =
            if (friends.isEmpty()) "No chats yet" else "${friends.count { it.second }} of ${friends.size} allowed"
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
        val locked = blocking && remaining > 0

        findViewById<View>(R.id.setupGroup).visibility = if (serviceOn) View.GONE else View.VISIBLE
        findViewById<View>(R.id.controlGroup).visibility = if (serviceOn) View.VISIBLE else View.GONE
        if (!serviceOn) return

        val pill = findViewById<TextView>(R.id.statusPill)
        pill.text = if (blocking) "😴  Reels & Shorts are snoozing" else "☀️  Reels & Shorts are awake"
        pill.setBackgroundResource(if (blocking) R.drawable.bg_pill_mint else R.drawable.bg_pill_coral)

        val big = findViewById<TextView>(R.id.ringBig)
        val caption = findViewById<TextView>(R.id.ringCaption)
        when {
            locked -> {
                val total = BlockState.lockTotalMs(this).coerceAtLeast(remaining)
                ring.ringColor = getColor(R.color.coral)
                ring.progress = remaining.toFloat() / total
                big.text = formatDuration(remaining)
                caption.text = "until you can wake them up"
            }
            blocking -> {
                ring.ringColor = getColor(R.color.mint_deep)
                ring.progress = 1f
                big.text = "Zzz"
                caption.text = "sleeping soundly"
            }
            else -> {
                ring.progress = 0f
                big.text = if (selectedMinutes == 0) "0m" else SnoozeOptions.label(selectedMinutes)
                caption.text = "minimum snooze"
            }
        }

        // The lock length is only chosen when starting a snooze.
        findViewById<View>(R.id.chipGroup).visibility = if (blocking) View.GONE else View.VISIBLE
        chips.forEach { it.isSelected = it.tag == selectedMinutes }

        val toggle = findViewById<TextView>(R.id.toggleButton)
        toggle.text = when {
            locked -> "🔒  Locked"
            blocking -> "Wake up"
            else -> "Start snoozing"
        }
        toggle.isEnabled = !locked
        toggle.setTextColor(getColor(if (locked) R.color.ink_soft else R.color.card))

        findViewById<TextView>(R.id.hint).text = when {
            locked -> "You picked this lock. Hang in there!"
            blocking -> "Your lock is over. You can wake them up any time."
            selectedMinutes == 0 -> "No lock: you can wake them up any time."
            else -> "Once started, you can't stop early."
        }
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
        private const val CHIPS_PER_ROW = 4
    }
}
