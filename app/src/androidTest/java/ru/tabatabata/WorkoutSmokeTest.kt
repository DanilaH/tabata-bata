package ru.tabatabata

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.rule.GrantPermissionRule
import java.io.File
import org.junit.Rule
import org.junit.Test

class WorkoutSmokeTest {
    @get:Rule(order = 0)
    val permission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun text(id: Int) = compose.activity.getString(id)

    private fun capture(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(compose.activity.filesDir, "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun startPauseResumeAndStop() {
        capture("home")
        compose.onNodeWithText(text(R.string.quick)).performClick()
        compose.waitForIdle()
        capture("editor")
        compose.onNodeWithText(text(R.string.start)).performScrollTo().performClick()
        compose.waitUntil(10000) { TimerService.state.value?.status == Status.RUNNING }
        capture("workout")
        compose.onNodeWithText(text(R.string.pause)).performClick()
        compose.waitUntil(5000) { TimerService.state.value?.status == Status.PAUSED }
        compose.onNodeWithText(text(R.string.paused)).assertExists()
        capture("paused")
        compose.onNodeWithText(text(R.string.resume)).performClick()
        compose.waitUntil(5000) { TimerService.state.value?.status == Status.RUNNING }
        compose.onNodeWithText(text(R.string.stop)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.exit_title)).assertExists()
        compose.onAllNodesWithText(text(R.string.stop)).onLast().performClick()
        compose.waitUntil(5000) { TimerService.state.value?.status == Status.STOPPED }
        compose.onNodeWithText(text(R.string.stopped)).assertExists()
        capture("stopped")
        compose.onNodeWithText(text(R.string.home)).performClick()
        compose.onNodeWithText(text(R.string.app_name)).assertExists()
    }
}
