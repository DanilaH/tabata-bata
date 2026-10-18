package ru.tabatabata

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EditorControlsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun label(id: Int) = compose.activity.getString(id)

    @Test fun holdingAcceleratesAndStopsOnRelease() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val drafts = EditorDrafts(context)
        drafts.clear("new")
        try {
            compose.onNodeWithText("+ " + label(R.string.new_timer)).performClick()
            val field = compose.onNodeWithContentDescription(label(R.string.work))
            field.performTextReplacement("0:20")
            compose.onNodeWithContentDescription("+ " + label(R.string.work)).performClick()
            field.assertTextContains("0:21")
            compose.mainClock.autoAdvance = false
            val plus = compose.onNodeWithContentDescription("+ " + label(R.string.work))
            plus.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(1000)
            fun value(): Int = parseDuration(field.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)!!
            val early = value()
            compose.mainClock.advanceTimeBy(1000)
            val middle = value()
            compose.mainClock.advanceTimeBy(1000)
            val late = value()
            assertTrue(early > 21)
            assertTrue("Holding must accelerate", late-middle > middle-early)
            plus.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(100)
            val released = value()
            compose.mainClock.advanceTimeBy(1000)
            assertEquals(released, value())
            compose.mainClock.autoAdvance = true
            field.performTextReplacement("0:01")
            compose.onNodeWithContentDescription("− " + label(R.string.work)).assertIsNotEnabled()
        } finally { compose.mainClock.autoAdvance = true; drafts.clear("new") }
    }

    @Test fun readyVisibilityPersistsAndDuplicateCanBeEdited() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = Store(context)
        val original = runBlocking { store.options.first() }
        val presets = runBlocking { store.presets.first() }
        val mode = Preset(name = "Menu edit test", plan = Plan())
        try {
            compose.onNodeWithText(label(R.string.settings)).performClick()
            compose.onNodeWithText(label(R.string.show_ready_modes)).performScrollTo().performClick()
            compose.waitUntil(10000) { runBlocking { store.options.first().showReadyModes } != original.showReadyModes }
            compose.activityRule.scenario.recreate()
            assertEquals(!original.showReadyModes, runBlocking { Store(context).options.first().showReadyModes })
            compose.onNodeWithText("← " + label(R.string.saved)).performClick()
            if (original.showReadyModes) compose.onNodeWithText(label(R.string.ready_mode)).assertDoesNotExist()
            runBlocking { store.savePresets(listOf(mode)) }
            compose.waitUntil(10000) { compose.onAllNodesWithText(mode.name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.mode_actions, mode.name)).performClick()
            compose.onNodeWithText(label(R.string.copy)).performClick()
            compose.waitUntil(10000) { runBlocking { store.presets.first().size } == 2 }
            val copy = runBlocking { store.presets.first().last() }
            compose.onNodeWithText(copy.name).performScrollTo()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.mode_actions, copy.name)).performClick()
            compose.onNodeWithText(label(R.string.edit)).performClick()
            compose.onNodeWithContentDescription(label(R.string.work)).performTextReplacement("0:35")
            compose.onNodeWithText(label(R.string.save)).performClick()
            compose.waitUntil(10000) { runBlocking { store.presets.first().last().plan.work } == 35 }
            assertEquals(20, runBlocking { store.presets.first().first().plan.work })
        } finally { runBlocking { store.saveOptions(original); store.savePresets(presets) } }
    }
}
