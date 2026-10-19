package ru.tabatabata

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EditorDraftTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun text(id: Int) = compose.activity.getString(id)

    @Test
    fun newDraftSurvivesRecreationIncludingInvalidInput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val drafts = EditorDrafts(context)
        drafts.clear("new")
        try {
            compose.onNodeWithText("+ " + text(R.string.new_timer)).performClick()
            compose.onNodeWithContentDescription(text(R.string.work)).performTextReplacement("2:30")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithContentDescription(text(R.string.work)).assertTextContains("2:30")
            compose.onNodeWithContentDescription(text(R.string.work)).performTextReplacement("1:")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithContentDescription(text(R.string.work)).assertTextContains("1:")
            compose.onNodeWithText(text(R.string.start)).assertIsNotEnabled()
            assertTrue(EditorDrafts(context).read("new")!!.contains("1:"))
        } finally {
            drafts.clear("new")
        }
    }

    @Test
    fun discardLeavesSavedModeUntouched() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = Store(context)
        val before = runBlocking { store.presets.first() }
        val p = Preset(name = "Draft test", plan = Plan())
        try {
            runBlocking { store.savePresets(listOf(p)) }
            compose.waitUntil(10000) {
                compose.onAllNodesWithText(p.name).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(p.name).performClick()
            compose.onNodeWithContentDescription(text(R.string.work)).performTextReplacement("0:45")
            compose.onNodeWithContentDescription(text(R.string.work)).assertTextContains("0:45")
            // This test verifies draft state, not hit testing while the IME moves the layout.
            // Touch input remains covered by WorkoutSmokeTest / EditorControlsTest.
            compose.onNodeWithText("← " + text(R.string.saved)).performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText(text(R.string.unsaved)).assertExists()
            compose.onNodeWithText(text(R.string.reset)).performClick()
            assertEquals(p, runBlocking { store.presets.first().single() })
            assertFalse(EditorDrafts(context).has(p.id))
        } finally {
            runBlocking { store.savePresets(before) }
            EditorDrafts(context).clear(p.id)
        }
    }

    @Test
    fun startingEditedModeReturnsToItsDraftAndCanSave() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS))
        val store = Store(context)
        val before = runBlocking { store.presets.first() }
        val lastPlan = runBlocking { store.lastPlan.first() }
        val p = Preset(name = "Launch draft", plan = Plan())
        try {
            runBlocking { store.savePresets(listOf(p)) }
            compose.waitUntil(10000) {
                compose.onAllNodesWithText(p.name).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(p.name).performClick()
            compose.onNodeWithContentDescription(text(R.string.work)).performTextReplacement("0:45")
            compose.onNodeWithContentDescription(text(R.string.work)).assertTextContains("0:45")
            compose.onNodeWithText(text(R.string.start)).assertIsEnabled()
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(10000) { TimerService.state.value?.status == Status.RUNNING }
            assertEquals(p, runBlocking { store.presets.first().single() })
            compose.runOnUiThread { TimerService.command(context, "stop") }
            compose.waitUntil(10000) { TimerService.state.value == null || TimerService.state.value?.status == Status.STOPPED }
            compose.waitUntil(5000) { TimerService.state.value == null }
            compose.onNodeWithText(p.name).performClick()
            compose.onNodeWithContentDescription(text(R.string.work)).assertTextContains("0:45")
            compose.onNodeWithText(text(R.string.save)).assertIsEnabled()
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(10000) {
                runBlocking { store.presets.first().single().plan.work == 45 }
            }
            // DataStore emits before the save coroutine clears the draft and closes the editor.
            // Wait for the user-visible completion, then check the same postcondition.
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("+ " + text(R.string.new_timer)).fetchSemanticsNodes().isNotEmpty()
            }
            assertFalse(EditorDrafts(context).has(p.id))
        } finally {
            if (
                TimerService.state.value?.status in
                    listOf(Status.RUNNING, Status.PAUSED, Status.INTERRUPTED)
            ) {
                compose.runOnUiThread { TimerService.command(context, "stop") }
                compose.waitUntil(10000) { TimerService.state.value == null || TimerService.state.value?.status == Status.STOPPED }
            }
            TimerService.clearResult()
            runBlocking {
                store.savePresets(before)
                store.saveLastPlan(lastPlan)
            }
            EditorDrafts(context).clear(p.id)
        }
    }
}
