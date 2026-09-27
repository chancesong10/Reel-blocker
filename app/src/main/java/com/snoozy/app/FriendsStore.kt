package com.snoozy.app

import android.content.Context

// DM chats that Reels have been opened from, and whether each is allowed.
// Only the chat's name is stored, on this phone only.
object FriendsStore {

    private const val PREFS = "friends"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // New people start allowed: sending you a Reel is the extra step.
    fun isAllowed(context: Context, name: String): Boolean =
        prefs(context).getBoolean(name, true)

    fun remember(context: Context, name: String) {
        val p = prefs(context)
        if (!p.contains(name)) p.edit().putBoolean(name, true).apply()
    }

    fun set(context: Context, name: String, allowed: Boolean) {
        prefs(context).edit().putBoolean(name, allowed).apply()
    }

    fun setAll(context: Context, allowed: Boolean) {
        val p = prefs(context)
        p.edit().apply { p.all.keys.forEach { putBoolean(it, allowed) } }.apply()
    }

    fun all(context: Context): List<Pair<String, Boolean>> =
        prefs(context).all
            .mapNotNull { (name, allowed) -> (allowed as? Boolean)?.let { name to it } }
            .sortedBy { it.first.lowercase() }
}
