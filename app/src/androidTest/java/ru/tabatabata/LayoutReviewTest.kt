package ru.tabatabata

import android.app.LocaleManager
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class LayoutReviewTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun capture(name: String) {
        val dir=File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "screenshots")
        dir.mkdirs()
        File(dir,"$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    private fun label(id: Int) = compose.activity.getString(id)
    @Test fun englishLargeTextEditorSettingsAndLandscapeRest() {
        val font = shell("settings get system font_scale").trim()
        val locales = instrumentation.targetContext.getSystemService(LocaleManager::class.java).applicationLocales
        try {
            instrumentation.targetContext.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("en")
            shell("settings put system font_scale 1.5")
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            compose.onNodeWithText("+ " + label(R.string.new_timer)).assertIsDisplayed()
            capture("home-en-large")
            compose.onNodeWithText("+ " + label(R.string.new_timer)).performClick()
            capture("editor-en-large")
            compose.onNodeWithContentDescription(label(R.string.cool)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(label(R.string.last_rest)).performScrollTo().assertIsDisplayed()
            capture("editor-options-en-large")
            compose.onNodeWithText("← " + label(R.string.saved)).performScrollTo().performClick()
            compose.onNodeWithText(label(R.string.settings)).performClick()
            val finish = compose.onNodeWithText(label(R.string.finish_cue))
            finish.performScrollTo().assertIsDisplayed()
            val layouts= mutableListOf<TextLayoutResult>()
            finish.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            assertTrue(layouts.isNotEmpty())
            capture("settings-en-large")
            assertFalse(layouts.joinToString { "text=${it.layoutInput.text} size=${it.size} paragraph=${it.multiParagraph.width}x${it.multiParagraph.height} widthOverflow=${it.didOverflowWidth} heightOverflow=${it.didOverflowHeight}" }, layouts.any { it.hasVisualOverflow })
            compose.onNodeWithText(label(R.string.vibration_preview)).performScrollTo().assertIsDisplayed()
            capture("settings-vibration-en-large")
            compose.runOnUiThread {
                TimerService.command(compose.activity,"start",Plan(0,30,30,2))
            }
            compose.waitUntil(10000) { TimerService.state.value?.status == Status.RUNNING }
            compose.runOnUiThread {
                TimerService.command(compose.activity,"next")
                TimerService.command(compose.activity,"pause")
                compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            compose.waitUntil(10000) { TimerService.state.value?.status == Status.PAUSED && compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            compose.waitForIdle()
            compose.onNodeWithText(label(R.string.add_rest)).assertIsDisplayed()
            compose.onNodeWithText(label(R.string.resume)).assertIsDisplayed()
            capture("rest-landscape-en-large")
            compose.runOnUiThread { TimerService.command(compose.activity,"stop") }
            compose.waitUntil(10000) { TimerService.state.value == null }
            compose.onNodeWithText("+ " + label(R.string.new_timer)).assertIsDisplayed()
            compose.onNodeWithText(label(R.string.stopped)).assertDoesNotExist()
        } finally {
            if (TimerService.state.value?.status in listOf(Status.RUNNING,Status.PAUSED,Status.INTERRUPTED)) {
                compose.runOnUiThread { TimerService.command(compose.activity,"stop") }
            }
            shell("settings put system font_scale ${font.toFloatOrNull() ?: 1f}")
            instrumentation.targetContext.getSystemService(LocaleManager::class.java).applicationLocales=locales
            compose.runOnUiThread { compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }
}
