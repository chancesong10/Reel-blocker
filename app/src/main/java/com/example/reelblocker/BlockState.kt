package com.example.reelblocker

import android.content.Context

// Whether blocking is on, and the earliest time it may be turned off.
// Shared by MainActivity (writes) and ReelBlockerService (reads).
object BlockState {

    private const val PREFS = "block_state"
    private const val KEY_BLOCKING = "blocking"
    private const val KEY_LOCK_UNTIL = "lock_until"
    private const val KEY_LOCK_TOTAL = "lock_total"
    private const val KEY_LAST_CHOICE = "last_choice"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isBlocking(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BLOCKING, true)

    fun lockRemainingMs(context: Context): Long {
        val until = prefs(context).getLong(KEY_LOCK_UNTIL, 0L)
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    // Length of the current lock, for drawing how much of it is left.
    fun lockTotalMs(context: Context): Long =
        prefs(context).getLong(KEY_LOCK_TOTAL, 0L)

    // The lock length picked last time, pre-selected next time.
    fun lastChoiceMs(context: Context, default: Long): Long =
        prefs(context).getLong(KEY_LAST_CHOICE, default)

    fun block(context: Context, lockMs: Long) {
        prefs(context).edit()
            .putBoolean(KEY_BLOCKING, true)
            .putLong(KEY_LOCK_UNTIL, System.currentTimeMillis() + lockMs)
            .putLong(KEY_LOCK_TOTAL, lockMs)
            .putLong(KEY_LAST_CHOICE, lockMs)
            .apply()
    }

    // Returns false (and changes nothing) while the lock is still running.
    fun unblock(context: Context): Boolean {
        if (lockRemainingMs(context) > 0) return false
        prefs(context).edit()
            .putBoolean(KEY_BLOCKING, false)
            .putLong(KEY_LOCK_UNTIL, 0L)
            .apply()
        return true
    }
}
