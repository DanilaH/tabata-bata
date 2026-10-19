package ru.tabatabata

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.*
import org.junit.Test

/** Regression acceptance for the independent audit findings. */
class AuditStoreProbe {
    @Test fun capAndUndoAndImport() = runBlocking {
        val store = Store(InstrumentationRegistry.getInstrumentation().targetContext)
        val before = store.presets.first()
        try {
            val list = (0 until 500).map { Preset("audit-$it", "Audit $it", Plan()) }
            store.savePresets(list)
            // UI-equivalent sequence: 500 -> delete -> copy another -> undo delete.
            store.remove(list.first().id)
            store.updatePreset(Preset("replacement", "Replacement", Plan()))
            assertEquals(500, store.presets.first().size)
            assertEquals(PresetWrite.LIMIT, store.updatePreset(list.first()))
            assertEquals(500, store.presets.first().size)
            // Competing additions both pass a stale UI count; only one may commit.
            store.savePresets(list.take(499))
            val results = coroutineScope {
                listOf("race-a", "race-b").map { id ->
                    async { store.updatePreset(Preset(id, id, Plan())) }
                }.awaitAll()
            }
            assertEquals(1, results.count { it == PresetWrite.SAVED })
            assertEquals(1, results.count { it == PresetWrite.LIMIT })
            assertEquals(500, store.presets.first().size)
            val legacy = list + Preset("legacy-501", "Legacy", Plan())
            assertEquals(501, store.parsePresets(store.encode(legacy).toString(), false).size)
            assertTrue(runCatching { store.parsePresets(store.encode(listOf(list[0], list[0])).toString(), false) }.isFailure)
            store.savePresets(list.take(3))
            val removed = requireNotNull(store.remove(list.first().id))
            assertEquals(PresetWrite.SAVED, store.restore(removed))
            println("AUDIT undo_order=${store.presets.first().map { it.id }}")
            assertEquals(listOf("audit-0", "audit-1", "audit-2"), store.presets.first().map { it.id })
            val rejected = listOf("", "{}", "[]", "null", "{broken", "x".repeat(1_000_001),
                store.export(listOf(list.first().copy(plan = Plan(work = 0)))),
                store.export(listOf(list.first().copy(name = ""))),
                store.export(list)).count { runCatching { store.import(it) }.isFailure }
            // The last case is a valid 500-mode export and must import successfully.
            assertEquals(8, rejected)
            println("AUDIT malformed_imports_rejected=$rejected valid500_accepted=true")
        } finally { store.savePresets(before) }
    }
}
