package ru.tabatabata

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Regression acceptance for the independent audit findings. */
class AuditUiProbe {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = compose.activity.getString(id)
    private fun shell(cmd: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
    ).bufferedReader().use { it.readText() }
    private fun capture(name: String) {
        val dir = File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "audit")
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun stop() {
        if (TimerService.state.value != null) {
            compose.runOnUiThread { TimerService.command(context, "stop") }
            compose.waitUntil(10000) { TimerService.state.value == null || TimerService.state.value?.status == Status.STOPPED }
            TimerService.clearResult()
        }
    }
    @Test fun clockFontChangesAtMinuteBoundary() {
        stop()
        val engine = TimerEngine(Plan(prepare=0, work=61, rest=0, reps=1),0)
        val state = mutableStateOf(engine.snapshot(0))
        compose.runOnUiThread {
            compose.activity.setContent { MaterialTheme { Workout(state.value, Options(), {}, {}) } }
        }
        fun font(label: String): Float {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.first().layoutInput.style.fontSize.value
        }
        val before = font("1:01")
        capture("clock-61")
        compose.runOnUiThread { state.value = engine.snapshot(2000) }
        val after = font("59")
        capture("clock-59")
        println("AUDIT clock_font_61sec=$before clock_font_59sec=$after")
        assertEquals(before, after, 0.01f)
    }
    @Test fun notificationExplanationLostOnRecreation() {
        stop()
        shell("pm revoke ${context.packageName} android.permission.POST_NOTIFICATIONS")
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("notificationAsked", false).commit()
        compose.onAllNodesWithText(text(R.string.start)).onFirst().performClick()
        compose.onNodeWithText(text(R.string.notification_title)).assertExists()
        capture("permission-before-recreate")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(text(R.string.notification_title)).assertExists()
        assertNull(TimerService.state.value)
        println("AUDIT first_start_retained_after_recreate=true")
        compose.onNodeWithText(text(R.string.later)).performClick()
        compose.waitUntil(10000) { TimerService.state.value?.status == Status.RUNNING }
        stop()
        capture("permission-after-recreate")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
    }
    @Test fun wakeLockExpiresAfterRepeatedPrevious() {
        stop()
        compose.runOnUiThread { TimerService.command(context, "start", Plan(prepare=0, work=6, rest=0, reps=1)) }
        compose.waitUntil(10000) { TimerService.state.value?.status == Status.RUNNING }
        assertTrue(Regex("(?m)^  PARTIAL_WAKE_LOCK.*TabataBata:Workout").containsMatchIn(shell("dumpsys power")))
        repeat(4) {
            SystemClock.sleep(3000)
            compose.runOnUiThread { TimerService.command(context, "previous") }
        }
        assertEquals(Status.RUNNING, TimerService.state.value?.status)
        val dump = shell("dumpsys power")
        println("AUDIT power_after_previous=\n$dump")
        val held = Regex("(?m)^  PARTIAL_WAKE_LOCK.*TabataBata:Workout").containsMatchIn(dump)
        println("AUDIT after_previous_running=true wake_lock_held=$held")
        assertTrue("Wake timeout refreshed by previous", held)
        capture("running-after-wake-expiry")
        stop()
    }
    @Test fun canceledHoldThenAccessibilityClickAndPressFeedback() {
        stop()
        EditorDrafts(context).clear("new")
        compose.onNodeWithText("+ " + text(R.string.new_timer)).performClick()
        val field = compose.onNodeWithContentDescription(text(R.string.work))
        fun value() = parseDuration(field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)!!
        field.assertExists()
        val plus = compose.onNodeWithContentDescription("+ " + text(R.string.work))
        plus.assertExists()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            plus.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(100)
            capture("plus-pressed")
            compose.mainClock.advanceTimeBy(1400)
            plus.performTouchInput { cancel() }
            compose.mainClock.advanceTimeBy(100)
            val before = value()
            plus.performClick()
            compose.mainClock.advanceTimeBy(100)
            println("AUDIT canceled_hold_semantics_click_before=$before after=${value()}")
            assertEquals(before+1, value())
            plus.performClick()
            compose.mainClock.advanceTimeBy(100)
            assertEquals(before+2, value())
            capture("plus-released")
        } finally { compose.mainClock.autoAdvance = true; EditorDrafts(context).clear("new") }
    }
    @Test fun narrowEditorMeasuresButtonAndLargeFont() {
        stop()
        EditorDrafts(context).clear("new")
        val density = shell("wm density").trim()
        val font = shell("settings get system font_scale").trim()
        try {
            shell("wm size 960x1920")
            shell("wm density 480")
            compose.waitUntil(10000) { compose.activity.resources.configuration.screenWidthDp == 320 }
            compose.onNodeWithText("+ " + text(R.string.new_timer)).performClick()
            fun measure(label: String) {
                val node = compose.onNodeWithContentDescription(label).fetchSemanticsNode()
                val dp = node.boundsInRoot.width / compose.activity.resources.displayMetrics.density
                println("AUDIT narrow320 label=$label widthDp=$dp bounds=${node.boundsInRoot}")
                assertTrue("Button must retain at least 48dp", dp >= 48f)
            }
            measure("+ " + text(R.string.work))
            measure("− " + text(R.string.work))
            capture("editor-320dp")
            shell("settings put system font_scale 2.0")
            compose.waitUntil(10000) { compose.activity.resources.configuration.fontScale >= 1.99f }
            compose.onNodeWithText(text(R.string.start)).assertIsDisplayed()
            capture("editor-320dp-font200")
        } finally {
            shell("wm size reset")
            val override = Regex("Override density: (\\d+)").find(density)?.groupValues?.get(1)
            shell(if (override == null) "wm density reset" else "wm density $override")
            shell("settings put system font_scale ${font.toFloatOrNull() ?: 1f}")
            EditorDrafts(context).clear("new")
        }
    }
}
