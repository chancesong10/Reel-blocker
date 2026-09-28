package com.snoozy.app

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView

class ReelBlockerService : AccessibilityService() {

    companion object {
        private const val TAG = "ReelBlocker"

        // Set to true, open Reels/Shorts, and read Logcat (tag "ReelBlocker")
        // to find the current view IDs when Instagram/YouTube update their UI.
        private const val DEBUG_DUMP_IDS = false

        private const val CHECK_INTERVAL_MS = 80L
        // Right after a tap or a new screen, check this often for a while so
        // a Reel is caught the moment it opens.
        private const val FAST_CHECK_MS = 30L
        private const val FAST_WATCH_MS = 1500L
        // While a Reel is open, keep re-checking until it's gone.
        private const val POLL_MS = 150L
        // While the modal is up, how often to check whether you're swiping
        // away to the home screen or recent apps, so it doesn't follow you.
        private const val LEAVE_WATCH_MS = 100L
        // Back takes a moment to close the Reel. Only press it again if the
        // Reel is still there after this long, and at most MAX_BACKS times,
        // so an extra press never backs out of the screen you came from.
        private const val BACK_RETRY_MS = 1000L
        private const val MAX_BACKS = 2
        // After tapping the Reels/Shorts tab, how long the modal waits for the
        // player to appear before assuming it was a false alarm.
        private const val PREEMPT_GRACE_MS = 1500L
        // A single missed check (mid-animation, tree not ready) is not enough
        // to count the Reel as closed.
        private const val MISSES_BEFORE_GONE = 2
        // A Reel counts as "sent in a chat" if it opens this soon after a
        // DM chat was on screen.
        private const val FROM_DM_WINDOW_MS = 2500L
        // Ignore scroll events while the chat's Reel is still opening.
        private const val PASS_SETTLE_MS = 800L
    }

    private class Target(
        // Also the label of the app's Reels/Shorts tab button.
        val name: String,
        // View IDs that only exist on the Reels / Shorts players.
        val playerIds: List<String>,
        // A DM chat screen, and the chat name at its top. Empty = no chat Reels.
        val dmChatIds: List<String> = emptyList(),
        val dmTitleIds: List<String> = emptyList(),
    )

    // These IDs change when the apps update. Use DEBUG_DUMP_IDS to find new ones.
    private val targets = mapOf(
        "com.instagram.android" to Target(
            "Reels",
            listOf(
                "com.instagram.android:id/clips_viewer_view_pager",
                "com.instagram.android:id/clips_video_container",
                "com.instagram.android:id/clips_root_layout",
            ),
            // From a real DM chat screen (Instagram, September 2026).
            dmChatIds = listOf(
                "com.instagram.android:id/row_thread_composer_edittext",
                "com.instagram.android:id/thread_fragment_container",
            ),
            dmTitleIds = listOf("com.instagram.android:id/header_title"),
        ),
        "com.google.android.youtube" to Target(
            "Shorts",
            listOf(
                "com.google.android.youtube:id/reel_player_page_container",
                "com.google.android.youtube:id/reel_recycler",
                "com.google.android.youtube:id/reel_watch_player",
            ),
        ),
    )

    private val handler = Handler(Looper.getMainLooper())
    // When the queued check will run; 0 = none queued.
    private var checkDueAt = 0L
    private var lastCheck = 0L
    private var fastUntil = 0L
    private var preemptUntil = 0L
    private var missCount = 0

    // Chat Reels: the DM chat last seen, and whether the Reel now on screen
    // is the one that chat sent (a "pass"). Swiping to another Reel ends it.
    private var dmChat: String? = null
    private var dmSeenAt = 0L
    private var playerShowing = false
    private var pass = false
    private var passStartedAt = 0L

    // Back presses sent for the Reel now open.
    private var backs = 0
    private var lastBackAt = 0L

    private var modal: View? = null
    // False while the modal is only up because the Reels tab was tapped and
    // the player hasn't shown yet, so it can still be a false alarm.
    private var modalConfirmed = false
    private var mutedMusic = false

    private val checkRunnable = Runnable {
        checkDueAt = 0L
        check()
    }

