package ru.tabatabata

import org.junit.Assert.*
import org.junit.Test

class SupportReportTest {
    private val device=SupportDevice("0.10.0",19,"16",36,"Example","Phone","ru-RU")
    @Test fun onlyTheExplicitDescriptionAndAllowlistedTechnicalDataAreIncluded() {
        assertEquals("Проблема\n\n---\nTabata Bata 0.10.0 (19)\nAndroid 16 · API 36\nExample Phone\nLocale: ru-RU",
            supportReport("  Проблема  ",device))
    }
    @Test fun oversizedDescriptionIsBoundedAndEmptyOneStillHasDeviceData() {
        assertEquals(2000,supportReport("a".repeat(3000),device).substringBefore("\n\n---").length)
        assertTrue(supportReport("",device).endsWith(device.technicalText()))
    }
}
