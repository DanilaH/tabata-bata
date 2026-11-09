package ru.tabatabata

/** A stopped or interrupted workout must never trigger an interstitial. */
object AdPolicy {
    fun mayPrefetch(completed: Int, lastShown: Long, now: Long): Boolean =
        completed >= 1 && (lastShown == 0L || (now >= lastShown && now - lastShown >= 86_400_000L))

    fun mayShow(result: Status, completed: Int, lastShown: Long, now: Long): Boolean =
        result == Status.COMPLETED && completed > 1 && mayPrefetch(completed, lastShown, now)
}
