package ru.tabatabata

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HistoryServiceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun recoveredCheckpointProducesOneStoppedRecordWithOriginalMetadata() {
        val repository = HistoryRepository(context)
        runBlocking { repository.clear() }
        val plan = Plan(0,10,5,2)
        val start = System.currentTimeMillis()-100000
        val checkpoint = JSONObject().put("plan",plan.json()).put("sessionId","recover-history")
            .put("index",1).put("remaining",3000).put("currentDuration",5000)
            .put("activeMs",12000).put("completedWork",JSONArray().put(0)).put("statsAvailable",true)
            .put("historyStartedAt",start).put("historyName","Сохранённое имя")
            .put("historyAccent","violet").put("historyMark",3)
        try {
            context.getSharedPreferences("session",0).edit().putString("active",checkpoint.toString()).commit()
            compose.runOnUiThread { TimerService.command(context,"recover") }
            compose.waitUntil(10000) { TimerService.state.value?.status==Status.INTERRUPTED }
            assertEquals(0,runBlocking { repository.load().total })
            assertEquals(12000L,TimerService.state.value!!.activeMs)
            compose.runOnUiThread { TimerService.command(context,"stop") }
            compose.waitUntil(10000) { TimerService.state.value==null }
            val record = runBlocking { repository.load().records.single() }
            assertEquals("recover-history",record.sessionId)
            assertEquals("Сохранённое имя",record.name)
            assertEquals("violet",record.accent)
            assertEquals(3,record.mark)
            assertEquals(start,record.startedAt)
            assertEquals(12000L,record.activeMs)
            assertEquals(1,record.completedRounds)
            assertFalse(record.completed)
            // Repeated stop commands do not create another session.
            compose.runOnUiThread { TimerService.command(context,"stop") }
            compose.waitForIdle()
            assertEquals(1,runBlocking { repository.load().total })
        } finally {
            compose.runOnUiThread { TimerService.command(context,"stop"); TimerService.clearResult() }
            runBlocking { repository.clear() }
        }
    }

    @Test fun naturalCompletionIsRecordedOnceAndRepeatGetsNewSessionId() {
        val repository = HistoryRepository(context)
        runBlocking { repository.clear() }
        val plan = Plan(0,1,0,1)
        try {
            compose.runOnUiThread { TimerService.command(context,"start",plan,Preset(name="Короткая",plan=plan)) }
            compose.waitUntil(10000) { TimerService.state.value?.status==Status.COMPLETED }
            val first = runBlocking { repository.load().records.single() }
            assertTrue(first.completed)
            assertEquals(1000L,first.activeMs)
            assertEquals(1,first.completedRounds)
            compose.runOnUiThread {
                TimerService.clearResult()
                TimerService.command(context,"start",plan,first.repeatPreset())
            }
            compose.waitUntil(10000) { runBlocking { repository.load().total }==2 }
            val records = runBlocking { repository.load().records }
            assertEquals(2,records.map { it.sessionId }.distinct().size)
            assertTrue(records.all { it.completed && it.name=="Короткая" })
        } finally {
            compose.runOnUiThread { TimerService.command(context,"stop"); TimerService.clearResult() }
            compose.waitForIdle()
            runBlocking { repository.clear() }
        }
    }
}
