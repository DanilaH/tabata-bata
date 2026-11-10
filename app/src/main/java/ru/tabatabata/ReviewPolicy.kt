package ru.tabatabata

internal data class ReviewExperience(val sessions: Int = 0, val days: Int = 0,
    val activeMs: Long = 0, val currentEligible: Boolean = false)
internal data class ReviewState(val attempts: Int = 0, val lastAttemptAt: Long = 0,
    val alreadyReviewed: Boolean = false)

internal object ReviewPolicy {
    const val COOLDOWN = 30 * 86_400_000L
    const val QUIET = 60_000L
    const val PREPARE_TIMEOUT = 1500L
    fun eligible(experience: ReviewExperience, state: ReviewState, now: Long, externalAt: Long): Boolean =
        experience.currentEligible && experience.sessions >= 3 && experience.days >= 2 &&
            experience.activeMs >= 600_000 && canClaim(state, now) &&
            (externalAt == 0L || now >= externalAt && now - externalAt >= QUIET)

    fun canClaim(state: ReviewState, now: Long): Boolean = now > 0 &&
        state.attempts in 0..1 && !state.alreadyReviewed && state.lastAttemptAt >= 0 &&
        (state.attempts == 0 || state.lastAttemptAt > 0 && now >= state.lastAttemptAt &&
            now - state.lastAttemptAt >= COOLDOWN)
}
