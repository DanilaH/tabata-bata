package ru.tabatabata

import org.junit.Assert.*
import org.junit.Test

class ReviewPolicyTest {
    private val experience = ReviewExperience(3,2,600_000,true)
    @Test fun enoughExperienceRequiresSavedCompletedWorkOnDifferentDays() {
        assertTrue(ReviewPolicy.eligible(experience,ReviewState(),1000,0))
        for (e in listOf(experience.copy(sessions=2),experience.copy(days=1),
            experience.copy(activeMs=599999),experience.copy(currentEligible=false)))
            assertFalse(ReviewPolicy.eligible(e,ReviewState(),1000,0))
    }
    @Test fun attemptsAreCappedAndClockRollbackDoesNotOpenAnotherWindow() {
        val first = ReviewState(1,1000)
        assertFalse(ReviewPolicy.canClaim(first,999))
        assertFalse(ReviewPolicy.canClaim(first,1000+ReviewPolicy.COOLDOWN-1))
        assertTrue(ReviewPolicy.canClaim(first,1000+ReviewPolicy.COOLDOWN))
        assertFalse(ReviewPolicy.canClaim(ReviewState(2,1000),Long.MAX_VALUE))
        assertFalse(ReviewPolicy.canClaim(ReviewState(alreadyReviewed=true),1000))
        assertFalse(ReviewPolicy.canClaim(ReviewState(-1,0),1000))
        assertFalse(ReviewPolicy.canClaim(ReviewState(1,0),1000))
    }
    @Test fun anotherExternalWindowGetsAQuietInterval() {
        assertFalse(ReviewPolicy.eligible(experience,ReviewState(),60_999,1000))
        assertTrue(ReviewPolicy.eligible(experience,ReviewState(),61_000,1000))
        assertFalse(ReviewPolicy.eligible(experience,ReviewState(),999,1000))
    }
}
