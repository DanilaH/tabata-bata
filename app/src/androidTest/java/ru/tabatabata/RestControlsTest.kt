package ru.tabatabata

import androidx.compose.ui.test.*
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RestControlsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun clockStaysAtSameHeightAcrossPrepareWorkAndRest() {
        val plan = Plan(prepare = 5, work = 20, rest = 10, reps = 2)
        val engine = TimerEngine(plan, 0)
        val state = mutableStateOf(engine.snapshot(0))
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme { Workout(state.value, Options(), {}, {}) }
            }
        }
        val prepare = compose.onNodeWithTag("workout-clock").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText(compose.activity.getString(R.string.add_rest)).assertDoesNotExist()
        compose.runOnIdle { engine.skip(1, 0); state.value = engine.snapshot(0) }
        val work = compose.onNodeWithTag("workout-clock").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { engine.skip(1, 0); state.value = engine.snapshot(0) }
        compose.onNodeWithText(compose.activity.getString(R.string.add_rest)).assertIsDisplayed()
        val rest = compose.onNodeWithTag("workout-clock").fetchSemanticsNode().boundsInRoot
        assertEquals(prepare.top, work.top, 1f)
        assertEquals(work.top, rest.top, 1f)
        assertEquals(work.bottom, rest.bottom, 1f)
        compose.runOnIdle { engine.pause(0); state.value = engine.snapshot(0) }
        val paused = compose.onNodeWithTag("workout-clock").fetchSemanticsNode().boundsInRoot
        assertEquals(rest.top, paused.top, 1f)
        assertEquals(rest.bottom, paused.bottom, 1f)
    }

    @Test
    fun clockStableThroughAllSeriesAndFinalStageWithEitherQueueSetting() {
        val plan = Plan(prepare = 5, work = 20, rest = 10, reps = 2,
            sets = 2, between = 20, cool = 5)
        val state = mutableStateOf(TimerEngine(plan, 0).snapshot(0))
        val options = mutableStateOf(Options())
        compose.runOnUiThread {
            compose.activity.setContent { MaterialTheme { Workout(state.value, options.value, {}, {}) } }
        }
        for (queue in listOf(false, true)) {
            val engine = TimerEngine(plan, 0)
            compose.runOnIdle { options.value = Options(showStages = queue); state.value = engine.snapshot(0) }
            val initial = compose.onNodeWithTag("workout-clock").fetchSemanticsNode().boundsInRoot
            repeat(plan.stages().size - 1) {
                compose.runOnIdle { engine.skip(1, 0); state.value = engine.snapshot(0) }
                val bounds = compose.onNodeWithTag("workout-clock").fetchSemanticsNode().boundsInRoot
                assertEquals("Stage ${state.value.index}, queue=$queue", initial.top, bounds.top, 1f)
                assertEquals(initial.bottom, bounds.bottom, 1f)
            }
        }
    }

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
            compose.waitUntil(5000) { TimerService.state.value == null || TimerService.state.value?.status == Status.STOPPED }
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
