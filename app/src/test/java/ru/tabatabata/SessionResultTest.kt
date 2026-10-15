package ru.tabatabata

import org.junit.Assert.*
import org.junit.Test

class SessionResultTest {
    @Test
    fun pauseAndSkippedWorkAreExcluded() {
        val e = TimerEngine(Plan(0, 10, 5, 2), 0)
        e.pause(3000)
        assertEquals(3000L, e.snapshot(90000).activeMs)
        e.skip(1, 90000)
        e.resume(100000)
        val s = e.snapshot(120000)
        assertEquals(Status.COMPLETED, s.status)
        assertEquals(18000L, s.activeMs)
        assertEquals(1, s.completedWork.size)
    }

    @Test
    fun replayDoesNotDoubleCountAndPartialStopHasExactTime() {
        val e = TimerEngine(Plan(0, 10, 5, 2), 0)
        e.snapshot(10000)
        e.skip(-1, 10000)
        e.snapshot(20000)
        assertEquals(1, e.snapshot(20000).completedWork.size)
        e.stop(22500)
        assertEquals(22500L, e.snapshot(90000).activeMs)
    }

    @Test
    fun restorePreservesStatisticsWithoutCountingDowntime() {
        val e = TimerEngine(Plan(0, 10, 5, 2), 0)
        val before = e.snapshot(12000)
        val restored = TimerEngine(before.plan, 80000)
        restored.restore(before.index, before.remaining, before.activeMs, before.completedWork)
        assertEquals(12000L, restored.snapshot(90000).activeMs)
        restored.resume(100000)
        val done = restored.snapshot(200000)
        assertEquals(25000L, done.activeMs)
        assertEquals(2, done.completedWork.size)
    }

    @Test
    fun timeInputBoundaries() {
        assertEquals(120, parseDuration("2:00"))
        assertEquals(120, parseDuration("120"))
        assertEquals(3600, parseDuration("60:00"))
        assertNull(parseDuration("1:60"))
        assertNull(parseDuration("1:"))
        assertNull(parseDuration(""))
        assertNull(parseDuration("-5"))
    }
}
