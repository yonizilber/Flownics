package com.yonizilber.flownics.domain.player

import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.ExerciseStage
import com.yonizilber.flownics.domain.model.SetLog
import com.yonizilber.flownics.domain.model.SetTarget
import com.yonizilber.flownics.domain.workout.RestStep
import com.yonizilber.flownics.domain.workout.Side
import com.yonizilber.flownics.domain.workout.WorkStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutPlayerTest {

    private fun reps(set: Int, reps: Int = 10, weight: Double? = null) = WorkStep(
        blockIndex = 0, itemIndex = 0, exerciseId = 1, set = set, totalSets = 3, round = 1, totalRounds = 1,
        target = SetTarget(reps = reps, weightKg = weight), side = null, durationSeconds = null,
    )

    private fun timed(seconds: Int, side: Side? = null) = WorkStep(
        blockIndex = 0, itemIndex = 1, exerciseId = 2, set = 1, totalSets = 1, round = 1, totalRounds = 1,
        target = SetTarget(seconds = seconds), side = side, durationSeconds = seconds,
    )

    private fun rest(seconds: Int) = RestStep(blockIndex = 0, durationSeconds = seconds, activeExerciseId = null)

    @Test
    fun openEndedSetWaitsForTheUser() {
        val steps = listOf(reps(1), rest(60))
        val state = WorkoutPlayer.start(steps, nowMs = 1_000)
        assertEquals(PlayerState(0, Phase.Running(1_000, null)), state)
        assertEquals(state, WorkoutPlayer.tick(state, steps, nowMs = 999_999))
        assertNull(WorkoutPlayer.remainingMs(state.phase, 5_000))
    }

    @Test
    fun timedFirstStepGetsALeadIn() {
        val steps = listOf(timed(30))
        val state = WorkoutPlayer.start(steps, nowMs = 0)
        assertEquals(Phase.LeadIn(WorkoutPlayer.LEAD_IN_MS), state.phase)
        val running = WorkoutPlayer.tick(state, steps, nowMs = WorkoutPlayer.LEAD_IN_MS)
        assertEquals(Phase.Running(WorkoutPlayer.LEAD_IN_MS, WorkoutPlayer.LEAD_IN_MS + 30_000), running.phase)
    }

    @Test
    fun restEndsIntoTimedWorkWithoutLeadInAtTheExactDeadline() {
        val steps = listOf(reps(1), rest(60), timed(30))
        val inRest = WorkoutPlayer.advance(WorkoutPlayer.start(steps, 0), steps, nowMs = 10_000)
        assertEquals(PlayerState(1, Phase.Running(10_000, 70_000)), inRest)
        // The tick arrives 400 ms late; the timed set still starts exactly when the rest ended.
        val working = WorkoutPlayer.tick(inRest, steps, nowMs = 70_400)
        assertEquals(PlayerState(2, Phase.Running(70_000, 100_000)), working)
    }

    @Test
    fun switchingSidesGetsALeadIn() {
        val steps = listOf(timed(20, Side.LEFT), timed(20, Side.RIGHT))
        val left = WorkoutPlayer.tick(WorkoutPlayer.start(steps, 0), steps, WorkoutPlayer.LEAD_IN_MS)
        val right = WorkoutPlayer.tick(left, steps, WorkoutPlayer.LEAD_IN_MS + 20_000)
        assertEquals(1, right.index)
        assertTrue(right.phase is Phase.LeadIn)
    }

    @Test
    fun aLateTickCatchesUpThroughSeveralDeadlines() {
        val steps = listOf(rest(10), rest(10), reps(1))
        val state = WorkoutPlayer.start(steps, 0)
        val caughtUp = WorkoutPlayer.tick(state, steps, nowMs = 25_000)
        assertEquals(PlayerState(2, Phase.Running(20_000, null)), caughtUp)
    }

    @Test
    fun pauseFreezesAndResumeShiftsTheDeadline() {
        val steps = listOf(rest(60), reps(1))
        val running = WorkoutPlayer.start(steps, 0)
        val paused = WorkoutPlayer.pause(running, nowMs = 20_000)
        assertEquals(40_000L, WorkoutPlayer.remainingMs(paused.phase, nowMs = 500_000))
        assertEquals(paused, WorkoutPlayer.tick(paused, steps, nowMs = 500_000))
        val resumed = WorkoutPlayer.resume(paused, nowMs = 100_000)
        assertEquals(Phase.Running(80_000, 140_000), resumed.phase)
        assertEquals(40_000L, WorkoutPlayer.remainingMs(resumed.phase, 100_000))
    }

    @Test
    fun adjustAddsTimeButNeverEndsTheCountdownInstantly() {
        val steps = listOf(rest(60), reps(1))
        val running = WorkoutPlayer.start(steps, 0)
        assertEquals(Phase.Running(0, 75_000), WorkoutPlayer.adjust(running, 15, nowMs = 10_000).phase)
        assertEquals(Phase.Running(0, 51_000), WorkoutPlayer.adjust(running, -90, nowMs = 50_000).phase)
        val paused = WorkoutPlayer.pause(running, nowMs = 30_000)
        val adjusted = WorkoutPlayer.adjust(paused, 15, nowMs = 99_000)
        assertEquals(45_000L, WorkoutPlayer.remainingMs(adjusted.phase, 99_000))
    }

    @Test
    fun advancingWhilePausedStaysPaused() {
        val steps = listOf(reps(1), rest(60), reps(2))
        val paused = WorkoutPlayer.pause(WorkoutPlayer.start(steps, 0), nowMs = 5_000)
        val next = WorkoutPlayer.advance(paused, steps, nowMs = 6_000)
        assertEquals(1, next.index)
        assertTrue(next.phase is Phase.Paused)
        assertEquals(60_000L, WorkoutPlayer.remainingMs(next.phase, 999_999))
    }

    @Test
    fun finishingAndGoingBackFromTheEnd() {
        val steps = listOf(reps(1), rest(30), reps(2))
        var state = WorkoutPlayer.start(steps, 0)
        state = WorkoutPlayer.advance(state, steps, 1_000)
        state = WorkoutPlayer.advance(state, steps, 2_000)
        state = WorkoutPlayer.advance(state, steps, 3_000)
        assertEquals(PlayerState(3, Phase.Finished), state)
        assertEquals(2, WorkoutPlayer.previous(state, steps, 4_000).index)
        // Ended early on step 1: going back returns to step 1, not one before it.
        val ended = WorkoutPlayer.end(PlayerState(1, Phase.Running(0, 30_000)))
        assertEquals(1, WorkoutPlayer.previous(ended, steps, 5_000).index)
    }

    @Test
    fun restoreComesBackPausedAtTheStartOfTheStep() {
        val steps = listOf(reps(1), rest(90), timed(30))
        val restRestored = WorkoutPlayer.restore(steps, index = 1, nowMs = 50_000)
        assertEquals(1, restRestored.index)
        assertEquals(90_000L, WorkoutPlayer.remainingMs(restRestored.phase, 999_999))
        val timedRestored = WorkoutPlayer.restore(steps, index = 2, nowMs = 0)
        assertTrue((timedRestored.phase as Phase.Paused).frozen is Phase.LeadIn)
        assertEquals(Phase.Finished, WorkoutPlayer.restore(steps, index = 3, nowMs = 0).phase)
    }

    @Test
    fun stageTimelineRunsBothSidesAndSkipsUntimedStages() {
        val stretch = Exercise(
            id = 9, name = "Hamstring stretch", category = ExerciseCategory.FLEXIBILITY, unilateral = true,
            stages = listOf(
                ExerciseStage("Set up"),
                ExerciseStage("Hold", durationSeconds = 20),
                ExerciseStage("Deepen", durationSeconds = 10),
            ),
        )
        val timeline = StageTimeline.of(stretch, bothSides = true)
        assertEquals(
            listOf(
                StageSegment(1, Side.LEFT, 0, 20),
                StageSegment(2, Side.LEFT, 20, 10),
                StageSegment(1, Side.RIGHT, 30, 20),
                StageSegment(2, Side.RIGHT, 50, 10),
            ),
            timeline,
        )
        assertEquals(StageSegment(1, Side.RIGHT, 30, 20), StageTimeline.at(timeline, 35))
        assertNull(StageTimeline.at(timeline, 60))
        assertEquals(2, StageTimeline.of(stretch, bothSides = false).size)
    }

    @Test
    fun setDefaultsCarryTodaysWeightChangeToTheNextSet() {
        val set2 = reps(2, weight = 40.0)
        val loggedSet1 = log(stepIndex = 0, actual = SetTarget(reps = 10, weightKg = 42.5))
        val lastTime = log(stepIndex = 0, actual = SetTarget(reps = 8, weightKg = 37.5))
        val defaults = SetDefaults.forStep(set2, stepIndex = 2, logs = mapOf(0 to loggedSet1), lastTime = lastTime)
        assertEquals(SetTarget(reps = 10, weightKg = 42.5), defaults)
    }

    @Test
    fun setDefaultsFallBackToLastTimeAndPreferWhatWasAlreadyLogged() {
        val noTarget = reps(1).copy(target = SetTarget())
        val lastTime = log(stepIndex = 4, actual = SetTarget(reps = 8, weightKg = 37.5))
        assertEquals(SetTarget(reps = 8, weightKg = 37.5), SetDefaults.forStep(noTarget, 0, emptyMap(), lastTime))
        val redo = log(stepIndex = 0, actual = SetTarget(reps = 6))
        assertEquals(SetTarget(reps = 6), SetDefaults.forStep(reps(1), 0, mapOf(0 to redo), lastTime))
    }

    private fun log(stepIndex: Int, actual: SetTarget) = SetLog(
        sessionId = 1, stepIndex = stepIndex, exerciseId = 1, exerciseName = "Squat", blockIndex = 0,
        set = 1, round = 1, side = null, actual = actual, skipped = false, completedAtMillis = 0,
    )
}
