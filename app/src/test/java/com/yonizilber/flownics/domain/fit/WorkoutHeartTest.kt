package com.yonizilber.flownics.domain.fit

import com.yonizilber.flownics.domain.model.SetLog
import com.yonizilber.flownics.domain.model.SetTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutHeartTest {

    private val t0 = 1_700_000_000_000L
    private fun at(seconds: Int) = t0 + seconds * 1000L

    private fun log(step: Int, start: Int?, end: Int, seconds: Int? = null, skipped: Boolean = false) =
        SetLog(1, step, 1, "Squat", 0, step, 1, null, SetTarget(reps = 8, seconds = seconds), skipped, at(end), start?.let(::at))

    @Test
    fun overlapIsTheShareOfTheShorter() {
        // A 40-minute workout inside a 45-minute recording: fully covered.
        assertEquals(1.0, WorkoutHeart.overlap(at(0), at(2700), at(120), at(2520)), 1e-9)
        // Half of the shorter one shared.
        assertEquals(0.5, WorkoutHeart.overlap(at(0), at(1000), at(500), at(1500)), 1e-9)
        assertEquals(0.0, WorkoutHeart.overlap(at(0), at(100), at(200), at(300)), 1e-9)
        assertTrue(WorkoutHeart.overlap(at(0), at(600), at(540), at(1800)) < WorkoutHeart.MIN_OVERLAP)
    }

    @Test
    fun eachSetGetsItsOwnHeartRate() {
        // Rises during a set (60 → 150 over 30 s), falls after (to 110 a minute later).
        val heart = (0..200).map { s ->
            val bpm = when {
                s in 10..40 -> 60 + (s - 10) * 3
                s in 41..100 -> 150 - (s - 40) * 40 / 60
                else -> 60
            }
            HeartPoint(at(s), bpm)
        }
        val sets = WorkoutHeart.sets(listOf(log(0, start = 10, end = 40), log(1, start = null, end = 160, seconds = 20), log(2, 170, 180, skipped = true)), heart)
        assertEquals(2, sets.size)
        val first = sets[0]
        assertEquals(0, first.stepIndex)
        assertEquals(150, first.max)
        assertEquals(105, first.avg)
        assertEquals(110, first.afterMinute)
        assertEquals(40, first.drop)
        // No start kept: the timed length is used. The minute after is past the recording.
        assertEquals(60, sets[1].max)
        assertNull(sets[1].afterMinute)
    }
}
