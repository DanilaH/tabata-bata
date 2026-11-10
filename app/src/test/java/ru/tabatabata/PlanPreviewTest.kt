package ru.tabatabata

import org.junit.Assert.*
import org.junit.Test

class PlanPreviewTest {
    @Test fun classicPreviewHasAllTimeAndNoFinalRest() {
        val result = overview(Plan())
        assertEquals(235_000L, result.duration)
        assertEquals(160_000L, result.byStage[Stage.WORK])
        assertEquals(70_000L, result.byStage[Stage.REST])
        assertEquals(5_000L, result.byStage[Stage.PREPARE])
        assertEquals(16, result.blocks.sumOf { it.steps.size })
        assertEquals(Stage.WORK, result.blocks.last().steps.last().interval.stage)
    }

    @Test fun multipleSetsKeepBetweenRestAndFinalRestSeparate() {
        val result = overview(Plan(13,20,10,8,3,60,30,true))
        assertEquals(863_000L, result.duration)
        assertEquals(listOf(13_000L,230_000L,60_000L,230_000L,60_000L,240_000L,30_000L),
            result.blocks.map { it.duration })
        assertEquals(833_000L, result.blocks.last().steps.single().startsAt)
        assertEquals(480_000L, result.byStage[Stage.WORK])
        assertEquals(220_000L, result.byStage[Stage.REST])
        assertEquals(120_000L, result.byStage[Stage.BETWEEN])
    }

    @Test fun zeroOptionalStagesAndOneRoundNeedNoEmptyBlocks() {
        val result = overview(Plan(0,1,0,1,1,0,0,false))
        assertEquals(1, result.blocks.size)
        assertEquals(1_000L, result.duration)
        assertEquals(0L, result.blocks.single().steps.single().startsAt)
    }

    @Test fun legalPlansHaveContiguousOffsetsAndExactTotal() {
        val random = java.util.Random(17)
        repeat(1000) {
            val plan = Plan(random.nextInt(60),1+random.nextInt(120),random.nextInt(60),
                1+random.nextInt(20),1+random.nextInt(5),random.nextInt(90),random.nextInt(60),random.nextBoolean())
            val result = overview(plan)
            val steps = result.blocks.flatMap { it.steps }
            assertEquals(0L, steps.first().startsAt)
            steps.zipWithNext().forEach { (a,b) -> assertEquals(a.startsAt + a.interval.duration, b.startsAt) }
            assertEquals(plan.durationMs(), result.duration)
            assertEquals(plan.durationMs(), steps.last().startsAt + steps.last().interval.duration)
        }
    }

    @Test fun maximumLegalRepetitionsRemainGrouped() {
        val result = overview(Plan(0,1,0,200,20,0,0,false))
        assertEquals(20, result.blocks.size)
        assertEquals(4000, result.blocks.sumOf { it.steps.size })
        assertEquals(4_000_000L,result.duration)
    }
}
