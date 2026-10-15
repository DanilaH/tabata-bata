package ru.tabatabata

import android.Manifest
import android.app.LocaleManager
import android.graphics.Bitmap
import android.os.Build
import android.os.LocaleList
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

class WorkoutSmokeTest {
    @get:Rule(order = 0)
    val permission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1)
    val russianLocale: ExternalResource =
        object : ExternalResource() {
            override fun before() {
                if (Build.VERSION.SDK_INT >= 33) {
                    InstrumentationRegistry.getInstrumentation()
                        .targetContext
                        .getSystemService(LocaleManager::class.java)
                        .applicationLocales = LocaleList.forLanguageTags("ru")
                }
            }
        }
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()

    private fun text(id: Int) = compose.activity.getString(id)

    private fun capture(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val output =
            requireNotNull(
                InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ) {
                "Gradle additional test output directory is required"
            }
        val dir = File(output, "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun themesPersistAndRender() {
        val store = Store(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = kotlinx.coroutines.runBlocking { store.options.first() }
        try {
            for (theme in listOf("light", "dark")) {
                kotlinx.coroutines.runBlocking { store.saveOptions(original.copy(theme = theme)) }
                compose.activityRule.scenario.recreate()
                compose.waitForIdle()
                capture("home-$theme")
                compose.onNodeWithText(text(R.string.settings)).performClick()
                compose
                    .onNodeWithText(
                        text(if (theme == "light") R.string.theme_light else R.string.theme_dark)
                    )
                    .assertExists()
                capture("settings-$theme")
                compose.activityRule.scenario.recreate()
                org.junit.Assert.assertEquals(
                    theme,
                    kotlinx.coroutines.runBlocking { store.options.first().theme },
                )
                compose.onNodeWithText(text(R.string.app_name)).assertExists()
                // Settings retains its navigation state across recreation.
                compose.onNodeWithText("← " + text(R.string.saved)).performClick()
                compose.waitForIdle()
            }
        } finally {
            kotlinx.coroutines.runBlocking { store.saveOptions(original) }
        }
    }

    @Test
    fun workoutThemesQueueAndTouchLock() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = Store(context)
        val original = kotlinx.coroutines.runBlocking { store.options.first() }
        try {
            for (theme in listOf("light", "dark")) {
                kotlinx.coroutines.runBlocking {
                    store.saveOptions(original.copy(theme = theme, showStages = true))
                }
                compose.runOnUiThread {
                    TimerService.command(
                        context,
                        "start",
                        Plan(prepare = 0, work = 30, rest = 10, reps = 8),
                    )
                }
                compose.waitUntil(10000) { TimerService.state.value?.status == Status.RUNNING }
                compose.waitForIdle()
                capture("work-$theme")
                compose.onNodeWithText(text(R.string.pause)).performClick()
                compose.waitUntil(5000) { TimerService.state.value?.status == Status.PAUSED }
                compose.waitForIdle()
                capture("paused-$theme")
                compose.onNodeWithText(text(R.string.lock_short)).performClick()
                compose.onNodeWithText(text(R.string.resume)).assertIsNotEnabled()
                compose.onNodeWithContentDescription(text(R.string.next)).assertIsNotEnabled()
                capture("locked-$theme")
                compose.onNodeWithContentDescription(text(R.string.unlock)).performTouchInput {
                    longClick()
                }
                compose.onNodeWithText(text(R.string.resume)).assertIsEnabled()
                compose.onNodeWithContentDescription(text(R.string.next)).performClick()
                compose.waitUntil(5000) { TimerService.state.value?.interval?.stage == Stage.REST }
                capture("rest-$theme")
                compose.runOnUiThread { TimerService.command(context, "stop") }
                compose.waitUntil(5000) { TimerService.state.value?.status == Status.STOPPED }
                TimerService.clearResult()
                compose.waitForIdle()
            }
        } finally {
            if (
                TimerService.state.value?.status in
                    listOf(Status.RUNNING, Status.PAUSED, Status.INTERRUPTED)
            ) {
                compose.runOnUiThread { TimerService.command(context, "stop") }
                compose.waitUntil(5000) { TimerService.state.value?.status == Status.STOPPED }
            }
            TimerService.clearResult()
            kotlinx.coroutines.runBlocking { store.saveOptions(original) }
        }
    }

    @Test
    fun startPauseResumeAndStop() {
        capture("home")
        compose.onNodeWithText(text(R.string.quick)).performClick()
        compose.waitForIdle()
        capture("editor")
        compose.onNodeWithContentDescription("+ " + text(R.string.work)).performClick()
        compose.onNodeWithContentDescription(text(R.string.work)).assertTextContains("21")
        compose.onNodeWithContentDescription("− " + text(R.string.work)).performClick()
        compose.onNodeWithContentDescription(text(R.string.work)).assertTextContains("20")
        compose.onNodeWithText(text(R.string.start)).performClick()
        compose.waitUntil(10000) { TimerService.state.value?.status == Status.RUNNING }
        capture("workout")
        compose.onNodeWithText(text(R.string.pause)).performClick()
        compose.waitUntil(5000) { TimerService.state.value?.status == Status.PAUSED }
        compose.onNodeWithText(text(R.string.paused)).assertExists()
        capture("paused")
        compose.onNodeWithText(text(R.string.resume)).performClick()
        compose.waitUntil(5000) { TimerService.state.value?.status == Status.RUNNING }
        compose.onNodeWithText(text(R.string.stop)).performClick()
        compose.onNodeWithText(text(R.string.exit_title)).assertExists()
        compose.onAllNodesWithText(text(R.string.stop)).onLast().performClick()
        compose.waitUntil(5000) { TimerService.state.value?.status == Status.STOPPED }
        compose.onNodeWithText(text(R.string.stopped)).assertExists()
        capture("stopped")
        compose.onNodeWithText(text(R.string.home)).performClick()
        compose.onNodeWithText(text(R.string.app_name)).assertExists()
    }

    @Test
    fun editingPresetSurvivesActivityRecreation() {
        val store = Store(InstrumentationRegistry.getInstrumentation().targetContext)
        val before = kotlinx.coroutines.runBlocking { store.presets.first() }
        try {
            val preset = Preset(name = "Короткие интервалы", plan = Plan())
            kotlinx.coroutines.runBlocking { store.savePresets(listOf(preset)) }
            compose.waitUntil(10000) {
                compose.onAllNodesWithText(preset.name).fetchSemanticsNodes().isNotEmpty()
            }
            capture("saved")
            compose.onNodeWithText("•••").performClick()
            compose.onNodeWithText(text(R.string.edit)).performClick()
            compose
                .onAllNodes(hasSetTextAction())
                .onFirst()
                .performTextReplacement("Rotation edited")
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            compose.onNodeWithText(text(R.string.save)).performClick()
            compose.waitUntil(10000) {
                kotlinx.coroutines.runBlocking {
                    store.presets.first().singleOrNull()?.name == "Rotation edited"
                }
            }
            val saved = kotlinx.coroutines.runBlocking { store.presets.first() }
            org.junit.Assert.assertEquals(1, saved.size)
            org.junit.Assert.assertEquals(preset.id, saved.single().id)
        } finally {
            kotlinx.coroutines.runBlocking { store.savePresets(before) }
        }
    }
}
