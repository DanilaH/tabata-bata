package ru.tabatabata

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StoreRoundTripTest {
    @Test
    fun persistedPresetsAndValidatedImport() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = Store(context)
        val before = store.presets.first()
        try {
            val preset = Preset(name = "Режим 20/10", plan = Plan(5, 2, 2, 25))
            store.savePresets(listOf(preset))
            assertEquals(listOf(preset), Store(context).presets.first())
            val imported = store.import(store.export(store.presets.first()))
            assertEquals(preset.name, imported.single().name)
            assertEquals(preset.plan, imported.single().plan)
            assertNotEquals(preset.id, imported.single().id)
            assertThrows(IllegalArgumentException::class.java) {
                store.import("""{"schema":2,"presets":[]}""")
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.import(store.export(listOf(preset.copy(plan = Plan(work = 0)))))
            }
            store.remove(preset.id)
            assertTrue(store.presets.first().isEmpty())
        } finally {
            store.savePresets(before)
        }
    }
}
