package ru.tabatabata

import org.junit.Assert.*
import org.junit.Test

class TimerEngineTest {
    @Test
    fun personalDurations() {
        assertEquals(103000, Plan(5, 2, 2, 25).durationMs())
        assertEquals(130000, Plan(5, 8, 5, 10).durationMs())
    }

    @Test
    fun noDoubleRestBetweenSets() {
        val p = Plan(0, 20, 10, 2, 2, 30)
        assertEquals(130000, p.durationMs())
        assertEquals(
            listOf(
                Stage.WORK,
                Stage.REST,
                Stage.WORK,
                Stage.BETWEEN,
                Stage.WORK,
                Stage.REST,
                Stage.WORK,
            ),
            p.stages().map { it.stage },
        )
    }

    @Test
    fun boundariesAndDelayedWake() {
        val e = TimerEngine(Plan(5, 2, 2, 25), 1000)
        assertEquals(Stage.WORK, e.snapshot(6000).interval?.stage)
        assertEquals(25, e.snapshot(102000).interval?.rep)
        assertEquals(Status.COMPLETED, e.snapshot(104000).status)
    }

    @Test
    fun pauseFreezesTime() {
        val e = TimerEngine(Plan(0, 20, 10, 2), 0)
        e.pause(7500)
        assertEquals(12500, e.snapshot(900000).remaining)
        e.resume(900000)
        assertEquals(12000, e.snapshot(900500).remaining)
    }

    @Test
    fun zeroOptionalStagesAndLastRest() {
        val p = Plan(0, 1, 0, 2, 2, 0, 0, true)
        assertEquals(4000, p.durationMs())
        assertEquals(4, p.stages().size)
        assertEquals(Status.COMPLETED, TimerEngine(p, 0).snapshot(4000).status)
    }

    @Test
    fun skipWhilePausedStaysPaused() {
        val e = TimerEngine(Plan(0, 20, 10, 2), 0)
        e.pause(0)
        e.skip(1, 0)
        assertEquals(Status.PAUSED, e.snapshot(100000).status)
        assertEquals(Stage.REST, e.snapshot(100000).interval?.stage)
        e.skip(-1, 100000)
        assertEquals(20000, e.snapshot(100000).remaining)
    }

    @Test
    fun recoveryRequiresManualResume() {
        val e = TimerEngine(Plan(), 0)
        e.restore(1, 1234)
        assertEquals(Status.INTERRUPTED, e.snapshot(100000).status)
        e.resume(100000)
        assertEquals(1234, e.snapshot(100000).remaining)
    }

    @Test
    fun thirteenCycleAndLongSession() {
        val p = Plan(0, 5, 5, 23)
        val e = TimerEngine(p, 0)
        assertEquals(13, e.snapshot(120000).interval?.rep)
        assertEquals(Stage.WORK, e.snapshot(120000).interval?.stage)
        assertEquals(Status.COMPLETED, e.snapshot(p.durationMs()).status)
    }

    @Test
    fun everyPlanDurationMatchesStages() {
        for (r in listOf(0, 1, 10)) for (s in 1..3) for (last in listOf(false, true)) {
            val p = Plan(5, 8, r, 10, s, 30, 7, last)
            assertEquals(p.durationMs(), p.stages().sumOf { it.duration })
        }
    }
}
