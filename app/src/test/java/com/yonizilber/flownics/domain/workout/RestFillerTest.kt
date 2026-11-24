package com.yonizilber.flownics.domain.workout

import com.yonizilber.flownics.domain.model.ActiveRest
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.MeasureMode
import com.yonizilber.flownics.domain.model.RestFiller
import com.yonizilber.flownics.domain.model.SetTarget
import com.yonizilber.flownics.domain.model.WorkoutBlock
import com.yonizilber.flownics.domain.model.WorkoutItem
import com.yonizilber.flownics.domain.model.WorkoutTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestFillerTest {

    private val squat = Exercise(id = 1, name = "Squat", category = ExerciseCategory.STRENGTH)
    private val row = Exercise(id = 2, name = "Row", category = ExerciseCategory.STRENGTH)
    private val hamstring = Exercise(id = 10, name = "Hamstring stretch", category = ExerciseCategory.FLEXIBILITY, measureMode = MeasureMode.TIME, unilateral = true)
    private val hip = Exercise(id = 11, name = "Hip flexor stretch", category = ExerciseCategory.FLEXIBILITY, measureMode = MeasureMode.TIME)
    private val pigeon = Exercise(id = 12, name = "Pigeon", category = ExerciseCategory.FLEXIBILITY, measureMode = MeasureMode.TIME)
    private val library = listOf(squat, row, hamstring, hip, pigeon).associateBy { it.id }

    private val stretching = WorkoutTemplate(
        id = 20,
        name = "Stretching",
        blocks = listOf(
            WorkoutBlock(
                items = listOf(
                    WorkoutItem(hamstring.id, sets = 2, target = SetTarget(seconds = 30), restSeconds = 10),
                    WorkoutItem(hip.id, sets = 1, target = SetTarget(seconds = 40), restSeconds = 10),
                    WorkoutItem(pigeon.id, sets = 1, target = SetTarget(seconds = 60), restSeconds = 0),
                ),
            ),
        ),
    )

    private fun strength(leftovers: Boolean, ownActiveRest: ActiveRest = ActiveRest.None) = WorkoutTemplate(
        id = 21,
        name = "Strength",
        blocks = listOf(
            WorkoutBlock(
                items = listOf(
                    WorkoutItem(squat.id, sets = 2, target = SetTarget(reps = 8), restSeconds = 90, activeRest = ownActiveRest),
                    WorkoutItem(row.id, sets = 2, target = SetTarget(reps = 10), restSeconds = 60),
                ),
            ),
        ),
        restFiller = RestFiller(stretching.id, leftovers),
    )

    private fun rests(c: CompiledWorkout) = c.steps.filterIsInstance<RestStep>().map { it.activeExerciseId }

    @Test
    fun eachStretchComesOnceInOrder() {
        val c = WorkoutCompiler.compile(strength(leftovers = false), library, listOf(stretching).associateBy { it.id })
        // 4 sets → 3 rests (none after the last). The hamstring stretch has 2 sets there, but as a
        // filler each exercise comes once.
        assertEquals(listOf(10L, 11L, 12L), rests(c))
        // Nothing left for the rest after the last set: a plain rest there goes.
        assertTrue(c.steps.last() is WorkStep)
        assertEquals(3, c.fillerUsed)
        assertEquals(3, c.fillerTotal)
        assertEquals(null, c.extraBlockTitle)
    }

    @Test
    fun withRepeatSetsAnExerciseFollowsItsSets() {
        val t = strength(leftovers = false).let { it.copy(restFiller = it.restFiller!!.copy(repeatSets = true)) }
        val c = WorkoutCompiler.compile(t, library, listOf(stretching).associateBy { it.id })
        // The hamstring stretch has 2 sets there: two rests in a row, then the hip flexor, and the
        // pigeon in the rest after the last set (an active rest stays, even at the very end).
        assertEquals(listOf(10L, 10L, 11L, 12L), rests(c))
        assertTrue(c.steps.last() is RestStep)
        assertEquals(4, c.fillerTotal)
    }

    @Test
    fun whatDoesntFitIsDoneAtTheEnd() {
        val short = strength(leftovers = true).let { it.copy(blocks = listOf(it.blocks[0].copy(items = it.blocks[0].items.take(1)))) }
        val c = WorkoutCompiler.compile(short, library, listOf(stretching).associateBy { it.id })
        // 2 squat sets, each with its rest, the last one too: the hamstring, then the hip flexor
        // after the last set; the pigeon, left over, at the end.
        assertEquals(listOf(10L, 11L), c.steps.filterIsInstance<RestStep>().filter { it.blockIndex == 0 }.map { it.activeExerciseId })
        assertEquals("Stretching", c.extraBlockTitle)
        val tail = c.steps.filterIsInstance<WorkStep>().filter { it.blockIndex == 1 }
        assertEquals(listOf(12L), tail.map { it.exerciseId })
        assertEquals(2, c.fillerUsed)
        assertTrue(c.steps.last() is WorkStep)
    }

    @Test
    fun theOtherWorkoutReplacesTheRestsOwnActiveRest() {
        val c = WorkoutCompiler.compile(strength(leftovers = false, ownActiveRest = ActiveRest.Pool()), library, listOf(stretching).associateBy { it.id })
        // Combining is the choice that counts: the squat's pool doesn't keep the rests.
        assertEquals(listOf(10L, 11L, 12L), rests(c))
    }

    @Test
    fun theLastRoundOfASupersetRestsBeforeTheNextBlock() {
        val superset = WorkoutBlock(
            structure = com.yonizilber.flownics.domain.model.BlockStructure.CIRCUIT,
            rounds = 2,
            roundRestSeconds = 150,
            items = listOf(WorkoutItem(squat.id, target = SetTarget(reps = 8), restSeconds = 45), WorkoutItem(row.id, target = SetTarget(reps = 8), restSeconds = 0)),
        )
        val next = WorkoutBlock(items = listOf(WorkoutItem(squat.id, sets = 1, target = SetTarget(reps = 5))))
        val c = WorkoutCompiler.compile(WorkoutTemplate(name = "Two blocks", blocks = listOf(superset, next)), library)
        assertEquals(listOf(45, 150, 45, 150), c.steps.filterIsInstance<RestStep>().map { it.durationSeconds })
    }

    @Test
    fun aPoolWithOneMatchVaries() {
        val chest = Exercise(id = 30, name = "Chest opener", category = ExerciseCategory.FLEXIBILITY, primaryMuscles = setOf(com.yonizilber.flownics.domain.model.Muscle.CHEST))
        val press = Exercise(id = 31, name = "Press", category = ExerciseCategory.STRENGTH, primaryMuscles = setOf(com.yonizilber.flownics.domain.model.Muscle.CHEST))
        val lib = library + mapOf(30L to chest, 31L to press)
        val t = WorkoutTemplate(
            name = "Press",
            blocks = listOf(WorkoutBlock(items = listOf(WorkoutItem(press.id, sets = 5, target = SetTarget(reps = 5), restSeconds = 120, activeRest = ActiveRest.Pool())))),
        )
        val c = WorkoutCompiler.compile(t, lib)
        // The chest opener (the only one for the chest) first, then others, not the same in every rest.
        val picks = c.steps.filterIsInstance<RestStep>().map { it.activeExerciseId }
        assertEquals(30L, picks.first())
        assertTrue(picks.toSet().size > 1)
    }

    @Test
    fun aSupersetGetsADifferentStretchBetweenRoundsAndNoneInsideARound() {
        val superset = WorkoutTemplate(
            id = 22,
            name = "Superset",
            blocks = listOf(
                WorkoutBlock(
                    structure = com.yonizilber.flownics.domain.model.BlockStructure.CIRCUIT,
                    rounds = 3,
                    roundRestSeconds = 90,
                    items = listOf(
                        WorkoutItem(squat.id, target = SetTarget(reps = 8), restSeconds = 15, activeRest = ActiveRest.Specific(pigeon.id)),
                        WorkoutItem(row.id, target = SetTarget(reps = 10), restSeconds = 60),
                    ),
                ),
            ),
            restFiller = RestFiller(stretching.id, leftoversAtEnd = false),
        )
        val c = WorkoutCompiler.compile(superset, library, listOf(stretching).associateBy { it.id })
        // Squat → 15 s → row → 90 s, three times. The 15 s rests stay plain (even with the squat's
        // own active rest, which is reported); the rests after rounds, the last one too, take different stretches.
        val restSeconds = c.steps.filterIsInstance<RestStep>().map { it.durationSeconds to it.activeExerciseId }
        assertEquals(listOf(15 to null, 90 to 10L, 15 to null, 90 to 11L, 15 to null, 90 to 12L), restSeconds)
        assertTrue(c.warnings.any { it is CompileWarning.ActiveRestInsideRound })
    }

    @Test
    fun aDeletedFillerIsReported() {
        val c = WorkoutCompiler.compile(strength(leftovers = true), library, emptyMap())
        assertTrue(CompileWarning.MissingRestFiller in c.warnings)
        assertEquals(listOf<Long?>(null, null, null), rests(c))
    }
}
