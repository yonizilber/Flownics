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
import com.yonizilber.flownics.domain.workout.RestPart
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
    private fun rest(active: Long?) = RestStep(0, 60, listOfNotNull(active?.let { RestPart(it, SetTarget(seconds = 30)) }), holdAtEnd = active != null)
    private fun atEnd(ex: Long) = WorkStep(1, 0, ex, 1, 1, 1, 1, SetTarget(seconds = 40), null, 40, fromRests = true)

    /**
     * Squat, then a superset of squat and row: the rest between them (3) is the short one inside
     * the round. Stretches 10–12 are in the rests, 13 and 14 wait at the end.
     */
    private val steps = listOf<WorkoutStep>(
        work(1), rest(10), work(1, 2), RestStep(0, 15, slot = false), work(2), rest(11), work(1, 3), rest(12), work(2, 2),
        atEnd(13), RestStep(1, 10, slot = false), atEnd(14),
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
        val back = checkNotNull(RestLane.previousEntry(steps, 5, back = 0))
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
    fun goingBackAgainWalksFurtherBackNeverRepeatingTheSameOne() {
        // In the rest at 7 (stretch 12), 10 and 11 done before it.
        val first = checkNotNull(RestLane.previousEntry(steps, 7, back = 0))
        assertEquals(11L, first.exerciseId)
        val once = checkNotNull(RestLane.pick(steps, 7, first, library, emptySet(), newBlock = 2))
        assertEquals(11L, active(once.steps)[7])
        // Back again from the same rest: the one before it, not 11 a second time.
        val second = checkNotNull(RestLane.previousEntry(once.steps, 7, back = 1))
        assertEquals(10L, second.exerciseId)
        val twice = checkNotNull(RestLane.pick(once.steps, 7, second, library, emptySet(), newBlock = 2))
        assertEquals(10L, active(twice.steps)[7])
        // What comes next is the walk forward again: 11, then 12 (where it was), 13, 14. Nothing twice.
        assertEquals(listOf(11L, 12L, 13L, 14L), endIds(twice.steps))
        // Nothing before 10.
        assertNull(RestLane.previousEntry(twice.steps, 7, back = 2))
    }

    @Test
    fun anAddedExercisesRestsAreNotPartOfTheLane() {
        // A row added after squat set 1's rest: its rests stay plain whatever the lane does.
        val row = SessionEdits.exerciseSteps(library.getValue(2), sets = 2, target = SetTarget(reps = 8), restSeconds = 60, blockIndex = 2)
        val added = SessionEdits.addAfterCurrent(steps, 0, finished = false, row).steps
        assertTrue(added.filterIsInstance<RestStep>().filter { it.blockIndex == 2 }.none { it.slot })
        val rowRests = added.indices.filter { (added[it] as? RestStep)?.blockIndex == 2 }
        val again = checkNotNull(RestLane.again(added, 1, library, emptySet(), newBlock = 3))
        assertTrue(rowRests.all { (again.steps[it] as RestStep).active.isEmpty() })
        val jumped = checkNotNull(RestLane.moveSets(added, 1, 2, library, emptySet(), newBlock = 3))
        assertTrue(rowRests.all { (jumped.steps[it] as RestStep).active.isEmpty() })
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
        // A rest after each round, the last one too.
        assertEquals(listOf(10L, 11L, 12L), rests.filter { it.slot }.map { it.activeExerciseId })
        assertEquals(45, rests.first { it.slot }.active.first().target?.seconds)
        // Doing one again from the first round rest never touches the superset's short rests.
        val index = compiled.steps.indexOfFirst { it is RestStep && it.slot }
        val out = checkNotNull(RestLane.again(compiled.steps, index, library, emptySet(), newBlock = 2))
        assertTrue(out.steps.filterIsInstance<RestStep>().filter { !it.slot }.all { it.activeExerciseId == null })
        // Three round rests (the last round's too): 10 again pushes 12 out to the end, before 13 and 14.
        assertEquals(listOf(12L, 13L, 14L), endIds(out.steps))
    }

    /** Strength with three rests to fill; the other workout is a superset of two stretches, three rounds. */
    private fun withSupersetFiller(together: Boolean, repeat: Boolean = true): List<WorkoutStep> {
        val strength = WorkoutTemplate(
            id = 1, name = "Strength",
            blocks = listOf(WorkoutBlock(items = listOf(WorkoutItem(squat.id, sets = 4, target = SetTarget(reps = 8), restSeconds = 90)))),
            restFiller = RestFiller(workoutId = 2, repeatSets = repeat, roundsTogether = together),
        )
        val pair = WorkoutTemplate(
            id = 2, name = "Mobility",
            blocks = listOf(
                WorkoutBlock(
                    structure = BlockStructure.CIRCUIT, rounds = 3, roundRestSeconds = 20,
                    items = listOf(WorkoutItem(10, sets = 1, target = SetTarget(seconds = 30), restSeconds = 5), WorkoutItem(11, sets = 1, target = SetTarget(seconds = 30), restSeconds = 5)),
                ),
                WorkoutBlock(items = listOf(WorkoutItem(12, sets = 2, target = SetTarget(seconds = 40), restSeconds = 10))),
            ),
        )
        return WorkoutCompiler.compile(strength, library, mapOf(1L to strength, 2L to pair)).steps
    }

    @Test
    fun aSupersetOfTheOtherWorkoutGoesIntoEachRestAsARound() {
        val steps = withSupersetFiller(together = true)
        val rests = steps.filterIsInstance<RestStep>().filter { it.slot }
        // A rest after each of the four sets: a round of the pair in the first three, stretch 12 in the last.
        assertEquals(listOf(listOf(10L, 11L), listOf(10L, 11L), listOf(10L, 11L), listOf(12L)), rests.map { r -> r.active.map { it.exerciseId } })
        assertEquals(30, rests.first().active[1].target?.seconds)
        // Then stretch 12's second set at the end.
        assertEquals(listOf(12L), endIds(steps))
        // The lane shows a round as one entry.
        assertEquals(listOf(10L, 11L), RestLane.entries(steps, 0).first().exerciseIds)
    }

    @Test
    fun oneExerciseARestWhenSupersetsAreNotKeptTogether() {
        val rests = withSupersetFiller(together = false).filterIsInstance<RestStep>().filter { it.slot }
        assertEquals(listOf(10L, 11L, 10L, 11L), rests.map { it.activeExerciseId })
        assertTrue(rests.all { it.active.size == 1 })
    }

    @Test
    fun aRoundThatGoesToTheEndStaysARound() {
        val steps = withSupersetFiller(together = true)
        val first = steps.indexOfFirst { it is RestStep && it.slot }
        val once = checkNotNull(RestLane.again(steps, first, library, emptySet(), newBlock = 2))
        val out = checkNotNull(RestLane.again(once.steps, first, library, emptySet(), newBlock = 2))
        // Two more rounds in the rests push the last round out: it is done at the end, both together, before stretch 12.
        val end = out.steps.filterIsInstance<WorkStep>().filter { it.fromRests }
        assertEquals(listOf(10L, 11L, 12L, 12L), end.map { it.exerciseId })
        assertEquals(listOf(0, 1, 0, 0), end.map { it.restPart })
        val atEnd = RestLane.entries(out.steps, 0).filter { it.status == LaneStatus.AT_END }
        assertEquals(listOf(listOf(10L, 11L), listOf(12L), listOf(12L)), atEnd.map { it.exerciseIds })
        // Brought back into a rest, it comes back whole.
        val back = checkNotNull(RestLane.pick(out.steps, first, atEnd.first(), library, emptySet(), newBlock = 2))
        assertEquals(listOf(10L, 11L), (back.steps[first] as RestStep).active.map { it.exerciseId })
    }

    @Test
    fun aRoundSkippedThenNotNowMovesOnWhole() {
        val steps = withSupersetFiller(together = true, repeat = false)
        // Without repeats: one round of the pair, then stretch 12 once.
        val rests = steps.filterIsInstance<RestStep>().filter { it.slot }
        assertEquals(listOf(listOf(10L, 11L), listOf(12L)), rests.filter { it.active.isNotEmpty() }.map { r -> r.active.map { it.exerciseId } })
        val first = steps.indexOfFirst { it is RestStep && it.slot }
        val out = checkNotNull(RestLane.postpone(steps, first, library, emptySet(), newBlock = 2))
        val after = out.steps.filterIsInstance<RestStep>().filter { it.slot }.map { r -> r.active.map { it.exerciseId } }
        assertEquals(listOf(emptyList(), listOf(10L, 11L), listOf(12L)), after)
    }

    private val extra = stretch(15)
    private val withExtra = library + (extra.id to extra)

    @Test
    fun aRestExerciseAddedWhenNoRestIsLeftComesFirstOfThoseAtTheEnd() {
        // During the last set: no rest left, so it comes before 13 and 14, in their block.
        val out = RestLane.addNext(steps, 8, listOf(RestPart(15, SetTarget(seconds = 30))), withExtra, emptySet(), newBlock = 2)
        assertEquals(listOf(15L, 13L, 14L), endIds(out.steps))
        assertTrue(out.steps.filterIsInstance<WorkStep>().filter { it.fromRests }.all { it.blockIndex == 1 })
        assertEquals(false, out.newBlockUsed)
        // While doing 13: after it, before 14.
        val during = RestLane.addNext(steps, 9, listOf(RestPart(15, SetTarget(seconds = 30))), withExtra, setOf(9), newBlock = 2)
        assertEquals(listOf(13L, 15L, 14L), endIds(during.steps))
    }

    @Test
    fun aRestExerciseAddedInARestIsDoneNowAndTheOthersMoveOn() {
        val out = RestLane.addNext(steps, 1, listOf(RestPart(15, SetTarget(seconds = 30))), withExtra, emptySet(), newBlock = 2)
        assertEquals(mapOf(1 to 15L, 5 to 10L, 7 to 11L), active(out.steps))
        assertEquals(listOf(12L, 13L, 14L), endIds(out.steps))
    }

    @Test
    fun aRestExerciseAddedAfterTheLastOneAtTheEndJoinsThatBlockAfterARest() {
        // Doing 14, the last of all: the new one follows it, in the same block, with its rest before it.
        val out = RestLane.addNext(steps, 11, listOf(RestPart(15, SetTarget(seconds = 30))), withExtra, setOf(9, 11), newBlock = 2)
        assertEquals(listOf(13L, 14L, 15L), endIds(out.steps))
        assertEquals(RestStep(1, 10, slot = false), out.steps[12])
        assertEquals(1, out.steps.last().blockIndex)
        assertEquals(false, out.newBlockUsed)
    }

    @Test
    fun anExerciseAddedToTheMainWorkoutTakesTheRestExercisesStillToCome() {
        // Doing 13 at the end: two sets of squats, as the main workout, after 13 and its rest.
        val added = SessionEdits.exerciseSteps(squat, sets = 2, target = SetTarget(reps = 8), restSeconds = 60, blockIndex = 2, inLane = true)
        val edit = SessionEdits.addAfterCurrent(steps, 9, finished = false, added)
        assertEquals(11, edit.insertedAt)
        val out = checkNotNull(RestLane.reflow(edit.steps, edit.insertedAt, library, setOf(9), newBlock = 3))
        // 14 moves into the first squat rest; the second has nothing left and, being last, goes.
        assertEquals(14L, (out.steps[12] as RestStep).activeExerciseId)
        assertEquals(listOf(13L), endIds(out.steps))
        assertEquals(squat.id, (out.steps.last() as WorkStep).exerciseId)
    }

    @Test
    fun anExerciseAddedNextLeavesTheRestExercisesAlone() {
        val added = SessionEdits.exerciseSteps(squat, sets = 2, target = SetTarget(reps = 8), restSeconds = 60, blockIndex = 2)
        val edit = SessionEdits.addAfterCurrent(steps, 9, finished = false, added)
        assertTrue(added.filterIsInstance<RestStep>().none { it.slot })
        assertEquals(listOf(13L, 14L), endIds(edit.steps))
        assertEquals(listOf(10L, 11L, 12L), active(edit.steps).values.toList())
    }
}
