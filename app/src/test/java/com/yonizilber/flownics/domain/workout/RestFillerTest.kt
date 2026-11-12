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
        assertEquals(3, c.fillerUsed)
        assertEquals(3, c.fillerTotal)
        assertEquals(null, c.extraBlockTitle)
    }

    @Test
    fun whatDoesntFitIsDoneAtTheEnd() {
        val short = strength(leftovers = true).let { it.copy(blocks = listOf(it.blocks[0].copy(items = it.blocks[0].items.take(1)))) }
        val c = WorkoutCompiler.compile(short, library, listOf(stretching).associateBy { it.id })
        // 2 squat sets → 1 rest → the hamstring; hip and pigeon at the end, each once (with the hip's own rest between them).
        assertEquals(listOf(10L), c.steps.filterIsInstance<RestStep>().filter { it.blockIndex == 0 }.map { it.activeExerciseId })
        assertEquals("Stretching", c.extraBlockTitle)
        val tail = c.steps.filterIsInstance<WorkStep>().filter { it.blockIndex == 1 }
        assertEquals(listOf(11L, 12L), tail.map { it.exerciseId })
        assertTrue(c.steps.last() is WorkStep)
    }

    @Test
    fun aRestWithItsOwnActiveRestKeepsIt() {
        val c = WorkoutCompiler.compile(strength(leftovers = false, ownActiveRest = ActiveRest.Specific(pigeon.id)), library, listOf(stretching).associateBy { it.id })
        // Squat rests keep the pigeon; the row rest takes the first stretch.
        assertEquals(listOf(12L, 12L, 10L), rests(c))
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
        // own active rest, which is reported); the two rests between rounds take two different stretches.
        val restSeconds = c.steps.filterIsInstance<RestStep>().map { it.durationSeconds to it.activeExerciseId }
        assertEquals(listOf(15 to null, 90 to 10L, 15 to null, 90 to 11L, 15 to null), restSeconds)
        assertTrue(c.warnings.any { it is CompileWarning.ActiveRestInsideRound })
    }

    @Test
    fun aDeletedFillerIsReported() {
        val c = WorkoutCompiler.compile(strength(leftovers = true), library, emptyMap())
        assertTrue(CompileWarning.MissingRestFiller in c.warnings)
        assertEquals(listOf<Long?>(null, null, null), rests(c))
    }
}
