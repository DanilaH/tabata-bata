package ru.tabatabata

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class HistoryRepositoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun record(id: String, at: Long, ms: Long = 1000, completed: Boolean = true) =
        WorkoutRecord(id,Plan(),"Режим",finishedAt=at,completed=completed,activeMs=ms,completedRounds=1)

    @Test fun terminalOutboxSurvivesNewRepositoryAndReplayIsIdempotent() = runBlocking {
        val repository = HistoryRepository(context)
        repository.clear()
        try {
            val prefs = context.getSharedPreferences("session",0)
            prefs.edit().putString("active","old checkpoint").commit()
            val entry = record("deduplicate",System.currentTimeMillis())
            repository.enqueue(entry)
            assertFalse(prefs.contains("active"))
            assertTrue(prefs.contains("history:deduplicate"))
            assertEquals(listOf(entry),HistoryRepository(context).load().records)
            assertFalse(prefs.contains("history:deduplicate"))
            // Emulate a kill after SQLite commit but before acknowledging the outbox.
            prefs.edit().putString("history:deduplicate",entry.json().toString()).commit()
            assertEquals(1,HistoryRepository(context).load().total)
            repository.enqueue(entry.copy(sessionId="repeat"))
            assertEquals(2,repository.load().total)
        } finally { repository.clear() }
    }

    @Test fun weeklyTotalIncludesRecordsOutsideVisiblePageAndClearRemovesPending() = runBlocking {
        val repository = HistoryRepository(context)
        repository.clear()
        try {
            val now = System.currentTimeMillis()
            val week = currentWeek(now,ZoneId.systemDefault())
            repository.enqueue(record("this-week",now,12345,false))
            repeat(55) { repository.enqueue(record("future-$it",week.end+it+1)) }
            val page = repository.load(50,now)
            assertEquals(56,page.total)
            assertEquals(50,page.records.size)
            assertFalse(page.records.any { it.sessionId=="this-week" })
            assertEquals(WeekSummary(0,1,12345,0),page.week)
            repository.enqueue(record("pending",now))
            repository.clear()
            assertEquals(0,HistoryRepository(context).load().total)
            assertFalse(context.getSharedPreferences("session",0).all.keys.any { it.startsWith("history:") })
        } finally { repository.clear() }
    }

    @Test fun oldUnknownStatsAreExplicitAndMetadataRoundTrips() = runBlocking {
        val repository = HistoryRepository(context)
        repository.clear()
        try {
            val entry = record("legacy",System.currentTimeMillis()).copy(name="Силовая",accent="orange",mark=3,
                statsAvailable=false,startedAt=null)
            repository.enqueue(entry)
            val loaded = repository.load()
            assertEquals(entry,loaded.records.single())
            assertEquals(0L,loaded.week.activeMs)
            assertEquals(1,loaded.week.incompleteStats)
        } finally { repository.clear() }
    }
}
