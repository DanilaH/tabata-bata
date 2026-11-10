package ru.tabatabata

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class HistoryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = compose.activity.getString(id)
    private fun capture(name: String) {
        val root = requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
        val dir = File(root,"history-090").apply { mkdirs() }
        compose.waitForIdle()
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(dir,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
    }

    @Test fun historyRepeatsOriginalDeletedPlanAndSurvivesRotation() {
        val repository = HistoryRepository(context)
        val ui = context.getSharedPreferences("ui",0)
        val asked = ui.getBoolean("notificationAsked",false)
        val plan = Plan(prepare=0,work=60,rest=15,reps=4)
        try {
            runBlocking { repository.clear() }
            ui.edit().putBoolean("notificationAsked",true).commit()
            val now = System.currentTimeMillis()
            repository.enqueue(WorkoutRecord("ui-done",Plan(),"Табата 20/10","green",2,
                now-235000,now,true,235000,8))
            repository.enqueue(WorkoutRecord("ui-stopped",plan,"Силовая · четыре раунда","orange",1,
                now-120000,now-1,false,78000,1))
            compose.onNodeWithText(text(R.string.history_open)+" →").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("history-ui-done").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("history-week-time").assertTextEquals("5:13")
            compose.onNodeWithTag("history-week-counts").assertTextEquals(compose.activity.getString(R.string.history_week_counts,1,1))
            capture("history")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("history-list").assertExists()
            compose.runOnUiThread { compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10000) { compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            capture("history-landscape")
            compose.runOnUiThread { compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(10000) { compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT }
            compose.onNodeWithTag("history-list").performScrollToNode(hasTestTag("history-ui-stopped"))
            capture("history-stopped")
            compose.onNode(hasAnyAncestor(hasTestTag("history-ui-stopped")) and hasText(text(R.string.history_saved_plan))).performClick()
            compose.onNodeWithTag("preview-total").assertTextEquals("4:45")
            capture("history-plan")
            compose.onNodeWithText(text(R.string.preview_close)).performClick()
            // There is no matching saved preset: repeat must use the history snapshot.
            compose.onNode(hasAnyAncestor(hasTestTag("history-ui-stopped")) and hasText(text(R.string.repeat))).performClick()
            compose.waitUntil(10000) { TimerService.state.value?.status==Status.RUNNING }
            assertEquals(plan,TimerService.state.value!!.plan)
            assertNotEquals("ui-stopped",TimerService.state.value!!.sessionId)
            assertEquals("Силовая · четыре раунда",TimerService.sessionPreset.value!!.name)
            TimerService.command(context,"stop")
            compose.waitUntil(10000) { TimerService.state.value==null }
            compose.onNodeWithText(text(R.string.history_open)+" →").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText(compose.activity.getString(R.string.history_week_counts,1,2)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("history-week-counts").assertTextEquals(compose.activity.getString(R.string.history_week_counts,1,2))
        } finally {
            if (TimerService.state.value!=null) TimerService.command(context,"stop")
            compose.waitUntil(10000) { TimerService.state.value==null }
            runBlocking { repository.clear() }
            ui.edit().putBoolean("notificationAsked",asked).commit()
            compose.runOnUiThread { compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    @Test fun emptyHistoryAndDestructiveClearRequireConfirmation() {
        val repository = HistoryRepository(context)
        runBlocking { repository.clear() }
        try {
            compose.onNodeWithText(text(R.string.history_open)+" →").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText(text(R.string.history_empty_title)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(text(R.string.history_empty_title)).assertExists()
            capture("history-empty")
            repository.enqueue(WorkoutRecord("clear-ui",Plan(),"Режим",finishedAt=System.currentTimeMillis(),
                completed=true,activeMs=235000,completedRounds=8))
            compose.waitUntil(10000) { compose.onAllNodesWithTag("history-clear-ui").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("history-list").performScrollToNode(hasText(text(R.string.history_clear)))
            compose.onNodeWithText(text(R.string.history_clear)).performClick()
            compose.onNodeWithText(text(R.string.cancel)).performClick()
            assertEquals(1,runBlocking { repository.load().total })
            compose.onNodeWithText(text(R.string.history_clear)).performClick()
            compose.onNodeWithText(text(R.string.history_clear_title)).assertExists()
            compose.onAllNodesWithText(text(R.string.history_clear)).onLast().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText(text(R.string.history_empty_title)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("history-week-time").assertTextEquals("0:00")
        } finally { runBlocking { repository.clear() } }
    }
    @Test fun longNamesAndActionsFitLargeTextAndLandscape() {
        val repository = HistoryRepository(context)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        val font = shell("settings get system font_scale").trim().toFloatOrNull() ?: 1f
        runBlocking { repository.clear() }
        try {
            repository.enqueue(WorkoutRecord("large-ui",Plan(),
                "Силовая тренировка с длинным названием и несколькими сериями", "violet", 1,
                finishedAt=System.currentTimeMillis(),completed=false,activeMs=123000,completedRounds=3))
            shell("settings put system font_scale 1.5")
            compose.waitUntil(10000) { compose.activity.resources.configuration.fontScale>=1.49f }
            compose.onNodeWithText(text(R.string.history_open)+" →").performScrollTo().performClick()
            compose.onNodeWithTag("history-list").performScrollToNode(hasTestTag("history-large-ui"))
            compose.onNodeWithText(text(R.string.repeat)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.history_saved_plan)).performScrollTo().assertIsDisplayed()
            capture("history-large-text")
            compose.runOnUiThread { compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10000) { compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            compose.onNodeWithText(text(R.string.history_saved_plan)).performScrollTo().assertIsDisplayed()
            capture("history-landscape-large-text")
        } finally {
            shell("settings put system font_scale $font")
            compose.runOnUiThread { compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            runBlocking { repository.clear() }
        }
    }

}
