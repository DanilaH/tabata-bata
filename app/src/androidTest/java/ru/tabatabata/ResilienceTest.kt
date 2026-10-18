package ru.tabatabata

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real-time service tests. No assertion here proves physical audio delivery. */
class ResilienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private fun text(id: Int) = compose.activity.getString(id)

    private fun await(timeout: Long = 8000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) {
                "Timed out: state=${TimerService.state.value}"
            }
            SystemClock.sleep(50)
        }
    }

    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command)
            )
            .bufferedReader()
            .use { it.readText() }

    private fun clean() {
        if (
            TimerService.state.value?.status in
                listOf(Status.RUNNING, Status.PAUSED, Status.INTERRUPTED)
        ) {
            TimerService.command(context, "stop")
            await { TimerService.state.value == null || TimerService.state.value?.status == Status.STOPPED }
        }
        TimerService.clearResult()
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        SystemClock.sleep(500)
    }

    @Test
    fun backgroundScreenOffNotificationActionsAndCompletion() {
        clean()
        if (android.os.Build.VERSION.SDK_INT >= 33)
            shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val notifications = context.getSystemService(NotificationManager::class.java)
        val plan = Plan(prepare = 1, work = 3, rest = 1, reps = 2)
        try {
            compose.runOnUiThread { TimerService.command(context, "start", plan) }
            await { TimerService.state.value?.status == Status.RUNNING }
            await { notifications.activeNotifications.any { it.id == 1 } }
            notifications.activeNotifications
                .first { it.id == 1 }
                .notification
                .actions[0]
                .actionIntent
                .send()
            await { TimerService.state.value?.status == Status.PAUSED }
            val frozen = TimerService.state.value!!.remaining
            SystemClock.sleep(1200)
            assertEquals(frozen, TimerService.state.value!!.remaining)
            await {
                notifications.activeNotifications
                    .firstOrNull { it.id == 1 }
                    ?.notification
                    ?.actions
                    ?.firstOrNull()
                    ?.title == text(R.string.resume)
            }
            notifications.activeNotifications
                .first { it.id == 1 }
                .notification
                .actions[0]
                .actionIntent
                .send()
            await { TimerService.state.value?.status == Status.RUNNING }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            val before = TimerService.state.value!!.totalRemaining
            shell("input keyevent KEYCODE_SLEEP")
            await { !context.getSystemService(PowerManager::class.java).isInteractive }
            SystemClock.sleep(2000)
            val after = TimerService.state.value!!.totalRemaining
            assertTrue("Timer must advance while screen is off", before - after >= 1700)
            await(12000) { TimerService.state.value?.status == Status.COMPLETED }
            await { notifications.activeNotifications.none { it.id == 1 } }
            assertFalse(
                context.getSharedPreferences("session", Context.MODE_PRIVATE).contains("active")
            )
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            clean()
        }
    }

    @Test
    fun notificationPermissionBlockedDoesNotBlockTimer() {
        clean()
        try {
            shell("appops set ${context.packageName} POST_NOTIFICATION ignore")
            compose.runOnUiThread {
                TimerService.command(
                    context,
                    "start",
                    Plan(prepare = 0, work = 2, rest = 0, reps = 1),
                )
            }
            await { TimerService.state.value?.status == Status.RUNNING }
            await { TimerService.state.value?.status == Status.COMPLETED }
        } finally {
            shell("appops set ${context.packageName} POST_NOTIFICATION allow")
            clean()
        }
    }

    @Test
    fun interruptedCheckpointRequiresExplicitRecoveryAndResume() {
        clean()
        val checkpoint =
            JSONObject()
                .put("plan", Plan().json())
                .put("index", 1)
                .put("remaining", 4321)
                .put("sessionId", "recovery-test")
        try {
            context
                .getSharedPreferences("session", Context.MODE_PRIVATE)
                .edit()
                .putString("active", checkpoint.toString())
                .commit()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(text(R.string.recovery_title)).assertExists()
            assertNull(TimerService.state.value)
            compose.onNodeWithText(text(R.string.recover)).performClick()
            await { TimerService.state.value?.status == Status.INTERRUPTED }
            val frozen = TimerService.state.value!!.remaining
            SystemClock.sleep(1200)
            assertEquals(4321L, frozen)
            assertEquals(frozen, TimerService.state.value!!.remaining)
            compose.onNodeWithText(text(R.string.resume)).performClick()
            await { TimerService.state.value?.status == Status.RUNNING }
        } finally {
            clean()
        }
    }

    @Test
    fun largeFontLandscapeEditorRemainsUsable() {
        clean()
        val original = shell("settings get system font_scale").trim()
        try {
            shell("settings put system font_scale 1.5")
            compose.waitUntil(10000) { compose.activity.resources.configuration.fontScale >= 1.49f }
            compose.waitForIdle()
            compose.onNodeWithText("+ " + text(R.string.new_timer)).performClick()
            compose.waitForIdle()
            compose.runOnUiThread {
                compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            compose.waitUntil(10000) {
                compose.activity.resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            compose.waitUntil(10000) {
                compose.onAllNodesWithText(text(R.string.start)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onRoot().printToLog("LargeFontLandscape")
            compose.onNodeWithText(text(R.string.start)).assertIsDisplayed().performClick()

            await { TimerService.state.value?.status == Status.RUNNING }
            compose.onNodeWithText(text(R.string.pause)).assertIsDisplayed().performClick()

            await { TimerService.state.value?.status == Status.PAUSED }
            compose.onNodeWithText(text(R.string.stop)).assertIsDisplayed()
            val image = compose.onRoot().captureToImage()
            val folder =
                java.io
                    .File(
                        requireNotNull(
                            InstrumentationRegistry.getArguments()
                                .getString("additionalTestOutputDir")
                        ),
                        "screenshots",
                    )
                    .apply { mkdirs() }
            java.io.File(folder, "landscape-large-font.png").outputStream().use {
                image
                    .asAndroidBitmap()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            shell("settings put system font_scale ${original.toFloatOrNull() ?: 1f}")
            compose.runOnUiThread {
                compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            clean()
        }
    }
}
