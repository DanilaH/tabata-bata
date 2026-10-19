package ru.tabatabata

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Audit probes describe current behaviour; NOT regression acceptance tests. */
class AuditStoreProbe {
    @Test fun capAndUndoAndImport() = runBlocking {
        val store = Store(InstrumentationRegistry.getInstrumentation().targetContext)
        val before = store.presets.first()
        try {
            val list = (0 until 500).map { Preset("audit-$it", "Audit $it", Plan()) }
            store.savePresets(list)
            store.updatePreset(Preset("overflow", "Overflow", Plan()))
            val failure = runCatching { store.presets.first() }.exceptionOrNull()
            println("AUDIT cap501_read_failure=${failure?.javaClass?.simpleName}")
            assertNotNull("Reproduces unguarded 501st write", failure)
            store.savePresets(list.take(3))
            store.remove(list.first().id)
            store.updatePreset(list.first())
            println("AUDIT undo_order=${store.presets.first().map { it.id }}")
            assertEquals(listOf("audit-1", "audit-2", "audit-0"), store.presets.first().map { it.id })
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
