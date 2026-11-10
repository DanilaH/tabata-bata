package ru.tabatabata

import org.junit.Assert.*
import org.junit.Test

class CuePolicyTest {
    @Test fun midpointIsAnEdgeNotEveryPublish() {
        assertTrue(crossedHalfway(Stage.WORK,20_000,10_010,9_990))
        assertFalse(crossedHalfway(Stage.WORK,20_000,9_990,9_950))
    }
    @Test fun shortIntervalsAndRestDoNotEmitMidpoint() {
        assertFalse(crossedHalfway(Stage.WORK,9_000,4_550,4_450))
        assertFalse(crossedHalfway(Stage.REST,20_000,10_050,9_950))
        assertFalse(crossedHalfway(null,20_000,10_050,9_950))
        assertTrue(crossedHalfway(Stage.WORK,10_000,5_050,4_950))
    }
    @Test fun latePublishAndPrimedResumeDoNotPlayOldSignal() {
        assertFalse(crossedHalfway(Stage.WORK,20_000,11_000,8_000))
        assertFalse(crossedHalfway(Stage.WORK,20_000,9_990,9_990))
        assertFalse(crossedHalfway(Stage.WORK,20_000,10_010,10_010))
    }
}
