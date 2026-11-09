package ru.tabatabata

import org.junit.Assert.*
import org.junit.Test

class AdPolicyTest {
    @Test fun prefetchOnlyAfterFirstCompletionAndOutsideDailyCap() {
        assertFalse(AdPolicy.mayPrefetch(0, 0, 172800000))
        assertTrue(AdPolicy.mayPrefetch(1, 0, 172800000))
        assertFalse(AdPolicy.mayPrefetch(10, 172800000, 172800001))
        assertFalse(AdPolicy.mayPrefetch(10, 172800000, 86400000))
        assertTrue(AdPolicy.mayPrefetch(10, 86400000, 172800000))
    }

    @Test
    fun stoppedAndInterruptedNeverTriggerAds() {
        for (status in listOf(Status.STOPPED, Status.INTERRUPTED, Status.RUNNING, Status.PAUSED)) {
            assertFalse(AdPolicy.mayShow(status, 100, 0, 172800000))
        }
    }

    @Test
    fun firstCompletionAndDailyCooldownAreProtected() {
        assertFalse(AdPolicy.mayShow(Status.COMPLETED, 1, 0, 172800000))
        assertFalse(AdPolicy.mayShow(Status.COMPLETED, 2, 172800000, 172800001))
        assertTrue(AdPolicy.mayShow(Status.COMPLETED, 2, 0, 172800000))
        assertTrue(AdPolicy.mayShow(Status.COMPLETED, 2, 86400000, 172800000))
    }

    @Test
    fun clockMovedBackwardsDoesNotIncreaseFrequency() {
        assertFalse(AdPolicy.mayShow(Status.COMPLETED, 2, 172800000, 86400000))
    }
}
