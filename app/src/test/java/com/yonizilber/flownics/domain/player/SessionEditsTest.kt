package com.yonizilber.flownics.domain.player

import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.MeasureMode
import com.yonizilber.flownics.domain.model.SetTarget
import com.yonizilber.flownics.domain.workout.RestPart
import com.yonizilber.flownics.domain.workout.RestStep
import com.yonizilber.flownics.domain.workout.Side
import com.yonizilber.flownics.domain.workout.WorkStep
import com.yonizilber.flownics.domain.workout.WorkoutStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEditsTest {

    private fun work(set: Int, total: Int = 3, ex: Long = 1, seconds: Int? = null, hold: Boolean = false, side: Side? = null) =
        WorkStep(0, 0, ex, set, total, 1, 1, SetTarget(reps = 8, seconds = seconds), side, seconds, hold)

    private fun rest(active: Long? = null, hold: Boolean = false, seconds: Int = 90) = RestStep(0, seconds, listOfNotNull(active?.let { RestPart(it) }), hold)

    @Test
    fun aHoldingStepCountsOvertimeUntilConfirmed() {
        val steps = listOf<WorkoutStep>(rest(active = 10, hold = true, seconds = 60), work(2))
        val running = PlayerState(0, Phase.Running(0, 60_000))
        val later = WorkoutPlayer.tick(running, steps, nowMs = 75_000)
        assertEquals(0, later.index)
        assertEquals(15_000, WorkoutPlayer.overtimeMs(later.phase, 75_000))
        assertNull(WorkoutPlayer.nextDeadline(later, steps, 75_000, holdAll = false))
        // Without the hold it moves on at zero, as before.
        assertEquals(1, WorkoutPlayer.tick(running, listOf(rest(active = 10), work(2)), 75_000).index)
        // "Wait for me" for the whole workout holds a plain timed step too.
        assertEquals(0, WorkoutPlayer.tick(running, listOf(rest(), work(2)), 75_000, holdAll = true).index)
        // Tapping Next moves on.
        assertEquals(1, WorkoutPlayer.advance(later, steps, 76_000).index)
    }

    @Test
    fun oneMoreSetGoesRightAfterAndRenumbers() {
        val steps = listOf<WorkoutStep>(work(1), rest(), work(2), rest(), work(3), rest(), work(1, total = 2, ex = 2))
        val edit = checkNotNull(SessionEdits.addSet(steps, 2))
        val squats = edit.steps.filterIsInstance<WorkStep>().filter { it.exerciseId == 1L }
        assertEquals(listOf(1, 2, 3, 4), squats.map { it.set })
        assertTrue(squats.all { it.totalSets == 4 })
        assertEquals(4, edit.insertedAt)
        assertEquals(2, edit.shift)
        // A one-sided timed set brings both sides along.
        val holds = listOf<WorkoutStep>(work(1, 1, seconds = 30, side = Side.LEFT), work(1, 1, seconds = 30, side = Side.RIGHT), rest())
        val e2 = checkNotNull(SessionEdits.addSet(holds, 1))
        assertEquals(listOf(Side.LEFT, Side.RIGHT, Side.LEFT, Side.RIGHT), e2.steps.filterIsInstance<WorkStep>().map { it.side })
        // In a superset the short rest after it stays one that never takes a rest exercise.
        val superset = listOf<WorkoutStep>(work(1), RestStep(0, 15, slot = false), work(1, ex = 2), rest(active = 10))
        val e3 = checkNotNull(SessionEdits.addSet(superset, 0))
        assertEquals(false, (e3.steps[3] as RestStep).slot)
    }

    @Test
    fun anAddedExerciseGoesAfterTheCurrentStepOrAtTheEnd() {
        val plank = Exercise(id = 5, name = "Plank", category = ExerciseCategory.CORE, measureMode = MeasureMode.TIME)
        val added = SessionEdits.exerciseSteps(plank, sets = 2, target = SetTarget(seconds = 45), restSeconds = 30, blockIndex = 1)
        assertEquals(4, added.size)
        assertEquals(45, (added[0] as WorkStep).durationSeconds)
        val steps = listOf<WorkoutStep>(work(1), rest(), work(2))
        // During set 1: after it and its rest.
        assertEquals(2, SessionEdits.addAfterCurrent(steps, 0, finished = false, added).insertedAt)
        // After the end: at the end.
        assertEquals(3, SessionEdits.addAfterCurrent(steps, 3, finished = true, added).insertedAt)
    }
}
