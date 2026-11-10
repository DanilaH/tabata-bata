package ru.tabatabata

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.Duration

class WorkoutHistoryTest {
    private fun epoch(s: String) = Instant.parse(s).toEpochMilli()
    private fun record(id: String, at: Long, completed: Boolean = true, ms: Long = 1000,
        known: Boolean = true) = WorkoutRecord(id, Plan(), "Табата", finishedAt = at,
        completed = completed, activeMs = ms, completedRounds = 1, statsAvailable = known)

    @Test fun weekIsLocalMondayAndEndIsExclusive() {
        val zone = ZoneId.of("Asia/Yekaterinburg")
        val window = currentWeek(epoch("2026-11-08T19:00:00Z"), zone)
        assertEquals(epoch("2026-11-08T19:00:00Z"), window.start)
        assertEquals(epoch("2026-11-15T19:00:00Z"), window.end)
        val summary = summarizeWeek(listOf(record("before",window.start-1),
            record("first",window.start), record("last",window.end-1,false,3000),
            record("next",window.end)), window)
        assertEquals(WeekSummary(1,1,4000,0), summary)
    }

    @Test fun daylightSavingWeeksDoNotAssume168Hours() {
        val zone = ZoneId.of("Europe/Berlin")
        val spring = currentWeek(epoch("2026-03-29T12:00:00Z"),zone)
        val autumn = currentWeek(epoch("2026-10-25T12:00:00Z"),zone)
        assertEquals(167L,Duration.ofMillis(spring.end-spring.start).toHours())
        assertEquals(169L,Duration.ofMillis(autumn.end-autumn.start).toHours())
    }

    @Test fun unknownOldStatisticsAreNotMadeIntoWeeklyMinutes() {
        val records = listOf(record("done",5000,ms=1234),record("stop",6000,false,2345),
            record("legacy",7000,ms=999999,known=false))
        assertEquals(WeekSummary(2,1,3579,1),summarizeWeek(records,WeekWindow(1,10000)))
    }

    @Test fun repeatUsesImmutableOriginalPlanAndAppearance() {
        val plan = Plan(work=45,rest=15,reps=10,sets=3)
        val record = WorkoutRecord("old",plan,"Силовая","violet",1,finishedAt=1000,
            completed=false,activeMs=12345,completedRounds=0)
        record.validate()
        val repeat = record.repeatPreset()
        assertEquals(plan,repeat.plan)
        assertEquals("Силовая",repeat.name)
        assertEquals("violet",repeat.accent)
        assertEquals(1,repeat.mark)
    }

    @Test fun stoppedTimeComesFromEngineAndExcludesPauseAndSkippedRounds() {
        val engine = TimerEngine(Plan(0,10,5,2),0)
        engine.pause(3000)
        engine.skip(1,90000)
        engine.resume(100000)
        engine.stop(101000)
        val snapshot = engine.snapshot(999999)
        val record = WorkoutRecord(snapshot.sessionId,snapshot.plan,"",finishedAt=1000,
            completed=false,activeMs=snapshot.activeMs,completedRounds=snapshot.completedWork.size)
        record.validate()
        assertEquals(4000L,record.activeMs)
        assertEquals(0,record.completedRounds)
    }
}
