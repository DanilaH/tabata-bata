package ru.tabatabata

import org.junit.Assert.*
import org.junit.Test

class AdLoadRetryTest {
    @Test fun offlineFailuresBackOffThenSuccessResetsWithoutDuplicateRequests() {
        var pending: (() -> Unit)? = null
        val delays = mutableListOf<Long>()
        var requests = 0
        lateinit var retry: AdLoadRetry
        retry = AdLoadRetry({ delay, action -> delays.add(delay); pending = action },
            { pending = null }, { if (retry.begin()) requests++ })
        assertTrue(retry.begin())
        repeat(8) {
            assertFalse(retry.begin())
            retry.failed()
            retry.failed() // duplicate failure must not schedule another request
            assertFalse(retry.begin())
            val action = pending!!
            pending = null
            action()
        }
        assertEquals(listOf(5000L, 10000L, 20000L, 40000L, 80000L, 160000L, 300000L, 300000L), delays)
        assertEquals(8, requests)
        retry.loaded()
        assertTrue(retry.begin()) // next prefetch after consuming the ad
        retry.failed()
        assertEquals(5000L, delays.last())
    }

    @Test fun closingCancelsRetryAndIgnoresLateCallback() {
        var pending: (() -> Unit)? = null
        var requests = 0
        val retry = AdLoadRetry({ _, action -> pending = action }, { pending = null }, { requests++ })
        assertTrue(retry.begin())
        retry.failed()
        val stale = pending!!
        retry.close()
        assertNull(pending)
        stale()
        retry.loaded()
        retry.failed()
        assertFalse(retry.begin())
        assertEquals(0, requests)
    }
}
