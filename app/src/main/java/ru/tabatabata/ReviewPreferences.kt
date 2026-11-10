package ru.tabatabata

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ReviewPreferences(context: Context) : ReviewLedger {
    private val prefs = context.getSharedPreferences("review-policy", Context.MODE_PRIVATE)
    private val ads = context.getSharedPreferences("ad-policy", Context.MODE_PRIVATE)
    companion object { private val lock = Any() }
    private fun state() = ReviewState(prefs.getInt("attempts", 0), prefs.getLong("last-attempt", 0),
        prefs.getBoolean("already-reviewed", false))
    override suspend fun read() = withContext(Dispatchers.IO) { synchronized(lock) { state() } }
    override suspend fun claim(now: Long) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val current = state()
            if (!ReviewPolicy.canClaim(current, now) ||
                lastExternalAt().let { it != 0L && (now < it || now - it < ReviewPolicy.QUIET) }) false
            else prefs.edit().putInt("attempts", current.attempts + 1).putLong("last-attempt", now).commit()
        }
    }
    override suspend fun reviewed() { withContext(Dispatchers.IO) {
        synchronized(lock) { check(prefs.edit().putBoolean("already-reviewed", true).commit()) }
    } }
    override fun lastExternalAt(): Long = maxOf(prefs.getLong("external-at", 0), ads.getLong("last-shown", 0))
    fun externalWindow() { synchronized(lock) {
        prefs.edit().putLong("external-at", System.currentTimeMillis()).apply()
    } }
}
