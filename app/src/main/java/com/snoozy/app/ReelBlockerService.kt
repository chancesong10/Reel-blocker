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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

class ReelBlockerService : AccessibilityService() {

    companion object {
        private const val TAG = "ReelBlocker"

        // Set to true, open Reels/Shorts, and read Logcat (tag "ReelBlocker")
        // to find the current view IDs when Instagram/YouTube update their UI.
        private const val DEBUG_DUMP_IDS = false

        private const val CHECK_INTERVAL_MS = 80L
        // While covering a Reel, keep re-checking so the cover disappears as
        // soon as you navigate away (even to an app we get no events from).
        private const val POLL_MS = 300L
        // After tapping the Reels/Shorts tab, how long the cover waits for the
        // player to appear before assuming it was a false alarm.
        private const val PREEMPT_GRACE_MS = 1500L
        // A single missed check (mid-animation, tree not ready) is not enough
        // to uncover the Reel; that caused the cover to flicker on and off.
        private const val MISSES_BEFORE_HIDE = 2
        // A Reel counts as "sent by a friend" if it opens this soon after a
        // DM chat was on screen.
        private const val FROM_DM_WINDOW_MS = 2500L
        // Ignore scroll events while the friend's Reel is still opening.
        private const val PASS_SETTLE_MS = 800L
        private const val DEBUG_CAPTURE_EVERY_MS = 1000L
    }

