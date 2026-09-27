package com.example.reelblocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val ids = blockedIds[pkg] ?: return
        if (!BlockState.isBlocking(this)) return

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

        // If "back" keeps landing on Reels/Shorts (e.g. opened from a link),
        // escalate to going home.
        if (recentBlocks >= ESCALATE_AFTER) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            recentBlocks = 0
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }

        // A normal screen (not an overlay), so the system nav bar can leave it.
        val what = if (pkg.contains("instagram")) "Reels" else "Shorts"
        startActivity(
            Intent(this, BlockedActivity::class.java)
                .putExtra(BlockedActivity.EXTRA_MESSAGE, "$what blocked")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun dumpIds(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > 40) return
        node.viewIdResourceName?.let { Log.d(TAG, "${"  ".repeat(depth)}$it") }
        for (i in 0 until node.childCount) dumpIds(node.getChild(i), depth + 1)
    }

    override fun onInterrupt() {}
}
