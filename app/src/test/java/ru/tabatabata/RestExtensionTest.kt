package ru.tabatabata

import org.junit.Assert.*
import org.junit.Test

class RestExtensionTest {
    @Test
    fun runningRestExtendsDeadlineAndProgressWithoutChangingPlan() {
        val plan = Plan(0, 10, 10, 2)
        val e = TimerEngine(plan, 0)
        assertFalse(e.extendRest(5000))
        assertTrue(e.extendRest(15000))
        val s = e.snapshot(15000)
        assertEquals(15000L, s.remaining)
        assertEquals(20000L, s.interval!!.duration)
        assertEquals(25000L, s.totalRemaining)
        assertEquals(15000L, s.activeMs)
        assertEquals(10, e.plan.rest)
        assertEquals(Stage.WORK, e.snapshot(30000).interval!!.stage)
    }

    @Test
    fun pausedRestRepeatedExtensionsAndRecovery() {
        val p = Plan(0, 10, 10, 2)
        val e = TimerEngine(p, 0)
        e.pause(12000)
        assertTrue(e.extendRest(20000))
        assertTrue(e.extendRest(30000))
        val s = e.snapshot(40000)
        assertEquals(28000L, s.remaining)
        assertEquals(12000L, s.activeMs)
        val recovered = TimerEngine(p, 50000)
        recovered.restore(
            s.index,
            s.remaining,
            s.activeMs,
            s.completedWork,
            true,
            s.interval!!.duration,
        )
        assertEquals(28000L, recovered.snapshot(50000).remaining)
        assertEquals(30000L, recovered.snapshot(50000).interval!!.duration)
        recovered.resume(60000)
        assertEquals(Stage.WORK, recovered.snapshot(88000).interval!!.stage)
        assertEquals(40000L, recovered.snapshot(88000).activeMs)
    }

    @Test
    fun betweenSetsOnlyAndExpiredRestCannotExtendWork() {
        val e = TimerEngine(Plan(0, 1, 0, 1, 2, 2), 0)
        assertTrue(e.extendRest(1000))
        assertEquals(Stage.BETWEEN, e.snapshot(12000).interval!!.stage)
        assertFalse(e.extendRest(13000))
        e.stop(13000)
        assertFalse(e.extendRest(13000))
    }
}
