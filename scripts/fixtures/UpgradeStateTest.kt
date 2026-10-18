package ru.tabatabata

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Compiled against both pinned 0.5.1 and current code. Never installs real SDK IDs. */
class UpgradeStateTest {
    @Test fun stateAcrossUpgrade() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = Store(context)
        val plan = Plan(7, 37, 13, 9, 2, 47, 11, true)
        val presets = listOf(
            Preset("upgrade-a", "Upgrade 37-13", plan),
            Preset("upgrade-b", "Второй режим", Plan(work = 43, reps = 3)),
        )
        val options = Options(volume = .35f, vibrate = false, countdown = false,
            keepScreen = false, duckMusic = true, theme = "dark", showStages = true, voice = false)
        val checkpoint = JSONObject().put("plan", plan.json()).put("index", 1)
            .put("remaining", 12345).put("sessionId", "upgrade-session").toString()
        val session = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        if (InstrumentationRegistry.getArguments().getString("phase") == "seed") {
            store.savePresets(presets)
            store.saveOptions(options)
            store.saveLastPlan(plan)
            store.saveLastPreset("upgrade-b")
            session.edit().putString("active", checkpoint).commit()
        }
        assertEquals(presets, store.presets.first())
        assertEquals(options, store.options.first())
        assertEquals(plan, store.lastPlan.first())
        assertEquals("upgrade-b", store.lastPresetId.first())
        assertEquals(checkpoint, session.getString("active", null))
        assertNull(TimerService.state.value)
    }
}
