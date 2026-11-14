package com.yonizilber.flownics.domain.player

import com.yonizilber.flownics.domain.model.BlockStructure
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.MeasureMode
import com.yonizilber.flownics.domain.model.RestFiller
import com.yonizilber.flownics.domain.model.SetTarget
import com.yonizilber.flownics.domain.model.WorkoutBlock
import com.yonizilber.flownics.domain.model.WorkoutItem
import com.yonizilber.flownics.domain.model.WorkoutTemplate
import com.yonizilber.flownics.domain.workout.RestStep
import com.yonizilber.flownics.domain.workout.WorkStep
import com.yonizilber.flownics.domain.workout.WorkoutCompiler
import com.yonizilber.flownics.domain.workout.WorkoutStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestLaneTest {

    private fun stretch(id: Long) = Exercise(id = id, name = "Stretch $id", category = ExerciseCategory.FLEXIBILITY, measureMode = MeasureMode.TIME)
    private val squat = Exercise(id = 1, name = "Squat", category = ExerciseCategory.STRENGTH)
    private val row = Exercise(id = 2, name = "Row", category = ExerciseCategory.STRENGTH)
    private val library = (listOf(squat, row) + (10L..14L).map(::stretch)).associateBy { it.id }

    private fun work(ex: Long, set: Int = 1) = WorkStep(0, 0, ex, set, 3, 1, 1, SetTarget(reps = 8), null, null)
    private fun rest(active: Long?) = RestStep(0, 60, active, holdAtEnd = active != null, activeTarget = active?.let { SetTarget(seconds = 30) })
    private fun atEnd(ex: Long) = WorkStep(1, 0, ex, 1, 1, 1, 1, SetTarget(seconds = 40), null, 40, fromRests = true)

    /**
     * Squat, then a superset of squat and row: the rest between them (3) is the short one inside
     * the round. Stretches 10–12 are in the rests, 13 and 14 wait at the end.
     */
    private val steps = listOf<WorkoutStep>(
        work(1), rest(10), work(1, 2), RestStep(0, 15, null, slot = false), work(2), rest(11), work(1, 3), rest(12), work(2, 2),
        atEnd(13), RestStep(1, 10, null, slot = false), atEnd(14),
    )

    private fun active(s: List<WorkoutStep>) = s.mapIndexedNotNull { i, x -> (x as? RestStep)?.activeExerciseId?.let { i to it } }.toMap()
    private fun endIds(s: List<WorkoutStep>) = s.filterIsInstance<WorkStep>().filter { it.fromRests }.map { it.exerciseId }

    @Test
    fun againGoesToTheNextRestBetweenSetsNeverTheOneInsideTheRound() {
        val out = checkNotNull(RestLane.again(steps, 1, library, emptySet(), newBlock = 2))
        assertEquals(mapOf(1 to 10L, 5 to 10L, 7 to 11L), active(out.steps))
        assertNull((out.steps[3] as RestStep).activeExerciseId)
        // The one pushed out of the last rest is first at the end, with its own time.
        assertEquals(listOf(12L, 13L, 14L), endIds(out.steps))
        assertEquals(30, out.steps.filterIsInstance<WorkStep>().first { it.exerciseId == 12L }.durationSeconds)
        assertEquals(12, out.shiftFrom)
        assertEquals(out.steps.size - steps.size, out.shiftBy)
    }

    @Test
    fun skipBringsTheNextOneInAndTheQueueClosesUp() {
        val out = checkNotNull(RestLane.skip(steps, 1, library, emptySet(), newBlock = 2))
        assertEquals(mapOf(1 to 11L, 5 to 12L, 7 to 13L), active(out.steps))
        assertEquals(listOf(14L), endIds(out.steps))
        assertEquals(-2, out.shiftBy)
    }

    @Test
    fun notNowLeavesThisRestPlainAndCarriesItOn() {
        val out = checkNotNull(RestLane.postpone(steps, 1, library, emptySet(), newBlock = 2))
        assertEquals(mapOf(5 to 10L, 7 to 11L), active(out.steps))
        assertEquals(listOf(12L, 13L, 14L), endIds(out.steps))
    }

    @Test
    fun anEarlierOneCanBeDoneAgainAndOneFromTheEndBroughtForward() {
        // In the rest at 5, back to stretch 10 (done at 1).
        val back = checkNotNull(RestLane.previousEntry(steps, 5))
        assertEquals(10L, back.exerciseId)
        val again = checkNotNull(RestLane.pick(steps, 5, back, library, emptySet(), newBlock = 2))
        assertEquals(mapOf(1 to 10L, 5 to 10L, 7 to 11L), active(again.steps))
        assertEquals(listOf(12L, 13L, 14L), endIds(again.steps))
        // During a set: stretch 14 from the end goes into the next rest between sets (5, not 3).
        val last = RestLane.entries(steps, 2).first { it.exerciseId == 14L }
        assertEquals(LaneStatus.AT_END, last.status)
        val forward = checkNotNull(RestLane.pick(steps, 2, last, library, emptySet(), newBlock = 2))
        assertEquals(mapOf(1 to 10L, 5 to 14L, 7 to 11L), active(forward.steps))
        assertEquals(listOf(12L, 13L), endIds(forward.steps))
    }

    @Test
    fun movingInTheSetsKeepsTheRestLaneInPlace() {
        // In the rest at 7 (stretch 12), back to redo squat set 2: the rests ahead go on from 12.
        val back = checkNotNull(RestLane.moveSets(steps, 7, 2, library, emptySet(), newBlock = 2))
        assertEquals(mapOf(1 to 10L, 5 to 12L, 7 to 13L), active(back.steps))
        assertEquals(listOf(14L), endIds(back.steps))
        // Jumping ahead from the rest at 1 to the last superset: the rest jumped over stays plain,
        // and nothing is lost.
        val ahead = checkNotNull(RestLane.moveSets(steps, 1, 6, library, emptySet(), newBlock = 2))
        assertEquals(mapOf(7 to 10L), active(ahead.steps))
        assertEquals(listOf(11L, 12L, 13L, 14L), endIds(ahead.steps))
    }

    @Test
    fun aStretchAlreadyDoneAtTheEndStaysPut() {
        val out = checkNotNull(RestLane.again(steps, 7, library, logged = setOf(9), newBlock = 2))
        // 13 was logged: the change only touches what comes after it.
        assertEquals(listOf(13L, 12L, 14L), endIds(out.steps))
    }

    @Test
    fun withNoBlockAtTheEndOneIsMade() {
        val plain = steps.take(9)
        val out = checkNotNull(RestLane.again(plain, 7, library, emptySet(), newBlock = 2))
        assertTrue(out.newBlockUsed)
        assertEquals(2, out.steps.last().blockIndex)
        assertEquals(listOf(12L), endIds(out.steps))
    }

    @Test
    fun combinedWorkoutsMarkTheRestsInsideARound() {
        val superset = WorkoutTemplate(
            id = 1, name = "Strength",
            blocks = listOf(
                WorkoutBlock(
                    structure = BlockStructure.CIRCUIT, rounds = 3, roundRestSeconds = 90,
                    items = listOf(WorkoutItem(squat.id, sets = 1, target = SetTarget(reps = 8), restSeconds = 15), WorkoutItem(row.id, sets = 1, target = SetTarget(reps = 8), restSeconds = 15)),
                ),
            ),
            restFiller = RestFiller(workoutId = 2),
        )
        val stretching = WorkoutTemplate(
            id = 2, name = "Stretching",
            blocks = listOf(WorkoutBlock(items = (10L..14L).map { WorkoutItem(it, sets = 1, target = SetTarget(seconds = 45), restSeconds = 10) })),
        )
        val compiled = WorkoutCompiler.compile(superset, library, mapOf(1L to superset, 2L to stretching))
        val rests = compiled.steps.filterIsInstance<RestStep>().filter { it.blockIndex == 0 }
        assertTrue(rests.filter { !it.slot }.all { it.activeExerciseId == null && it.durationSeconds == 15 })
        assertEquals(listOf(10L, 11L), rests.filter { it.slot }.map { it.activeExerciseId })
        assertEquals(45, rests.first { it.slot }.activeTarget?.seconds)
        // Doing one again from the first round rest never touches the superset's short rests.
        val index = compiled.steps.indexOfFirst { it is RestStep && it.slot }
        val out = checkNotNull(RestLane.again(compiled.steps, index, library, emptySet(), newBlock = 2))
        assertTrue(out.steps.filterIsInstance<RestStep>().filter { !it.slot }.all { it.activeExerciseId == null })
        assertEquals(listOf(11L, 12L, 13L, 14L), endIds(out.steps))
    }
}
