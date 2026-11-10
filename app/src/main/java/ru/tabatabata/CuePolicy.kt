package ru.tabatabata

/** Suppress stale midpoint cues after a jump, recovery or a long scheduling delay. */
internal fun crossedHalfway(stage: Stage?, duration: Long, previous: Long, remaining: Long): Boolean =
    stage == Stage.WORK && duration >= 10_000L && previous > duration / 2 &&
        remaining <= duration / 2 && remaining >= duration / 2 - 750L