    private val leaveWatch = object : Runnable {
        override fun run() {
            if (modal == null) return
            if (anotherAppTakingOver()) hideModal() else handler.postDelayed(this, LEAVE_WATCH_MS)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val target = targets[event.packageName?.toString()] ?: return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            fastUntil = SystemClock.uptimeMillis() + FAST_WATCH_MS
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && isTabTap(event, target)) {
            // The Reels tab is never a chat's Reel, even right after a chat.
            dmSeenAt = 0L
            preempt(target)
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && pass) onScrollDuringPass(event, target)
        scheduleCheck(0L)
    }

    // Swiping from the chat's Reel to the next one ends the pass.
    private fun onScrollDuringPass(event: AccessibilityEvent, target: Target) {
        val id = event.source?.viewIdResourceName
        if (SystemClock.uptimeMillis() - passStartedAt < PASS_SETTLE_MS) return
        if (id in target.playerIds) pass = false
    }

    // The player only shows up in the tree after it has drawn, which lets a
    // few frames of video through. Tapping the Reels/Shorts tab is the common
    // way in, so put the modal up on the tap itself, before the video loads.
    private fun isTabTap(event: AccessibilityEvent, target: Target): Boolean {
        val source = event.source
        val labels = event.text.map { it.toString() } + listOfNotNull(
            event.contentDescription?.toString(),
            source?.contentDescription?.toString(),
            source?.text?.toString(),
        )
        return labels.any { it.trim().equals(target.name, ignoreCase = true) }
    }

    private fun preempt(target: Target) {
        if (!BlockState.isBlocking(this)) return
        showModal(target, confirmed = false)
        muteMusic(true)
        preemptUntil = SystemClock.uptimeMillis() + PREEMPT_GRACE_MS
    }

    // Content-changed events fire constantly, so coalesce them into at most
    // one tree scan per CHECK_INTERVAL_MS, but never drop the last one. An
    // event can pull a slow queued poll forward, never push it back.
    private fun scheduleCheck(minDelay: Long) {
        val now = SystemClock.uptimeMillis()
        val interval = if (now < fastUntil) FAST_CHECK_MS else CHECK_INTERVAL_MS
        val due = now + maxOf(minDelay, interval - (now - lastCheck), 0L)
        if (checkDueAt != 0L && checkDueAt <= due) return
        handler.removeCallbacks(checkRunnable)
        checkDueAt = due
        handler.postAtTime(checkRunnable, due)
    }

