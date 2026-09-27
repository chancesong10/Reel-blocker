package com.example.reelblocker

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

        private const val CHECK_INTERVAL_MS = 150L
        // While covering a Reel, keep re-checking so the cover disappears as
        // soon as you navigate away (even to an app we get no events from).
        private const val POLL_MS = 300L
    }

    private class Target(
        val name: String,
        // View IDs that only exist on the Reels / Shorts players.
        val playerIds: List<String>,
        // The app's own bottom tab bar, left uncovered so you can navigate away.
        val tabBarIds: List<String>,
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

    private var overlay: View? = null
    private var overlayBounds = Rect()
    private var mutedMusic = false

    private val checkRunnable = Runnable {
        checkPending = false
        check()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg in targets) scheduleCheck(0L)
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
        if (root == null || target == null || !BlockState.isBlocking(this)) {
            hideCover()
            return
        }
        if (DEBUG_DUMP_IDS) dumpIds(root, 0)

        val player = findVisible(root, target.playerIds)
        if (player == null) {
            hideCover()
            return
        }

        val bounds = Rect().also { player.getBoundsInScreen(it) }
        findVisible(root, target.tabBarIds)?.let { tabBar ->
            val tabBounds = Rect().also { tabBar.getBoundsInScreen(it) }
            if (tabBounds.top > bounds.top) bounds.bottom = minOf(bounds.bottom, tabBounds.top)
        }
        showCover("${target.name} blocked", bounds)
        scheduleCheck(POLL_MS)
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
