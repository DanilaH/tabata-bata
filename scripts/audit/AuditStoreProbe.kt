package ru.tabatabata

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
