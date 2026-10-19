package ru.tabatabata

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class AuditEngineProbe {
    @Test fun randomizedTimelineAndActions() {
        val r = Random(20261019)
        repeat(1000) {
            val p = Plan(r.nextInt(6), 1+r.nextInt(30), r.nextInt(15), 1+r.nextInt(20), 1+r.nextInt(4), r.nextInt(20), r.nextInt(8), r.nextBoolean())
            assertEquals(p.durationMs(), p.stages().sumOf { it.duration })
            val e = TimerEngine(p, 0)
            var now = 0L
            var lastActive = 0L
            repeat(150) {
                now += r.nextInt(3000)
                when(r.nextInt(6)) {
                    0 -> e.pause(now)
                    1 -> e.resume(now)
                    2 -> e.skip(1, now)
                    3 -> e.skip(-1, now)
                    4 -> e.extendRest(now)
                }
                val s = e.snapshot(now)
                assertTrue(s.remaining >= 0)
                assertTrue(s.totalRemaining >= s.remaining)
                assertTrue(s.activeMs >= lastActive)
                assertTrue(s.completedWork.size <= p.reps*p.sets)
                if (s.status == Status.PAUSED) assertEquals(s.remaining,e.snapshot(now+100).remaining)
                lastActive = s.activeMs
            }
            val uninterrupted = TimerEngine(p,0)
            assertEquals(Status.COMPLETED, uninterrupted.snapshot(p.durationMs()).status)
            assertEquals(p.reps*p.sets, uninterrupted.snapshot(p.durationMs()).completedWork.size)
        }
        println("AUDIT random_plans=1000 random_actions=150000 invariants=PASS")
    }
}
