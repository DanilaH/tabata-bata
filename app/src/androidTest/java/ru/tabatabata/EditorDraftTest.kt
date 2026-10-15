package ru.tabatabata

import androidx.compose.ui.test.*
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
    fun quickDraftSurvivesNavigationAndRecreationIncludingInvalidInput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val drafts = EditorDrafts(context)
        drafts.clear("quick")
        try {
            compose.onNodeWithText(text(R.string.quick)).performClick()
            compose.onNodeWithContentDescription(text(R.string.work)).performTextReplacement("2:30")
            compose.onNodeWithText(text(R.string.settings)).performClick()
            compose.onNodeWithText("← " + text(R.string.saved)).performClick()
            compose.onNodeWithText(text(R.string.quick)).performClick()
            compose.onNodeWithContentDescription(text(R.string.work)).assertTextContains("2:30")
            compose.onNodeWithContentDescription(text(R.string.work)).performTextReplacement("1:")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithContentDescription(text(R.string.work)).assertTextContains("1:")
            compose.onNodeWithText(text(R.string.start)).assertIsNotEnabled()
            assertTrue(EditorDrafts(context).read("quick")!!.contains("1:"))
        } finally {
            drafts.clear("quick")
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
            compose.onNodeWithText("← " + text(R.string.saved)).performClick()
            compose.onNodeWithText(text(R.string.unsaved)).assertExists()
            compose.onNodeWithText(text(R.string.reset)).performClick()
            assertEquals(p, runBlocking { store.presets.first().single() })
            assertFalse(EditorDrafts(context).has(p.id))
        } finally {
            runBlocking { store.savePresets(before) }
            EditorDrafts(context).clear(p.id)
        }
    }
}
