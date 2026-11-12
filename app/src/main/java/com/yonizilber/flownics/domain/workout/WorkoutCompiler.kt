package com.yonizilber.flownics.domain.workout

import com.yonizilber.flownics.domain.model.ActiveRest
import com.yonizilber.flownics.domain.model.BlockStructure
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.MeasureMode
import com.yonizilber.flownics.domain.model.Muscle
import com.yonizilber.flownics.domain.model.PoolFocus
import com.yonizilber.flownics.domain.model.SetTarget
import com.yonizilber.flownics.domain.model.WorkoutTemplate

enum class Side { LEFT, RIGHT }

sealed interface WorkoutStep {
    val blockIndex: Int

    /** Null means open-ended: the user taps "done" (e.g. a set of 10 reps). */
    val durationSeconds: Int?
}

data class WorkStep(
    override val blockIndex: Int,
    val itemIndex: Int,
    val exerciseId: Long,
    val set: Int,
    val totalSets: Int,
    val round: Int,
    val totalRounds: Int,
    val target: SetTarget,
    val side: Side?,
    override val durationSeconds: Int?,
) : WorkoutStep

/** A rest period; with [activeExerciseId] it is active rest (do that exercise while resting). */
data class RestStep(
    override val blockIndex: Int,
    override val durationSeconds: Int,
    val activeExerciseId: Long?,
) : WorkoutStep

/** Problems are reported, never silently patched: the builder shows each one to the user. */
sealed interface CompileWarning {
    val blockIndex: Int

    data class EmptyBlock(override val blockIndex: Int) : CompileWarning

    data class MissingExercise(override val blockIndex: Int, val itemIndex: Int) : CompileWarning

    /** [itemIndex] is null for a circuit's between-rounds rest. */
    data class MissingActiveRestExercise(override val blockIndex: Int, val itemIndex: Int?) : CompileWarning

    data class EmptyActiveRestPool(override val blockIndex: Int, val itemIndex: Int?) : CompileWarning

    data class NoRestTimeForActiveRest(override val blockIndex: Int, val itemIndex: Int?) : CompileWarning

    /**
     * An item of a superset or circuit has its own active rest, but the rest after it is the short
     * one between exercises of the round: active rest only comes between sets and rounds.
     */
    data class ActiveRestInsideRound(override val blockIndex: Int, val itemIndex: Int) : CompileWarning

    /** The workout chosen to fill the rests is gone. */
    data object MissingRestFiller : CompileWarning {
        override val blockIndex: Int = 0
    }

    data class ActiveRestTooLong(
        override val blockIndex: Int,
        val itemIndex: Int?,
        val exerciseId: Long,
        val neededSeconds: Int,
        val availableSeconds: Int,
    ) : CompileWarning
}

data class CompiledWorkout(
    val steps: List<WorkoutStep>,
    val warnings: List<CompileWarning>,
    val estimatedSeconds: Int,
    /**
     * When the rest-filler workout's leftovers are done at the end: the title of that extra block,
     * whose steps use block index `template.blocks.size`.
     */
    val extraBlockTitle: String? = null,
    /** How many of the rest-filler workout's exercises went into rests, and how many in all. */
    val fillerUsed: Int = 0,
    val fillerTotal: Int = 0,
)

/**
 * Turns a template into the flat list of steps the player runs. Pure and deterministic: the same
 * template and library always give the same steps, including which pool exercise fills each rest.
 */
object WorkoutCompiler {

    // Rough pace for open-ended sets, used only for the duration estimate.
    private const val SECONDS_PER_REP = 3
    private const val UNKNOWN_SET_SECONDS = 45
    private const val MIN_SET_SECONDS = 20

    /** [workouts] is needed only to find a [WorkoutTemplate.restFiller]; without it the rests stay plain. */
    fun compile(template: WorkoutTemplate, library: Map<Long, Exercise>, workouts: Map<Long, WorkoutTemplate> = emptyMap()): CompiledWorkout =
        Compilation(library).run(template, workouts)

    /** Time an exercise needs as active rest, from its timed stages; null when unknown. */
    fun activeRestSeconds(exercise: Exercise): Int? {
        val timed = exercise.stages.mapNotNull { it.durationSeconds }
        if (timed.isEmpty()) return null
        return timed.sum() * if (exercise.unilateral) 2 else 1
    }

    fun estimateSeconds(step: WorkoutStep, library: Map<Long, Exercise>): Int {
        step.durationSeconds?.let { return it }
        val work = step as WorkStep
        val exercise = library[work.exerciseId]
        val reps = work.target.reps ?: return UNKNOWN_SET_SECONDS
        val sides = if (exercise?.unilateral == true) 2 else 1
        return maxOf(MIN_SET_SECONDS, reps * SECONDS_PER_REP * sides)
    }

