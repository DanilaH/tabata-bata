package ru.tabatabata

/** A stopped or interrupted workout must never trigger an interstitial. */
object AdPolicy {
    fun mayShow(result: Status, completed: Int, lastShown: Long, now: Long): Boolean =
        result == Status.COMPLETED && completed > 1 && now - lastShown >= 86_400_000L
}
