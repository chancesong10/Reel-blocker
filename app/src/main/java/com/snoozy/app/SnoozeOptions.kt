package com.snoozy.app

import android.content.Context

// The quick lock lengths shown as chips on the home screen, in minutes.
// "None" (no lock) is always offered in front of these and isn't stored.
object SnoozeOptions {

    const val MAX_OPTIONS = 7
    const val MAX_MINUTES = 24 * 60
    const val MINUTE_STEP = 5

    private const val PREFS = "snooze_options"
    private const val KEY = "minutes"
    private val DEFAULTS = listOf(15, 30, 60, 120, 240, 480, MAX_MINUTES)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context): List<Int> {
        val saved = prefs(context).getString(KEY, null) ?: return DEFAULTS
        return saved.split(',').mapNotNull { it.toIntOrNull() }
    }

    private fun save(context: Context, minutes: List<Int>) {
        prefs(context).edit().putString(KEY, minutes.sorted().joinToString(",")).apply()
    }

    // Returns why the option can't be added, or null once it's added.
    fun add(context: Context, minutes: Int): String? {
        val current = get(context)
        return when {
            minutes <= 0 -> "Pick a time longer than 0 minutes."
            minutes > MAX_MINUTES -> "The longest snooze is 24 hours."
            minutes in current -> "${label(minutes)} is already an option."
            current.size >= MAX_OPTIONS -> "You can have up to $MAX_OPTIONS options. Remove one first."
            else -> {
                save(context, current + minutes)
                null
            }
        }
    }

    fun remove(context: Context, minutes: Int) = save(context, get(context) - minutes)

    fun reset(context: Context) = prefs(context).edit().remove(KEY).apply()

    // 90 -> "1h 30m", 45 -> "45m", 120 -> "2h"
    fun label(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }
}
