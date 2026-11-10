package ru.tabatabata

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ProductPassTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = compose.activity.getString(id)
    private fun capture(name: String) {
        val dir = File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "product-080").apply { mkdirs() }
        File(dir,"$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }

    @Test fun previewRotationAndAppearanceEditingKeepTheSavedPlan() {
        val store = Store(context)
        val before = runBlocking { store.presets.first() }
        val options = runBlocking { store.options.first() }
        val first = Preset("product-classic", "Табата 20/10", Plan(), "green", 2)
        val second = Preset("product-strength", "Силовая · три серии", Plan(work=45,rest=15,reps=10,sets=3), "violet", 1)
        try {
            runBlocking { store.saveOptions(options.copy(theme="light")); store.savePresets(listOf(first,second)) }
            compose.waitUntil(10000) { compose.onAllNodesWithText(first.name).fetchSemanticsNodes().isNotEmpty() }
            capture("home-light")
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.mode_actions, first.name)).performClick()
            compose.onNodeWithText(text(R.string.plan_preview)).performClick()
            compose.onNodeWithTag("preview-total").assertTextEquals("3:55")
            compose.onNodeWithTag("preview-stage-WORK").assertTextEquals("2:40")
            compose.onNodeWithTag("preview-stage-REST").assertTextEquals("1:10")
            capture("plan-light")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("plan-preview").assertExists()
            compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            compose.onNodeWithText(text(R.string.preview_close)).assertIsDisplayed()
            capture("plan-landscape")
            compose.onNodeWithText(text(R.string.preview_close)).performClick()
            compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(10000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            compose.onNodeWithText(first.name).performClick()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.card_accent_label, text(R.string.accent_orange)))
                .performScrollTo().performClick()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.card_mark_label, text(R.string.mark_strength)))
                .performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.save)).performClick()
            compose.waitUntil(10000) { runBlocking { store.presets.first().first().accent } == "orange" }
            val saved = runBlocking { store.presets.first().first() }
            assertEquals(1,saved.mark)
            assertEquals(first.plan,saved.plan)
            val imported = store.import(store.export(listOf(saved))).single()
            assertEquals(saved.accent,imported.accent)
            assertEquals(saved.mark,imported.mark)
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(first.name).assertIsDisplayed()
            runBlocking { store.saveOptions(options.copy(theme="dark")) }
            compose.waitForIdle()
            capture("home-dark")
        } finally {
            EditorDrafts(context).clear(first.id)
            runBlocking { store.savePresets(before); store.saveOptions(options) }
            compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    @Test fun signalChoicesSurviveRecreationAndEditorPreviewDoesNotSave() {
        val store = Store(context)
        val options = runBlocking { store.options.first() }
        val before = runBlocking { store.presets.first() }
        EditorDrafts(context).clear("new")
        try {
            compose.onNodeWithText(text(R.string.settings)).performClick()
            compose.onNodeWithText(text(R.string.profile_bell)).performScrollTo().performClick()
            if (!options.halfway) compose.onNodeWithText(text(R.string.halfway)).performScrollTo().performClick()
            if (!options.voice) compose.onNodeWithText(text(R.string.voice)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.voice_stages_rounds)).performScrollTo().performClick()
            compose.waitUntil(10000) { runBlocking { store.options.first() }.let { it.soundProfile=="bell" && it.halfway && it.voiceRounds } }
            compose.activityRule.scenario.recreate()
            assertEquals("bell",runBlocking { store.options.first().soundProfile })
            compose.onNodeWithText(text(R.string.profile_bell)).performScrollTo().assertExists()
            capture("signals")
            compose.onNodeWithText("← " + text(R.string.saved)).performClick()
            compose.onNodeWithText("+ " + text(R.string.new_timer)).performClick()
            compose.onNodeWithContentDescription(text(R.string.work)).performTextReplacement("0:45")
            capture("editor")
            compose.onNodeWithContentDescription(text(R.string.plan_preview)).performClick()
            compose.onNodeWithTag("preview-stage-WORK").assertTextEquals("6:00")
            assertEquals(before,runBlocking { store.presets.first() })
            assertNull(TimerService.state.value)
            compose.onNodeWithText(text(R.string.preview_close)).performClick()
            compose.onNodeWithContentDescription(text(R.string.work)).assertTextContains("0:45")
        } finally {
            EditorDrafts(context).clear("new")
            runBlocking { store.saveOptions(options) }
        }
    }
}
