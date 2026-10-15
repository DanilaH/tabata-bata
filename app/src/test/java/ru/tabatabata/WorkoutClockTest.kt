package ru.tabatabata

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutClockTest {
    @Test
    fun countdownBoundaries() {
        assertEquals("0", workoutClock(-1))
        assertEquals("1", workoutClock(1))
        assertEquals("8", workoutClock(8000))
        assertEquals("59", workoutClock(59000))
        assertEquals("1:00", workoutClock(59999))
        assertEquals("1:20", workoutClock(80000))
        assertEquals("1:00:00", workoutClock(3600000))
    }
}
