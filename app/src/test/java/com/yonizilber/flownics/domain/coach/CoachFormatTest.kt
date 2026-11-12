package com.yonizilber.flownics.domain.coach

import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.MeasureMode
import com.yonizilber.flownics.domain.model.Muscle
import com.yonizilber.flownics.domain.model.SessionHeader
import com.yonizilber.flownics.domain.model.SetLog
import com.yonizilber.flownics.domain.model.SetTarget
import com.yonizilber.flownics.domain.workout.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CoachFormatTest {

    private val zone = ZoneId.of("Asia/Jerusalem")
    private val squat = Exercise(id = 1, name = "Squat", category = ExerciseCategory.STRENGTH, primaryMuscles = setOf(Muscle.QUADS), measureMode = MeasureMode.REPS_WEIGHT)
    private val plank = Exercise(id = 2, name = "Plank", category = ExerciseCategory.CORE, measureMode = MeasureMode.TIME)
    private val library = mapOf(1L to squat, 2L to plank)

    private fun millis(date: String, hour: Int = 18) = LocalDate.parse(date).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun log(session: Long, step: Int, exercise: Long, actual: SetTarget, skipped: Boolean = false, side: Side? = null) =
        SetLog(session, step, exercise, library[exercise]?.name ?: "Gone", 0, step, 1, side, actual, skipped, 0)

    @Test
    fun setsAreListedOneByOneNotAveraged() {
        val pyramid = listOf(
            log(1, 0, 1, SetTarget(reps = 8, weightKg = 60.0)),
            log(1, 1, 1, SetTarget(reps = 8, weightKg = 60.0)),
            log(1, 2, 1, SetTarget(reps = 6, weightKg = 70.0)),
            log(1, 3, 1, SetTarget(reps = 3, weightKg = 77.5)),
            log(1, 4, 1, SetTarget(reps = 8, weightKg = 60.0), skipped = true),
        )
        assertEquals("8×60 kg ×2, 6×70 kg, 3×77.5 kg (1 skipped)", CoachFormat.setsLine(pyramid))
    }

    @Test
    fun sidesAndTimedSetsStayApart() {
        val sets = listOf(
            log(1, 0, 2, SetTarget(seconds = 45), side = Side.LEFT),
            log(1, 1, 2, SetTarget(seconds = 40), side = Side.RIGHT),
        )
        assertEquals("45 s (L), 40 s (R)", CoachFormat.setsLine(sets))
    }

    @Test
    fun trainingLogKeepsToTheRangeAndFilters() {
        val sessions = listOf(
            SessionHeader(1, null, "Legs A", millis("2026-11-02"), millis("2026-11-02") + 3_000_000, "knee ok"),
            SessionHeader(2, null, "Core", millis("2026-11-05"), millis("2026-11-05") + 1_200_000, ""),
            SessionHeader(3, null, "Legs B", millis("2026-10-20"), millis("2026-10-20") + 3_000_000, ""),
        )
        val logs = listOf(
            log(1, 0, 1, SetTarget(reps = 5, weightKg = 80.0)),
            log(2, 0, 2, SetTarget(seconds = 60)),
            log(3, 0, 1, SetTarget(reps = 5, weightKg = 75.0)),
        )
        val all = CoachFormat.trainingLog(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-10"), null, sessions, logs, library, zone)
        assertTrue(all.startsWith("Workouts from 2026-11-01 to 2026-11-10: 2."))
        // Newest first, notes included, the October workout left out.
        assertTrue(all.indexOf("Core") < all.indexOf("Legs A"))
        assertTrue("Notes: knee ok" in all)
        assertFalse("Legs B" in all)

        val squatOnly = CoachFormat.trainingLog(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-11-10"), 1, sessions, logs, library, zone)
        assertTrue(squatOnly.startsWith("Workouts with Squat from 2026-10-01 to 2026-11-10: 2."))
        assertTrue("Record heaviest weight (kg): 80" in squatOnly)
        assertFalse("Plank" in squatOnly)
    }

    @Test
    fun anIntervalRunShowsEveryPartNotAnAverage() {
        val run = com.yonizilber.flownics.domain.model.Activity(
            id = 9,
            type = com.yonizilber.flownics.domain.model.ActivityType.RUN,
            title = "",
            startedAtMillis = millis("2026-11-10"),
            durationSeconds = 1500,
            distanceMeters = 4000,
            avgHeartRate = 155,
            rpe = 8,
            segments = listOf(
                com.yonizilber.flownics.domain.model.ActivitySegment(com.yonizilber.flownics.domain.model.SegmentKind.WORK, 260, 1000, 170),
                com.yonizilber.flownics.domain.model.ActivitySegment(com.yonizilber.flownics.domain.model.SegmentKind.RECOVERY, 120, null, 140),
                com.yonizilber.flownics.domain.model.ActivitySegment(com.yonizilber.flownics.domain.model.SegmentKind.WORK, 275, 1000, 174),
            ),
        )
        val text = CoachFormat.trainingLog(LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-10"), null, emptyList(), emptyList(), library, zone, activities = listOf(run))
        assertTrue("Activities: 1." in text)
        assertTrue("Run (RUN, activity 9), 4 km in 25:00 (6:15/km), avg HR 155, felt 8/10" in text)
        assertTrue("1. work: 1 km, 4:20, 4:20/km, HR 170" in text)
        assertTrue("3. work: 1 km, 4:35, 4:35/km, HR 174" in text)
        // Filtering by an exercise leaves activities out.
        val squat = CoachFormat.trainingLog(LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-10"), 1, emptyList(), emptyList(), library, zone, activities = listOf(run))
        assertFalse("activity 9" in squat)
    }

    @Test
    fun aLongRangeSaysWhatWasLeftOut() {
        val sessions = (1..5L).map { SessionHeader(it, null, "W$it", millis("2026-11-0$it"), millis("2026-11-0$it") + 600_000, "") }
        val text = CoachFormat.trainingLog(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-09"), null, sessions, emptyList(), library, zone, limit = 2)
        assertTrue("Showing the newest 2 of 5" in text)
        assertTrue("W5" in text && "W4" in text && "W3" !in text)
    }

    @Test
    fun statusTellsTodayProfileAndMemory() {
        val text = CoachFormat.status(
            nowMillis = millis("2026-11-10", 9),
            zone = zone,
            profile = CoachProfile(goals = "10 km under 50 min"),
            notes = listOf(CoachNote(7, "Prefers evening runs", 0)),
            sessions = emptyList(),
            logs = emptyList(),
            library = library,
            workouts = emptyList(),
            estimatedMinutes = { 0 },
        )
        assertTrue(text.startsWith("Now: 2026-11-10 (Tuesday) 09:00, time zone Asia/Jerusalem."))
        assertTrue("- Goals: 10 km under 50 min" in text)
        assertTrue("- 7: Prefers evening runs" in text)
        assertTrue("Workouts done in the last 14 days: 0." in text)
    }
}
