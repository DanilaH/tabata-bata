package ru.tabatabata

import org.junit.Assert.*
import org.junit.Test

class AdLoadRetryTest {
    private class Harness {
        var clock = 0L
        var pending: (() -> Unit)? = null
        val delays = mutableListOf<Long>()
        val tokens = mutableListOf<Int>()
        val retry = AdLoadRetry(
            { delay, action -> delays.add(delay); pending = action },
            { pending = null },
            { retryRequest() },
            { clock },
        )
        private fun retryRequest() { retry.begin()?.let { tokens.add(it) } }
        fun fire() {
            val action = requireNotNull(pending)
            pending = null
            clock += delays.last()
            action()
        }
    }

    @Test fun threeFailuresStopUntilLaterEligibleActionAfterCooldown() {
        val h = Harness()
        h.retry.setEnabled(true)
        repeat(3) { i ->
            assertTrue(h.retry.failed(h.tokens.last()))
            if (i < 2) h.fire()
        }
        assertEquals(3, h.tokens.size)
        assertEquals(listOf(15_000L, 60_000L), h.delays)
        assertNull(h.pending)
        h.retry.opportunity()
        assertEquals(3, h.tokens.size)
        h.clock += 300_000
        assertNull(h.pending) // Time alone never restarts exhausted requests.
        h.retry.opportunity()
        assertEquals(4, h.tokens.size)
        assertEquals(1, h.retry.attempt)
    }

    @Test fun backgroundCancelsRetryAndStaleScheduledActionCannotRestartIt() {
        val h = Harness()
        h.retry.setEnabled(true)
        h.retry.failed(h.tokens.last())
        val stale = h.pending!!
        h.retry.setEnabled(false)
        assertNull(h.pending)
        h.clock += 15_000
        stale()
        assertEquals(1, h.tokens.size)
        h.retry.setEnabled(true)
        assertEquals(2, h.tokens.size)
        stale()
        assertEquals(2, h.tokens.size)
    }

    @Test fun hiddenRequestMayFinishButCannotCreateAnotherRequest() {
        val h = Harness()
        h.retry.setEnabled(true)
        val token = h.tokens.single()
        h.retry.setEnabled(false)
        h.retry.setEnabled(true)
        assertEquals(1, h.tokens.size) // No overlapping unresolved SDK calls.
        h.retry.setEnabled(false)
        assertTrue(h.retry.loaded(token))
        assertFalse(h.retry.failed(token))
        assertFalse(h.retry.loaded(token))
        assertNull(h.pending)
    }

    @Test fun lateFailureWhileHiddenWaitsForEligibilityAndPreservesDelay() {
        val h = Harness()
        h.retry.setEnabled(true)
        h.retry.setEnabled(false)
        assertTrue(h.retry.failed(h.tokens.single()))
        assertNull(h.pending)
        h.retry.setEnabled(true)
        assertEquals(listOf(15_000L), h.delays)
        assertNull(h.retry.begin())
        h.fire()
        assertEquals(2, h.tokens.size)
    }

    @Test fun successfulLoadResetsBurstAndDuplicateFailureCannotAffectNextRequest() {
        val h = Harness()
        h.retry.setEnabled(true)
        val old = h.tokens.single()
        h.retry.failed(old)
        h.fire()
        assertFalse(h.retry.failed(old))
        assertTrue(h.retry.loaded(h.tokens.last()))
        h.retry.opportunity() // The consumer calls this only after consuming the cached ad.
        assertEquals(1, h.retry.attempt)
        assertFalse(h.retry.loaded(old))
    }

    @Test fun closeRejectsLateSdkAndScheduledCallbacks() {
        val h = Harness()
        h.retry.setEnabled(true)
        val token = h.tokens.single()
        h.retry.failed(token)
        val stale = h.pending!!
        h.retry.close()
        h.retry.setEnabled(true)
        stale()
        assertFalse(h.retry.loaded(token))
        assertFalse(h.retry.failed(token))
        assertNull(h.retry.begin())
        assertEquals(1, h.tokens.size)
    }
}
