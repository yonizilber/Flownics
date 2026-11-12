package com.yonizilber.flownics.domain.plan

import com.yonizilber.flownics.domain.model.Activity
import com.yonizilber.flownics.domain.model.ActivitySegment
import com.yonizilber.flownics.domain.model.ActivityType
import com.yonizilber.flownics.domain.model.PlanKind
import com.yonizilber.flownics.domain.model.PlannedStep
import com.yonizilber.flownics.domain.model.PlannedWorkout
import com.yonizilber.flownics.domain.model.SegmentKind
import com.yonizilber.flownics.domain.model.SessionHeader
import com.yonizilber.flownics.feature.plan.PlanEditViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PlanMatchingTest {

    private val zone = ZoneId.of("Asia/Jerusalem")
    private val day = LocalDate.parse("2026-11-11")
    private fun at(hour: Int, date: LocalDate = day) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private val runPlan = PlannedWorkout(id = 1, date = day, kind = PlanKind.ACTIVITY, activityType = ActivityType.RUN)
    private val legPlan = PlannedWorkout(id = 2, date = day, kind = PlanKind.WORKOUT, workoutId = 7)

    @Test
    fun plansMatchWhatWasDoneThatDay() {
        val run = Activity(id = 10, type = ActivityType.RUN, title = "", startedAtMillis = at(18), durationSeconds = 1800)
        val ride = Activity(id = 11, type = ActivityType.RIDE, title = "", startedAtMillis = at(8), durationSeconds = 1800)
        val legs = SessionHeader(20, 7, "Legs", at(7), at(8), "")
        val matched = PlanMatching.match(listOf(runPlan, legPlan), listOf(legs), listOf(ride, run), zone)
        assertEquals(DoneItem.Logged(run), matched[1])
        assertEquals(DoneItem.Workout(legs), matched[2])
    }

    @Test
    fun theWrongDayOrTypeOrWorkoutIsNotAMatch() {
        val yesterday = Activity(id = 10, type = ActivityType.RUN, title = "", startedAtMillis = at(18, day.minusDays(1)), durationSeconds = 1800)
        val otherWorkout = SessionHeader(20, 8, "Back", at(7), at(8), "")
        val matched = PlanMatching.match(listOf(runPlan, legPlan), listOf(otherWorkout), listOf(yesterday), zone)
        assertNull(matched[1])
        assertNull(matched[2])
    }

    @Test
    fun oneRunFulfilsOnlyOnePlan() {
        val second = runPlan.copy(id = 3)
        val run = Activity(id = 10, type = ActivityType.RUN, title = "", startedAtMillis = at(18), durationSeconds = 1800)
        assertEquals(1, PlanMatching.match(listOf(runPlan, second), emptyList(), listOf(run), zone).size)
    }

    @Test
    fun eachRepeatIsComparedWithItsTarget() {
        val work = PlannedStep(SegmentKind.WORK, distanceMeters = 1000, paceFastSecondsPerKm = 260, paceSlowSecondsPerKm = 270, maxHeartRate = 175)
        val steps = listOf(PlannedStep(SegmentKind.WARMUP, durationSeconds = 600), work, PlannedStep(SegmentKind.RECOVERY, durationSeconds = 120), work, work)
        val done = listOf(
            ActivitySegment(SegmentKind.WARMUP, 600, 1700),
            ActivitySegment(SegmentKind.WORK, 265, 1000, 170),
            ActivitySegment(SegmentKind.RECOVERY, 120),
            ActivitySegment(SegmentKind.WORK, 285, 1000, 172),
            // The 3rd repeat was skipped.
        )
        val rows = PlanMatching.compare(steps, done)
        assertEquals(
            listOf(StepVerdict.NO_TARGET, StepVerdict.OK, StepVerdict.NO_TARGET, StepVerdict.SLOWER, StepVerdict.NOT_DONE),
            rows.map { it.verdict },
        )
        val hot = PlanMatching.compare(listOf(work), listOf(ActivitySegment(SegmentKind.WORK, 262, 1000, 181)))
        assertEquals(StepVerdict.HR_HIGH, hot.single().verdict)
        // Within a few seconds of the range counts as on target.
        val close = PlanMatching.compare(listOf(work), listOf(ActivitySegment(SegmentKind.WORK, 258, 1000)))
        assertEquals(StepVerdict.OK, close.single().verdict)
    }

    @Test
    fun paceRangesAsTyped() {
        assertEquals(260 to 270, PlanEditViewModel.parsePaceRange("4:20-4:30"))
        assertEquals(260 to 270, PlanEditViewModel.parsePaceRange("4:30 – 4:20"))
        assertEquals(270 to 270, PlanEditViewModel.parsePaceRange("4:30"))
        assertNull(PlanEditViewModel.parsePaceRange("fast"))
        assertTrue(PlanEditViewModel.parsePaceRange("0:30") == null)
    }
}
