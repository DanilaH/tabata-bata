package ru.tabatabata

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RestControlsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun restExtensionPauseAndLock() {
        val c = compose.activity
        compose.runOnIdle {
            TimerService.clearResult()
            TimerService.command(c, "start", Plan(0, 30, 30, 2))
        }
        compose.waitUntil(5000) { TimerService.state.value?.status == Status.RUNNING }
        compose.runOnIdle {
            TimerService.command(c, "next")
            TimerService.command(c, "pause")
        }
        compose.waitUntil(5000) { TimerService.state.value?.status == Status.PAUSED }
        try {
            val before = TimerService.state.value!!.remaining
            compose.onNodeWithText(c.getString(R.string.add_rest)).performClick()
            compose.waitUntil(5000) { TimerService.state.value?.remaining == before + 10000 }
            compose.onNodeWithContentDescription(c.getString(R.string.lock_short)).performClick()
            compose.onNodeWithText(c.getString(R.string.add_rest)).assertIsNotEnabled()
        } finally {
            compose.runOnIdle { TimerService.command(c, "stop") }
            compose.waitUntil(5000) { TimerService.state.value?.status == Status.STOPPED }
            compose.runOnIdle { TimerService.clearResult() }
        }
    }

    @Test
    fun voiceDefaultsAndLastModePersist() = runBlocking {
        val store = Store(compose.activity)
        val options = store.options.first()
        val last = store.lastPresetId.first()
        try {
            store.saveOptions(options.copy(voice = true))
            assertTrue(Store(compose.activity).options.first().voice)
            store.saveLastPreset("test-last")
            assertEquals("test-last", Store(compose.activity).lastPresetId.first())
        } finally {
            store.saveOptions(options)
            store.saveLastPreset(last ?: "")
        }
    }
}
