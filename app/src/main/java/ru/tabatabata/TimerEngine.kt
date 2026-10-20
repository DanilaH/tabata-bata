package ru.tabatabata

enum class Stage {
    PREPARE,
    WORK,
    REST,
    BETWEEN,
    COOL,
}

data class Plan(
    val prepare: Int = 5,
    val work: Int = 20,
    val rest: Int = 10,
    val reps: Int = 8,
    val sets: Int = 1,
    val between: Int = 60,
    val cool: Int = 0,
    val lastRest: Boolean = false,
) {
    fun validate() {
        require(
            prepare in 0..3600 &&
                work in 1..3600 &&
                rest in 0..3600 &&
                reps in 1..200 &&
                sets in 1..20 &&
                between in 0..3600 &&
                cool in 0..3600
        )
        require(durationMs() <= 24 * 60 * 60 * 1000L)
    }

    fun stages(): List<Interval> = buildList {
        fun stage(type: Stage, seconds: Int, rep: Int = 0, set: Int = 0) {
            if (seconds > 0) add(Interval(type, seconds * 1000L, rep, set))
        }
        stage(Stage.PREPARE, prepare)
        for (s in 1..sets) {
            for (r in 1..reps) {
                stage(Stage.WORK, work, r, s)
                if (r < reps || (s == sets && lastRest)) stage(Stage.REST, rest, r, s)
            }
            if (s < sets) stage(Stage.BETWEEN, between, reps, s)
        }
        stage(Stage.COOL, cool)
    }

    fun durationMs(): Long =
        prepare * 1000L +
            sets * (reps * work * 1000L + (reps - 1) * rest * 1000L) +
            (sets - 1) * between * 1000L +
            cool * 1000L +
            if (lastRest) rest * 1000L else 0L
}

data class Interval(val stage: Stage, val duration: Long, val rep: Int, val set: Int)

enum class Status {
    RUNNING,
    PAUSED,
    COMPLETED,
    STOPPED,
    INTERRUPTED,
}

data class Snapshot(
    val status: Status,
    val index: Int,
    val remaining: Long,
    val totalRemaining: Long,
    val interval: Interval?,
    val next: Interval?,
    val plan: Plan,
    val sessionId: String,
    val activeMs: Long = 0,
    val completedWork: Set<Int> = emptySet(),
    val statsAvailable: Boolean = true,
)

/** All time is injected from a monotonic clock. Rendering never advances the timer. */
class TimerEngine(
    val plan: Plan,
    now: Long,
    val sessionId: String = java.util.UUID.randomUUID().toString(),
) {
    init { plan.validate() }

    private val intervals = plan.stages().toMutableList()
    private var index = 0
    private var deadline = now + intervals.first().duration
    private var pausedRemaining = intervals.first().duration
    private var status = Status.RUNNING
    private var measuredAt = now
    private var activeMs = 0L
    private val completedWork = mutableSetOf<Int>()
    private var statsAvailable = true

    fun snapshot(now: Long): Snapshot {
        if (status == Status.RUNNING) {
            val finishAt = deadline + intervals.drop(index + 1).sumOf { it.duration }
            activeMs += (minOf(now, finishAt) - measuredAt).coerceAtLeast(0)
            measuredAt = now
            while (index < intervals.size && now >= deadline) {
                if (intervals[index].stage == Stage.WORK) completedWork.add(index)
                index++
                if (index < intervals.size) deadline += intervals[index].duration
                else status = Status.COMPLETED
            }
        }
        val current = intervals.getOrNull(index)
        val left =
            when (status) {
                Status.RUNNING -> (deadline - now).coerceAtLeast(0)
                Status.PAUSED,
                Status.INTERRUPTED -> pausedRemaining
                else -> 0
            }
        return Snapshot(
            status,
            index,
            left,
            if (current == null || status == Status.STOPPED) 0
            else left + intervals.drop(index + 1).sumOf { it.duration },
            current,
            intervals.getOrNull(index + 1),
            plan,
            sessionId,
            activeMs,
            completedWork.toSet(),
            statsAvailable,
        )
    }

    fun pause(now: Long) {
        val s = snapshot(now)
        if (status == Status.RUNNING) {
            pausedRemaining = s.remaining
            status = Status.PAUSED
        }
    }

    fun resume(now: Long) {
        if (status == Status.PAUSED || status == Status.INTERRUPTED) {
            deadline = now + pausedRemaining
            measuredAt = now
            status = Status.RUNNING
        }
    }

    fun skip(delta: Int, now: Long) {
        snapshot(now)
        if (status != Status.RUNNING && status != Status.PAUSED && status != Status.INTERRUPTED)
            return
        if (status == Status.INTERRUPTED) status = Status.PAUSED
        index = (index + delta).coerceIn(0, intervals.size)
        if (index == intervals.size) {
            status = Status.COMPLETED
            return
        }
        pausedRemaining = intervals[index].duration
        deadline = now + pausedRemaining
    }

    /** Extends only the currently visible rest; never mutates the saved plan. */
    fun extendRest(now: Long): Boolean {
        val s = snapshot(now)
        if (
            s.status !in listOf(Status.RUNNING, Status.PAUSED) ||
                s.interval?.stage !in listOf(Stage.REST, Stage.BETWEEN)
        )
            return false
        intervals[index] = intervals[index].copy(duration = intervals[index].duration + 10_000L)
        if (status == Status.RUNNING) deadline += 10_000L else pausedRemaining += 10_000L
        return true
    }

    fun stop(now: Long? = null) {
        if (now != null) snapshot(now)
        status = Status.STOPPED
    }

    fun restore(
        index: Int,
        remaining: Long,
        activeMs: Long = 0,
        completed: Set<Int> = emptySet(),
        statsAvailable: Boolean = true,
        currentDuration: Long? = null,
    ) {
        this.activeMs = activeMs.coerceAtLeast(0)
        this.completedWork.clear()
        this.completedWork.addAll(completed.filter { intervals.getOrNull(it)?.stage == Stage.WORK })
        this.statsAvailable = statsAvailable
        this.index = index.coerceIn(0, intervals.lastIndex)
        if (
            intervals[this.index].stage in listOf(Stage.REST, Stage.BETWEEN) &&
                currentDuration != null
        ) {
            intervals[this.index] =
                intervals[this.index].copy(
                    duration =
                        currentDuration.coerceIn(intervals[this.index].duration, 7 * 86400000L)
                )
        }
        pausedRemaining = remaining.coerceIn(1, intervals[this.index].duration)
        status = Status.INTERRUPTED
    }
}
