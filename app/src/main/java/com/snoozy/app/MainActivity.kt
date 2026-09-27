package com.snoozy.app

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
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
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    // Minimum time blocking must stay on before it can be turned off.
    // (chip label, big text in the ring, length in ms)
    private val lockOptions = listOf(
        Triple("None", "0m", 0L),
        Triple("15m", "15m", 15 * MINUTE),
        Triple("30m", "30m", 30 * MINUTE),
        Triple("1h", "1h", 60 * MINUTE),
        Triple("2h", "2h", 2 * 60 * MINUTE),
        Triple("4h", "4h", 4 * 60 * MINUTE),
        Triple("8h", "8h", 8 * 60 * MINUTE),
        Triple("24h", "24h", 24 * 60 * MINUTE),
    )

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    private val chips = mutableListOf<TextView>()
    private var selected = 0

    private lateinit var ring: TimerRingView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ring = findViewById(R.id.ring)

        findViewById<View>(R.id.setupButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val lastChoice = BlockState.lastChoiceMs(this, 60 * MINUTE)
        selected = lockOptions.indexOfFirst { it.third == lastChoice }.coerceAtLeast(0)
        buildChips()

        findViewById<View>(R.id.toggleButton).setOnClickListener {
            if (BlockState.isBlocking(this)) {
                BlockState.unblock(this)
                render()
            } else if (lockOptions[selected].third == 0L) {
                BlockState.block(this, 0L)
                render()
            } else {
                confirmSnooze(lockOptions[selected].third)
            }
        }

        findViewById<View>(R.id.allowAll).setOnClickListener {
            FriendsStore.setAll(this, true)
            renderFriends()
        }
        findViewById<View>(R.id.blockAll).setOnClickListener {
            FriendsStore.setAll(this, false)
            renderFriends()
        }

        val copyIds = findViewById<View>(R.id.copyIds)
        copyIds.visibility = if (DebugCapture.ENABLED) View.VISIBLE else View.GONE
        copyIds.setOnClickListener {
            val dump = DebugCapture.read(this)
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Snoozy screen IDs", dump))
            val msg = if (dump.isEmpty()) "Nothing captured yet. Open Instagram first." else "Copied!"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // Built on resume rather than every tick, so switches aren't redrawn mid-tap.
    private fun renderFriends() {
        val list = findViewById<LinearLayout>(R.id.friendsList)
        list.removeAllViews()
        val friends = FriendsStore.all(this)
        val inflater = LayoutInflater.from(this)
        for ((name, allowed) in friends) {
            val row = inflater.inflate(R.layout.item_friend, list, false)
            row.findViewById<TextView>(R.id.friendName).text = name
            val switch = row.findViewById<Switch>(R.id.friendSwitch)
            switch.isChecked = allowed
            switch.setOnCheckedChangeListener { _, checked -> FriendsStore.set(this, name, checked) }
            list.addView(row)
        }
        findViewById<View>(R.id.friendsEmpty).visibility = if (friends.isEmpty()) View.VISIBLE else View.GONE
    }

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
            "Snooze for ${lockOptions[selected].first}?"
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

    private fun buildChips() {
        val rows = listOf<LinearLayout>(findViewById(R.id.chipRow1), findViewById(R.id.chipRow2))
        val inflater = LayoutInflater.from(this)
        lockOptions.forEachIndexed { i, option ->
            val row = rows[i / 4]
            val chip = inflater.inflate(R.layout.item_chip, row, false) as TextView
            chip.text = option.first
            chip.setOnClickListener {
                selected = i
                render()
            }
            row.addView(chip)
            chips += chip
        }
    }

    override fun onResume() {
        super.onResume()
        renderFriends()
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
                big.text = lockOptions[selected].second
                caption.text = "minimum snooze"
            }
        }

        // The lock length is only chosen when starting a snooze.
        findViewById<View>(R.id.chipGroup).visibility = if (blocking) View.GONE else View.VISIBLE
        chips.forEachIndexed { i, chip -> chip.isSelected = i == selected }

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
            selected == 0 -> "No lock: you can wake them up any time."
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
    }
}