    private class Compilation(private val library: Map<Long, Exercise>) {
        private val steps = mutableListOf<WorkoutStep>()
        private val warnings = linkedSetOf<CompileWarning>()
        private var poolCursor = 0

        /** The rest-filler workout's sets still to come, each with the rest that followed it there. */
        private val filler = ArrayDeque<List<WorkoutStep>>()

        /** The filler set the latest rest took, while that rest is still the last step. */
        private var lastFillerUnit: List<WorkoutStep>? = null

        fun run(template: WorkoutTemplate, workouts: Map<Long, WorkoutTemplate> = emptyMap()): CompiledWorkout {
            val fillerSource = template.restFiller?.let { f ->
                workouts[f.workoutId]?.takeIf { it.id != template.id } ?: run {
                    warnings += CompileWarning.MissingRestFiller
                    null
                }
            }
            // The other workout as it would run on its own (its own filler ignored), one entry per exercise.
            fillerSource?.let { source -> filler += units(Compilation(library).run(source.copy(restFiller = null)).steps) }
            val fillerTotal = filler.size
            template.blocks.forEachIndexed { blockIndex, block ->
                val present = block.items.withIndex().filter { (itemIndex, item) ->
                    (library[item.exerciseId] != null).also { found ->
                        if (!found) warnings += CompileWarning.MissingExercise(blockIndex, itemIndex)
                    }
                }
                if (present.isEmpty()) {
                    warnings += CompileWarning.EmptyBlock(blockIndex)
                    return@forEachIndexed
                }
                when (block.structure) {
                    BlockStructure.STRAIGHT_SETS -> present.forEach { (itemIndex, item) ->
                        val sets = item.sets.coerceAtLeast(1)
                        for (set in 1..sets) {
                            addWork(blockIndex, itemIndex, item.exerciseId, set, sets, 1, 1, item.target)
                            addRest(blockIndex, itemIndex, item.restSeconds, item.activeRest, worked(item.exerciseId))
                        }
                    }
                    BlockStructure.CIRCUIT -> {
                        val rounds = block.rounds.coerceAtLeast(1)
                        val blockMuscles = present.flatMap { worked(it.value.exerciseId) }.toSet()
                        for (round in 1..rounds) {
                            present.forEachIndexed { position, (itemIndex, item) ->
                                addWork(blockIndex, itemIndex, item.exerciseId, 1, 1, round, rounds, item.target)
                                val lastInRound = position == present.lastIndex
                                when {
                                    // The short rest between exercises of a round: never active rest.
                                    !lastInRound -> {
                                        if (round == 1 && item.activeRest != ActiveRest.None) {
                                            warnings += CompileWarning.ActiveRestInsideRound(blockIndex, itemIndex)
                                        }
                                        addRest(blockIndex, itemIndex, item.restSeconds, ActiveRest.None, emptySet(), fill = false)
                                    }
                                    // After the last round: the rest before the next block.
                                    round == rounds -> addRest(blockIndex, itemIndex, item.restSeconds, item.activeRest, worked(item.exerciseId))
                                    else -> addRest(blockIndex, null, block.roundRestSeconds, block.roundActiveRest, blockMuscles)
                                }
                            }
                        }
                    }
                }
            }
            // Nothing to rest for after the very last set; a stretch it took goes back in line.
            if (steps.lastOrNull() is RestStep) {
                steps.removeAt(steps.lastIndex)
                lastFillerUnit?.let { filler.addFirst(it) }
            }
            val fillerUsed = fillerTotal - filler.size
            var extraTitle: String? = null
            if (fillerSource != null && template.restFiller?.leftoversAtEnd == true && filler.isNotEmpty()) {
                // What didn't fit in the rests is done at the end, as its own block.
                extraTitle = fillerSource.name
                val extraBlock = template.blocks.size
                filler.flatten().forEach { step ->
                    steps += when (step) {
                        is WorkStep -> step.copy(blockIndex = extraBlock)
                        is RestStep -> step.copy(blockIndex = extraBlock)
                    }
                }
                filler.clear()
            }
            // Nothing to rest for after the very last set.
            if (steps.lastOrNull() is RestStep) steps.removeAt(steps.lastIndex)
            return CompiledWorkout(
                steps = steps.toList(),
                warnings = warnings.toList(),
                estimatedSeconds = steps.sumOf { estimateSeconds(it, library) },
                extraBlockTitle = extraTitle,
                fillerUsed = fillerUsed,
                fillerTotal = fillerTotal,
            )
        }

