package ru.tabatabata

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Wall time labels calendar dates only. Active duration always comes from TimerEngine. */
internal data class WorkoutRecord(
    val sessionId: String,
    val plan: Plan,
    val name: String,
    val accent: String = "blue",
    val mark: Int = 0,
    val startedAt: Long? = null,
    val finishedAt: Long,
    val completed: Boolean,
    val activeMs: Long,
    val completedRounds: Int,
    val statsAvailable: Boolean = true,
) {
    fun validate() {
        plan.validate()
        require(sessionId.isNotBlank() && sessionId.length <= 100)
        require(name.length <= 100 && accent in presetAccents && mark in 0..3)
        require(finishedAt > 0 && (startedAt == null || startedAt > 0))
        require(activeMs >= 0 && completedRounds in 0..plan.reps * plan.sets)
    }

    fun repeatPreset() = Preset("history-$sessionId", name, plan, accent, mark)
}

internal data class WeekWindow(val start: Long, val end: Long)
internal fun currentWeek(now: Long, zone: ZoneId): WeekWindow {
    val monday = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return WeekWindow(monday.atStartOfDay(zone).toInstant().toEpochMilli(),
        monday.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli())
}

internal data class WeekSummary(
    val completed: Int = 0,
    val stopped: Int = 0,
    val activeMs: Long = 0,
    val incompleteStats: Int = 0,
) {
    val count get() = completed + stopped
}

internal fun summarizeWeek(records: List<WorkoutRecord>, window: WeekWindow): WeekSummary {
    val week = records.filter { it.finishedAt >= window.start && it.finishedAt < window.end }
    return WeekSummary(week.count { it.completed }, week.count { !it.completed },
        week.filter { it.statsAvailable }.sumOf { it.activeMs }, week.count { !it.statsAvailable })
}
