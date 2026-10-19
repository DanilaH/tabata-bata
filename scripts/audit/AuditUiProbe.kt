package ru.tabatabata

import android.content.Context
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

/** Deliberately probes defects on 0.6.1; assertions are evidence, not desired UX. */
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
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun stop() {
        if (TimerService.state.value != null) {
            compose.runOnUiThread { TimerService.command(context, "stop") }
            compose.waitUntil(10000) { TimerService.state.value == null || TimerService.state.value?.status == Status.STOPPED }
            TimerService.clearResult()
        }
    }
    @Test fun notificationExplanationLostOnRecreation() {
        stop()
        shell("pm revoke ${context.packageName} android.permission.POST_NOTIFICATIONS")
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("notificationAsked", false).commit()
        compose.onAllNodesWithText(text(R.string.start)).onFirst().performClick()
        compose.onNodeWithText(text(R.string.notification_title)).assertExists()
        capture("permission-before-recreate")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(text(R.string.notification_title)).assertDoesNotExist()
        assertNull(TimerService.state.value)
        println("AUDIT first_start_lost_after_recreate=true")
        capture("permission-after-recreate")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
    }
    @Test fun wakeLockExpiresAfterRepeatedPrevious() {
        stop()
        compose.runOnUiThread { TimerService.command(context, "start", Plan(prepare=0, work=6, rest=0, reps=1)) }
        compose.waitUntil(10000) { TimerService.state.value?.status == Status.RUNNING }
        assertTrue(shell("dumpsys power").contains("TabataBata:Workout"))
        repeat(4) {
            SystemClock.sleep(3000)
            compose.runOnUiThread { TimerService.command(context, "previous") }
        }
        assertEquals(Status.RUNNING, TimerService.state.value?.status)
        val held = shell("dumpsys power").contains("TabataBata:Workout")
        println("AUDIT after_previous_running=true wake_lock_held=$held")
        assertFalse("Reproduces wake timeout not extended by previous", held)
        capture("running-after-wake-expiry")
        stop()
    }
    @Test fun canceledHoldThenAccessibilityClickAndPressFeedback() {
        stop()
        EditorDrafts(context).clear("new")
        compose.onNodeWithText("+ " + text(R.string.new_timer)).performClick()
        val field = compose.onNodeWithContentDescription(text(R.string.work))
        fun value() = parseDuration(field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)!!
        val plus = compose.onNodeWithContentDescription("+ " + text(R.string.work))
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
            assertEquals(before, value())
            plus.performClick()
            compose.mainClock.advanceTimeBy(100)
            assertEquals(before+1, value())
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
