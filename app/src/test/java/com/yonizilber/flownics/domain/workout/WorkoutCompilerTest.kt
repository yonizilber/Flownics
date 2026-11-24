package com.yonizilber.flownics.domain.workout

import com.yonizilber.flownics.domain.model.ActiveRest
import com.yonizilber.flownics.domain.model.BlockStructure
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.ExerciseStage
import com.yonizilber.flownics.domain.model.MeasureMode
import com.yonizilber.flownics.domain.model.Muscle
import com.yonizilber.flownics.domain.model.PoolFocus
import com.yonizilber.flownics.domain.model.SetTarget
import com.yonizilber.flownics.domain.model.WorkoutBlock
import com.yonizilber.flownics.domain.model.WorkoutItem
import com.yonizilber.flownics.domain.model.WorkoutTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutCompilerTest {

    private val squat = Exercise(1, "Squat", ExerciseCategory.STRENGTH, primaryMuscles = setOf(Muscle.QUADS, Muscle.GLUTES))
    private val pushUp = Exercise(2, "Push-up", ExerciseCategory.STRENGTH, primaryMuscles = setOf(Muscle.CHEST))
    private val quadStretch = Exercise(
        3, "Quad stretch", ExerciseCategory.FLEXIBILITY,
        primaryMuscles = setOf(Muscle.QUADS),
        stages = listOf(ExerciseStage("Hold", durationSeconds = 30)),
        unilateral = true,
    )
    private val chestOpener = Exercise(4, "Chest opener", ExerciseCategory.FLEXIBILITY, primaryMuscles = setOf(Muscle.CHEST))
    private val catCow = Exercise(5, "Cat cow", ExerciseCategory.MOBILITY, primaryMuscles = setOf(Muscle.LOWER_BACK))
    private val sidePlank = Exercise(6, "Side plank", ExerciseCategory.CORE, measureMode = MeasureMode.TIME, unilateral = true)

    private val library = listOf(squat, pushUp, quadStretch, chestOpener, catCow, sidePlank).associateBy { it.id }

    private fun template(vararg blocks: WorkoutBlock) = WorkoutTemplate(name = "T", blocks = blocks.toList())

    @Test
    fun straightSetsAlternateWorkAndRestWithoutTrailingRest() {
        val result = WorkoutCompiler.compile(
            template(WorkoutBlock(items = listOf(WorkoutItem(squat.id, sets = 3, target = SetTarget(reps = 10), restSeconds = 60)))),
            library,
        )
        assertEquals(5, result.steps.size)
        val work = result.steps.filterIsInstance<WorkStep>()
        assertEquals(listOf(1, 2, 3), work.map { it.set })
        assertTrue(work.all { it.totalSets == 3 && it.durationSeconds == null })
        assertEquals(listOf(60, 60), result.steps.filterIsInstance<RestStep>().map { it.durationSeconds })
        assertTrue(result.steps.last() is WorkStep)
        assertEquals(emptyList<CompileWarning>(), result.warnings)
    }

    @Test
    fun restBetweenExercisesComesFromTheFinishedItem() {
        val result = WorkoutCompiler.compile(
            template(
                WorkoutBlock(
                    items = listOf(
                        WorkoutItem(squat.id, sets = 1, restSeconds = 90),
                        WorkoutItem(pushUp.id, sets = 1, restSeconds = 30),
                    ),
                ),
            ),
            library,
        )
        assertEquals(listOf(squat.id, null, pushUp.id), result.steps.map { (it as? WorkStep)?.exerciseId })
        assertEquals(90, result.steps[1].durationSeconds)
    }

    @Test
    fun zeroRestAddsNoRestStep() {
        val result = WorkoutCompiler.compile(
            template(WorkoutBlock(items = listOf(WorkoutItem(squat.id, sets = 2, restSeconds = 0)))),
            library,
        )
        assertEquals(2, result.steps.size)
        assertTrue(result.steps.all { it is WorkStep })
    }

    @Test
    fun circuitRunsRoundsAndUsesRoundRestBetweenRounds() {
        val block = WorkoutBlock(
            structure = BlockStructure.CIRCUIT,
            rounds = 2,
            roundRestSeconds = 120,
            items = listOf(WorkoutItem(squat.id, restSeconds = 10), WorkoutItem(pushUp.id, restSeconds = 15)),
        )
        val result = WorkoutCompiler.compile(template(block), library)
        // Round 1: squat, 10s, push-up, 120s round rest. Round 2: squat, 10s, push-up (trailing rest dropped).
        assertEquals(7, result.steps.size)
        assertEquals(listOf(10, 120, 10), result.steps.filterIsInstance<RestStep>().map { it.durationSeconds })
        assertEquals(listOf(1, 1, 2, 2), result.steps.filterIsInstance<WorkStep>().map { it.round })
        assertTrue(result.steps.filterIsInstance<WorkStep>().all { it.totalRounds == 2 })
    }

    @Test
    fun lastCircuitRoundRestsWithTheRoundRestBeforeTheNextBlock() {
        val circuit = WorkoutBlock(
            structure = BlockStructure.CIRCUIT,
            rounds = 1,
            roundRestSeconds = 120,
            items = listOf(WorkoutItem(pushUp.id, restSeconds = 45)),
        )
        val next = WorkoutBlock(items = listOf(WorkoutItem(squat.id, sets = 1)))
        val result = WorkoutCompiler.compile(template(circuit, next), library)
        // A superset ends with its round rest before the next block, not its last item's short rest.
        assertEquals(120, (result.steps[1] as RestStep).durationSeconds)
    }

    @Test
    fun specificActiveRestFillsEveryRest() {
        val item = WorkoutItem(squat.id, sets = 2, restSeconds = 60, activeRest = ActiveRest.Specific(quadStretch.id))
        val result = WorkoutCompiler.compile(template(WorkoutBlock(items = listOf(item))), library)
        // After both sets, the last one too: an active rest stays even when nothing follows.
        assertEquals(listOf(quadStretch.id, quadStretch.id), result.steps.filterIsInstance<RestStep>().map { it.activeExerciseId })
        assertTrue(result.steps.last() is RestStep)
    }

    @Test
    fun poolPrefersStretchesForTheWorkedMuscles() {
        val item = WorkoutItem(squat.id, sets = 3, restSeconds = 60, activeRest = ActiveRest.Pool())
        val result = WorkoutCompiler.compile(template(WorkoutBlock(items = listOf(item))), library)
        val active = result.steps.filterIsInstance<RestStep>().map { it.activeExerciseId }
        // The quad stretch first; it isn't repeated while other stretches haven't been used.
        assertEquals(quadStretch.id, active.first())
        assertTrue(active[1] != quadStretch.id)
    }

    @Test
    fun poolWithAnyFocusRotatesThroughCandidatesByName() {
        val item = WorkoutItem(squat.id, sets = 4, restSeconds = 90, activeRest = ActiveRest.Pool(focus = PoolFocus.ANY))
        val result = WorkoutCompiler.compile(template(WorkoutBlock(items = listOf(item))), library)
        val active = result.steps.filterIsInstance<RestStep>().map { it.activeExerciseId }
        // Alphabetical: Cat cow, Chest opener, Quad stretch, then round again (a rest after each of the 4 sets).
        assertEquals(listOf(catCow.id, chestOpener.id, quadStretch.id, catCow.id), active)
    }

    @Test
    fun poolFallsBackToWholePoolWhenNoMuscleMatches() {
        val calfRaise = Exercise(10, "Calf raise", ExerciseCategory.STRENGTH, primaryMuscles = setOf(Muscle.CALVES))
        val lib = library + (calfRaise.id to calfRaise)
        val item = WorkoutItem(calfRaise.id, sets = 2, restSeconds = 90, activeRest = ActiveRest.Pool())
        val result = WorkoutCompiler.compile(template(WorkoutBlock(items = listOf(item))), lib)
        assertEquals(catCow.id, result.steps.filterIsInstance<RestStep>().first().activeExerciseId)
        assertEquals(emptyList<CompileWarning>(), result.warnings)
    }

    @Test
    fun emptyPoolIsReportedAndRestStaysPlain() {
        val item = WorkoutItem(squat.id, sets = 2, restSeconds = 60, activeRest = ActiveRest.Pool(categories = setOf(ExerciseCategory.BREATHING)))
        val result = WorkoutCompiler.compile(template(WorkoutBlock(items = listOf(item))), library)
        assertEquals(null, result.steps.filterIsInstance<RestStep>().single().activeExerciseId)
        assertEquals(listOf(CompileWarning.EmptyActiveRestPool(0, 0)), result.warnings)
    }

    @Test
    fun activeRestLongerThanRestIsReportedOnce() {
        // Quad stretch: 30s hold per side = 60s, but only 45s of rest.
        val item = WorkoutItem(squat.id, sets = 3, restSeconds = 45, activeRest = ActiveRest.Specific(quadStretch.id))
        val result = WorkoutCompiler.compile(template(WorkoutBlock(items = listOf(item))), library)
        assertEquals(listOf(CompileWarning.ActiveRestTooLong(0, 0, quadStretch.id, 60, 45)), result.warnings)
    }

    @Test
    fun activeRestWithoutRestTimeIsReported() {
        val item = WorkoutItem(squat.id, sets = 2, restSeconds = 0, activeRest = ActiveRest.Specific(chestOpener.id))
        val result = WorkoutCompiler.compile(template(WorkoutBlock(items = listOf(item))), library)
        assertEquals(listOf(CompileWarning.NoRestTimeForActiveRest(0, 0)), result.warnings)
    }

    @Test
    fun missingExercisesAreReportedAndSkipped() {
        val block = WorkoutBlock(items = listOf(WorkoutItem(999, sets = 1), WorkoutItem(squat.id, sets = 1)))
        val empty = WorkoutBlock(items = listOf(WorkoutItem(998)))
        val result = WorkoutCompiler.compile(template(block, empty), library)
        assertEquals(listOf(squat.id), result.steps.filterIsInstance<WorkStep>().map { it.exerciseId })
        assertEquals(
            listOf(
                CompileWarning.MissingExercise(0, 0),
                CompileWarning.MissingExercise(1, 0),
                CompileWarning.EmptyBlock(1),
            ),
            result.warnings,
        )
    }

    @Test
    fun deletedSpecificActiveRestIsReported() {
        val item = WorkoutItem(squat.id, sets = 2, restSeconds = 60, activeRest = ActiveRest.Specific(999))
        val result = WorkoutCompiler.compile(template(WorkoutBlock(items = listOf(item))), library)
        assertEquals(null, result.steps.filterIsInstance<RestStep>().single().activeExerciseId)
        assertEquals(listOf(CompileWarning.MissingActiveRestExercise(0, 0)), result.warnings)
    }

    @Test
    fun timedUnilateralExerciseRunsEachSide() {
        val item = WorkoutItem(sidePlank.id, sets = 1, target = SetTarget(seconds = 30))
        val result = WorkoutCompiler.compile(template(WorkoutBlock(items = listOf(item))), library)
        val work = result.steps.filterIsInstance<WorkStep>()
        assertEquals(listOf(Side.LEFT, Side.RIGHT), work.map { it.side })
        assertTrue(work.all { it.durationSeconds == 30 })
    }

    @Test
    fun estimateAddsTimedStepsAndRepPace() {
        val item = WorkoutItem(squat.id, sets = 2, target = SetTarget(reps = 10), restSeconds = 60)
        val result = WorkoutCompiler.compile(template(WorkoutBlock(items = listOf(item))), library)
        // 2 sets × 10 reps × 3 s + one 60 s rest.
        assertEquals(120, result.estimatedSeconds)
    }
}
