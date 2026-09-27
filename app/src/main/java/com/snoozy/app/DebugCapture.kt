package com.snoozy.app

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

// TEMPORARY: records the layout IDs of recent Instagram screens so they can
// be copied from the app once, to find the DM screen IDs. Only ID names,
// view types and positions are kept: never any text, names or messages.
// Remove this file (and its button) once the DM IDs are filled in.
object DebugCapture {

    const val ENABLED = true

    private const val PREFS = "debug_capture"
    private const val KEY = "screens"
    private const val MAX_SCREENS = 10

    private val screens = ArrayDeque<String>()
    private var lastSignature = 0

    fun captureScreen(context: Context, root: AccessibilityNodeInfo) {
        val lines = mutableListOf<String>()
        walk(root, 0, lines)
        val signature = lines.map { it.substringBefore(" y=") }.hashCode()
        if (signature == lastSignature) return
        lastSignature = signature
        add(context, "=== screen\n" + lines.joinToString("\n"))
    }

    fun captureScroll(context: Context, id: String?, className: CharSequence?) {
        add(context, "=== scroll  id=$id  class=${className?.toString()?.substringAfterLast('.')}")
    }

    private fun add(context: Context, entry: String) {
        screens.addLast(entry)
        while (screens.size > MAX_SCREENS) screens.removeFirst()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, screens.joinToString("\n\n")).apply()
    }

    fun read(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    private fun walk(node: AccessibilityNodeInfo?, depth: Int, out: MutableList<String>) {
        if (node == null || depth > 40) return
        val id = node.viewIdResourceName
        if (id != null && node.isVisibleToUser) {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val hasText = if (!node.text.isNullOrBlank()) " [has text]" else ""
            val type = node.className?.toString()?.substringAfterLast('.')
            out += "${"  ".repeat(depth)}${id.substringAfter(":id/")} ($type)$hasText y=${bounds.top}"
        }
        for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1, out)
    }
}