    private fun check() {
        lastCheck = SystemClock.uptimeMillis()

        val root = rootInActiveWindow
        val target = targets[root?.packageName?.toString()]
        // Blocking turned off, or another app in front: stand down.
        if (!BlockState.isBlocking(this) || (root != null && target == null)) {
            hideModal()
            muteMusic(false)
            return
        }
        if (root != null && DEBUG_DUMP_IDS) dumpIds(root, 0)

        val player = if (root != null && target != null) findVisible(root, target.playerIds) else null
        if (player == null) {
            if (root != null && target != null) noteDmChat(root, target)
            missCount++
            val loadingAfterTap = lastCheck < preemptUntil
            if (missCount >= MISSES_BEFORE_GONE && !loadingAfterTap) {
                // The Reel is closed, or the tab tap was a false alarm.
                playerShowing = false
                pass = false
                backs = 0
                muteMusic(false)
                if (!modalConfirmed) hideModal()
            }
            // Keep looking while a Reel may be opening or still closing.
            if (loadingAfterTap || missCount < MISSES_BEFORE_GONE || lastCheck < fastUntil) {
                scheduleCheck(0L)
            }
            return
        }

        missCount = 0
        preemptUntil = 0L
        if (!playerShowing) {
            // A Reel just opened. Was it tapped in an allowed chat?
            playerShowing = true
            backs = 0
            val chat = dmChat
            pass = chat != null && lastCheck - dmSeenAt < FROM_DM_WINDOW_MS &&
                FriendsStore.isAllowed(this, chat)
            passStartedAt = lastCheck
        }
        if (pass) {
            if (!modalConfirmed) hideModal()
            muteMusic(false)
            scheduleCheck(POLL_MS)
            return
        }

        // Mid-swipe to home/recents Instagram can still look active, and
        // pressing Back then could act on the launcher instead.
        if (anotherAppTakingOver()) {
            hideModal()
            scheduleCheck(POLL_MS)
            return
        }

        // Send you back to where the Reel was opened from, then say why.
        muteMusic(true)
        showModal(target!!, confirmed = true)
        if (backs < MAX_BACKS && (backs == 0 || lastCheck - lastBackAt >= BACK_RETRY_MS)) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            backs++
            lastBackAt = lastCheck
        }
        scheduleCheck(POLL_MS)
    }

    // Remember which DM chat is open, so a Reel tapped in it can be allowed.
    private fun noteDmChat(root: AccessibilityNodeInfo, target: Target) {
        if (target.dmChatIds.isEmpty() || findVisible(root, target.dmChatIds) == null) return
        // Back in a chat means the last Reel is closed, so the next Reel
        // tapped here is judged fresh (and can get its own pass).
        playerShowing = false
        pass = false
        val name = findVisible(root, target.dmTitleIds)?.text?.toString()?.trim()
        if (name.isNullOrEmpty()) return
        dmChat = name
        dmSeenAt = lastCheck
        FriendsStore.remember(this, name)
    }

    private fun findVisible(root: AccessibilityNodeInfo, ids: List<String>): AccessibilityNodeInfo? =
        ids.firstNotNullOfOrNull { id ->
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser }
        }

    // A small card over the dimmed app, saying Reels are snoozing. It
    // swallows touches until OK is pressed. Accessibility overlays need no
    // extra permission.
    private fun showModal(target: Target, confirmed: Boolean) {
        modalConfirmed = modalConfirmed || confirmed
        val title = "Shh… ${target.name} are snoozing"
        modal?.let {
            it.findViewById<TextView>(R.id.snoozeTitle).text = title
            return
        }

        val view = LayoutInflater.from(this).inflate(R.layout.snooze_modal, null)
        view.findViewById<TextView>(R.id.snoozeTitle).text = title
        view.findViewById<View>(R.id.snoozeOk).setOnClickListener {
            hideModal()
            // If the Reel is somehow still open, the next check sends you back again.
            backs = 0
            scheduleCheck(0L)
        }
        getSystemService(WindowManager::class.java).addView(view, modalParams())
        modal = view
        handler.postDelayed(leaveWatch, LEAVE_WATCH_MS)
    }

    private fun hideModal() {
        handler.removeCallbacks(leaveWatch)
        modal?.let { getSystemService(WindowManager::class.java).removeView(it) }
        modal = null
        modalConfirmed = false
    }

    // Instagram/YouTube only report their own events, so swiping up to the
    // home screen or recent apps goes unnoticed until it's over. The window
    // list does show the launcher appearing right away. Only each window's
    // app name is read. Small windows (picture-in-picture) don't count.
    private fun anotherAppTakingOver(): Boolean {
        val metrics = resources.displayMetrics
        val screenArea = metrics.widthPixels.toLong() * metrics.heightPixels
        val bounds = Rect()
        return windows.any { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@any false
            val pkg = window.root?.packageName?.toString() ?: return@any false
            if (pkg in targets || pkg == packageName) return@any false
            window.getBoundsInScreen(bounds)
            bounds.width().toLong() * bounds.height() * 2 >= screenArea
        }
    }

    private fun modalParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Not focusable, so Back still reaches the app underneath.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        // Dim the whole screen, under the system bars too.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    // Silence the Reel for the moment it plays before Back closes it.
    private fun muteMusic(mute: Boolean) {
        if (mute == mutedMusic) return
        mutedMusic = mute
        getSystemService(AudioManager::class.java).adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            0
        )
    }

    private fun dumpIds(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > 40) return
        node.viewIdResourceName?.let { Log.d(TAG, "${"  ".repeat(depth)}$it") }
        for (i in 0 until node.childCount) dumpIds(node.getChild(i), depth + 1)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(checkRunnable)
        hideModal()
        muteMusic(false)
        super.onDestroy()
    }
}
