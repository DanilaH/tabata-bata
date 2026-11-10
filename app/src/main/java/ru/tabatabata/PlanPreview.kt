package ru.tabatabata

/** Preview uses the exact compiled intervals executed by TimerEngine. Times are milliseconds. */
internal data class PreviewStep(val interval: Interval, val startsAt: Long)
internal data class PreviewBlock(val set: Int, val steps: List<PreviewStep>) {
    val duration: Long get() = steps.sumOf { it.interval.duration }
}
internal data class PlanOverview(val blocks: List<PreviewBlock>, val byStage: Map<Stage, Long>) {
    val duration: Long get() = byStage.values.sum()
}

internal fun overview(plan: Plan): PlanOverview {
    plan.validate()
    var elapsed = 0L
    val steps = plan.stages().map { interval ->
        PreviewStep(interval, elapsed).also { elapsed += interval.duration }
    }
    val blocks = mutableListOf<PreviewBlock>()
    for (step in steps) {
        val grouped = step.interval.stage in listOf(Stage.WORK, Stage.REST)
        val last = blocks.lastOrNull()
        if (grouped && last != null && last.set == step.interval.set &&
            last.steps.last().interval.stage in listOf(Stage.WORK, Stage.REST)) {
            blocks[blocks.lastIndex] = last.copy(steps = last.steps + step)
        } else blocks.add(PreviewBlock(if (grouped) step.interval.set else 0, listOf(step)))
    }
    return PlanOverview(blocks, steps.groupBy { it.interval.stage }
        .mapValues { (_, items) -> items.sumOf { it.interval.duration } })
}

