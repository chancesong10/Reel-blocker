package com.example.reelblocker

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.SystemClock
import android.util.Log
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

        private const val CHECK_INTERVAL_MS = 250L
        private const val BLOCK_COOLDOWN_MS = 400L
        private const val ESCALATE_WINDOW_MS = 3000L
        private const val ESCALATE_AFTER = 3
    }

    // View IDs that only exist on the Reels / Shorts full-screen players.
    // These change when the apps update. Use DEBUG_DUMP_IDS to find new ones.
    private val blockedIds = mapOf(
        "com.instagram.android" to listOf(
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/clips_video_container",
            "com.instagram.android:id/clips_root_layout",
        ),
        "com.google.android.youtube" to listOf(
            "com.google.android.youtube:id/reel_player_page_container",
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_watch_player",
        ),
    )

    private var lastCheck = 0L
    private var lastBlock = 0L
    private var recentBlocks = 0
    private var overlay: View? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val ids = blockedIds[pkg] ?: return

        // Content-changed events fire constantly; throttle the tree scan.
        val now = SystemClock.uptimeMillis()
        if (now - lastCheck < CHECK_INTERVAL_MS) return
        lastCheck = now

        val root = rootInActiveWindow ?: return
        if (DEBUG_DUMP_IDS) dumpIds(root, 0)

        if (ids.any { isVisible(root, it) }) block(now, pkg)
    }

    private fun isVisible(root: AccessibilityNodeInfo, id: String): Boolean =
        root.findAccessibilityNodeInfosByViewId(id).any { it.isVisibleToUser }

    private fun block(now: Long, pkg: String) {
        if (now - lastBlock < BLOCK_COOLDOWN_MS) return

        recentBlocks = if (now - lastBlock < ESCALATE_WINDOW_MS) recentBlocks + 1 else 1
        lastBlock = now

        // Cover the screen first so the Reel/Short is never seen closing.
        val what = if (pkg.contains("instagram")) "Reels" else "Shorts"
        showBlockedScreen("$what blocked")

        // If "back" keeps landing on Reels/Shorts (e.g. opened from a link),
        // escalate to going home.
        if (recentBlocks >= ESCALATE_AFTER) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            recentBlocks = 0
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    // Accessibility overlays need no extra permission. The overlay is not
    // focusable, so the back/home actions still reach the app underneath.
    private fun showBlockedScreen(message: String) {
        if (overlay != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.blocked_overlay, null)
        view.findViewById<TextView>(R.id.blockedText).text = message
        view.setOnClickListener { hideBlockedScreen() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        )
        getSystemService(WindowManager::class.java).addView(view, params)
        overlay = view
    }

    private fun hideBlockedScreen() {
        overlay?.let { getSystemService(WindowManager::class.java).removeView(it) }
        overlay = null
    }

    override fun onDestroy() {
        hideBlockedScreen()
        super.onDestroy()
    }

    private fun dumpIds(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > 40) return
        node.viewIdResourceName?.let { Log.d(TAG, "${"  ".repeat(depth)}$it") }
        for (i in 0 until node.childCount) dumpIds(node.getChild(i), depth + 1)
    }

    override fun onInterrupt() {}
}