        /**
         * Each exercise of the other workout once, in its order: its first set (both sides of a
         * timed one-sided hold) and the rest after it. Unlike sets, a filler exercise isn't repeated:
         * three rounds of a superset take three different exercises.
         */
        private fun units(source: List<WorkoutStep>): List<List<WorkoutStep>> {
            val units = mutableListOf<MutableList<WorkoutStep>>()
            source.forEach { step ->
                val startsNew = step is WorkStep && step.side != Side.RIGHT
                if (startsNew || units.isEmpty()) units += mutableListOf(step) else units.last() += step
            }
            return units
                .filter { unit -> (unit.first() as? WorkStep)?.let { it.set == 1 && it.round == 1 } ?: false }
                .distinctBy { unit -> (unit.first() as WorkStep).let { it.blockIndex to it.itemIndex } }
        }

        private fun worked(exerciseId: Long): Set<Muscle> =
            library[exerciseId]?.let { it.primaryMuscles + it.secondaryMuscles }.orEmpty()

        private fun addWork(
            blockIndex: Int,
            itemIndex: Int,
            exerciseId: Long,
            set: Int,
            totalSets: Int,
            round: Int,
            totalRounds: Int,
            target: SetTarget,
        ) {
            lastFillerUnit = null
            val exercise = library.getValue(exerciseId)
            val timed = exercise.measureMode == MeasureMode.TIME && (target.seconds ?: 0) > 0
            val sides = if (exercise.unilateral && timed) listOf(Side.LEFT, Side.RIGHT) else listOf(null)
            sides.forEach { side ->
                steps += WorkStep(
                    blockIndex = blockIndex,
                    itemIndex = itemIndex,
                    exerciseId = exerciseId,
                    set = set,
                    totalSets = totalSets,
                    round = round,
                    totalRounds = totalRounds,
                    target = target,
                    side = side,
                    durationSeconds = if (timed) target.seconds else null,
                )
            }
        }

        private fun addRest(
            blockIndex: Int,
            itemIndex: Int?,
            seconds: Int,
            activeRest: ActiveRest,
            workedMuscles: Set<Muscle>,
            /** False for the rest between exercises of a round: the rest-filler workout doesn't go there. */
            fill: Boolean = true,
        ) {
            if (seconds <= 0) {
                if (activeRest != ActiveRest.None) warnings += CompileWarning.NoRestTimeForActiveRest(blockIndex, itemIndex)
                return
            }
            // A rest with nothing of its own takes the next set of the rest-filler workout.
            lastFillerUnit = null
            val activeId = if (fill && activeRest == ActiveRest.None && filler.isNotEmpty()) {
                filler.removeFirst().also { lastFillerUnit = it }.let { (it.first() as WorkStep).exerciseId }
            } else {
                resolve(activeRest, blockIndex, itemIndex, workedMuscles)
            }
            if (activeId != null) {
                val needed = activeRestSeconds(library.getValue(activeId))
                if (needed != null && needed > seconds) {
                    warnings += CompileWarning.ActiveRestTooLong(blockIndex, itemIndex, activeId, needed, seconds)
                }
            }
            steps += RestStep(blockIndex, seconds, activeId)
        }

        private fun resolve(activeRest: ActiveRest, blockIndex: Int, itemIndex: Int?, workedMuscles: Set<Muscle>): Long? =
            when (activeRest) {
                ActiveRest.None -> null
                is ActiveRest.Specific ->
                    if (library.containsKey(activeRest.exerciseId)) {
                        activeRest.exerciseId
                    } else {
                        warnings += CompileWarning.MissingActiveRestExercise(blockIndex, itemIndex)
                        null
                    }
                is ActiveRest.Pool -> {
                    val candidates = library.values
                        .filter { e -> e.categories.any { it in activeRest.categories } }
                        .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
                    val focused = if (activeRest.focus == PoolFocus.WORKED_MUSCLES) {
                        candidates.filter { (it.primaryMuscles + it.secondaryMuscles).any(workedMuscles::contains) }
                    } else {
                        emptyList()
                    }
                    // No stretch matches the worked muscles: rotate through the whole pool rather
                    // than leave the rest empty.
                    val pool = focused.ifEmpty { candidates }
                    if (pool.isEmpty()) {
                        warnings += CompileWarning.EmptyActiveRestPool(blockIndex, itemIndex)
                        null
                    } else {
                        pool[poolCursor++ % pool.size].id
                    }
                }
            }
    }
}