    private class Target(
        // Also the label of the app's Reels/Shorts tab button.
        val name: String,
        // View IDs that only exist on the Reels / Shorts players.
        val playerIds: List<String>,
        // The app's own bottom tab bar, left uncovered so you can navigate away.
        val tabBarIds: List<String>,
        // A DM chat screen, and the chat name at its top. Empty = no friend Reels.
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
            listOf("com.instagram.android:id/tab_bar"),
            // TODO: best guesses until the real IDs are captured with DebugCapture.
            dmChatIds = listOf(
                "com.instagram.android:id/row_thread_composer_edittext",
                "com.instagram.android:id/direct_thread_composer",
                "com.instagram.android:id/thread_fragment_container",
            ),
            dmTitleIds = listOf(
                "com.instagram.android:id/thread_title",
                "com.instagram.android:id/action_bar_title",
            ),
        ),
        "com.google.android.youtube" to Target(
            "Shorts",
            listOf(
                "com.google.android.youtube:id/reel_player_page_container",
                "com.google.android.youtube:id/reel_recycler",
                "com.google.android.youtube:id/reel_watch_player",
            ),
            listOf("com.google.android.youtube:id/pivot_bar"),
        ),
    )

    private val handler = Handler(Looper.getMainLooper())
    private var checkPending = false
    private var lastCheck = 0L
    private var preemptUntil = 0L
    private var missCount = 0
    private var lastDebugCapture = 0L

    // Friend Reels: the DM chat last seen, and whether the Reel now on screen
    // is the one that chat sent (a "pass"). Swiping to another Reel ends it.
    private var dmChat: String? = null
    private var dmSeenAt = 0L
    private var playerShowing = false
    private var pass = false
    private var passStartedAt = 0L

    private var overlay: View? = null
    private var overlayBounds = Rect()
    private var mutedMusic = false

    private val checkRunnable = Runnable {
        checkPending = false
        check()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val target = targets[event.packageName?.toString()] ?: return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // Any other tap (e.g. a different tab) cancels the grace period.
            if (isTabTap(event, target)) {
                // The Reels tab is never a friend's Reel, even right after a chat.
                dmSeenAt = 0L
                preemptCover(target)
            } else {
                preemptUntil = 0L
            }
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && pass) onScrollDuringPass(event, target)
        scheduleCheck(0L)
    }

    // Swiping from the friend's Reel to the next one ends the pass.
    private fun onScrollDuringPass(event: AccessibilityEvent, target: Target) {
        val id = event.source?.viewIdResourceName
        if (DebugCapture.ENABLED) DebugCapture.captureScroll(this, id, event.className)
        if (SystemClock.uptimeMillis() - passStartedAt < PASS_SETTLE_MS) return
        if (id in target.playerIds) pass = false
    }

    // The player only shows up in the tree after it has drawn, which lets a
    // few frames of video through. Tapping the Reels/Shorts tab is the common
    // way in, so cover the screen on the tap itself, before the video loads.
    private fun isTabTap(event: AccessibilityEvent, target: Target): Boolean {
        val source = event.source
        val labels = event.text.map { it.toString() } + listOfNotNull(
            event.contentDescription?.toString(),
            source?.contentDescription?.toString(),
            source?.text?.toString(),
        )
        return labels.any { it.trim().equals(target.name, ignoreCase = true) }
    }

    private fun preemptCover(target: Target) {
        if (!BlockState.isBlocking(this)) return
        val root = rootInActiveWindow ?: return
        val bounds = Rect().also { root.getBoundsInScreen(it) }
        clipAboveTabBar(root, target, bounds)
        showCover("Shh… ${target.name} are snoozing", bounds)
        preemptUntil = SystemClock.uptimeMillis() + PREEMPT_GRACE_MS
    }

    private fun clipAboveTabBar(root: AccessibilityNodeInfo, target: Target, bounds: Rect) {
        findVisible(root, target.tabBarIds)?.let { tabBar ->
            val tabBounds = Rect().also { tabBar.getBoundsInScreen(it) }
            if (tabBounds.top > bounds.top) bounds.bottom = minOf(bounds.bottom, tabBounds.top)
        }
    }

    // Content-changed events fire constantly, so coalesce them into at most
    // one tree scan per CHECK_INTERVAL_MS, but never drop the last one.
    private fun scheduleCheck(minDelay: Long) {
        if (checkPending) return
        checkPending = true
        val sinceLast = SystemClock.uptimeMillis() - lastCheck
        handler.postDelayed(checkRunnable, maxOf(minDelay, CHECK_INTERVAL_MS - sinceLast, 0L))
    }

    private fun check() {
        lastCheck = SystemClock.uptimeMillis()

        val root = rootInActiveWindow
        val target = targets[root?.packageName?.toString()]
        // Another app in front, or blocking turned off: uncover right away.
        if (!BlockState.isBlocking(this) || (root != null && target == null)) {
            hideCover()
            return
        }
        if (root != null && DEBUG_DUMP_IDS) dumpIds(root, 0)
        if (root != null && DebugCapture.ENABLED && lastCheck - lastDebugCapture > DEBUG_CAPTURE_EVERY_MS) {
            lastDebugCapture = lastCheck
            DebugCapture.captureScreen(this, root)
        }

        val player = if (root != null && target != null) findVisible(root, target.playerIds) else null
        if (player == null) {
            if (root != null && target != null) noteDmChat(root, target)
            missCount++
            // Count the player as gone only once the cover would come off too.
            if (missCount >= MISSES_BEFORE_HIDE) {
                playerShowing = false
                pass = false
            }
            val loadingAfterTap = lastCheck < preemptUntil
            if (overlay != null && (loadingAfterTap || missCount < MISSES_BEFORE_HIDE)) {
                scheduleCheck(POLL_MS)
            } else {
                hideCover()
            }
            return
        }

        missCount = 0
        if (!playerShowing) {
            // A Reel just opened. Was it tapped in an allowed friend's chat?
            playerShowing = true
            val chat = dmChat
            pass = chat != null && lastCheck - dmSeenAt < FROM_DM_WINDOW_MS &&
                FriendsStore.isAllowed(this, chat)
            passStartedAt = lastCheck
        }
        if (pass) {
            preemptUntil = 0L
            hideCover()
            scheduleCheck(POLL_MS)
            return
        }

        preemptUntil = 0L
        // Cover the whole screen above the tab bar rather than the player's own
        // bounds, which shift while it animates and made the cover jump.
        val bounds = Rect().also { root!!.getBoundsInScreen(it) }
        clipAboveTabBar(root!!, target!!, bounds)
        showCover("Shh… ${target.name} are snoozing", bounds)
        scheduleCheck(POLL_MS)
    }

    // Remember which DM chat is open, so a Reel tapped in it can be allowed.
    private fun noteDmChat(root: AccessibilityNodeInfo, target: Target) {
        if (target.dmChatIds.isEmpty() || findVisible(root, target.dmChatIds) == null) return
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

    // A black panel over just the player. It swallows touches on the video
    // but everything outside it (tab bar, system Back/Home) keeps working.
    // Accessibility overlays need no extra permission.
    private fun showCover(message: String, bounds: Rect) {
        val wm = getSystemService(WindowManager::class.java)
        val existing = overlay
        if (existing != null) {
            if (bounds != overlayBounds) {
                overlayBounds = bounds
                wm.updateViewLayout(existing, coverParams(bounds))
            }
            existing.findViewById<TextView>(R.id.blockedText).text = message
            return
        }

        val view = LayoutInflater.from(this).inflate(R.layout.blocked_overlay, null)
        view.findViewById<TextView>(R.id.blockedText).text = message
        wm.addView(view, coverParams(bounds))
        overlay = view
        overlayBounds = bounds
        muteMusic(true)
    }

    private fun coverParams(bounds: Rect) = WindowManager.LayoutParams(
        bounds.width(),
        bounds.height(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.OPAQUE
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = bounds.left
        y = bounds.top
        // Position in raw screen coordinates, not shifted by the system bars.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun hideCover() {
        missCount = 0
        overlay?.let { getSystemService(WindowManager::class.java).removeView(it) }
        overlay = null
        muteMusic(false)
    }

    // The Reel keeps playing under the cover, so silence it while covered.
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
        hideCover()
        super.onDestroy()
    }
}
