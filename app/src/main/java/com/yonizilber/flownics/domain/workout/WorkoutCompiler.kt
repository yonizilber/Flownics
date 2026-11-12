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

    fun compile(template: WorkoutTemplate, library: Map<Long, Exercise>): CompiledWorkout =
        Compilation(library).run(template)

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

        fun run(template: WorkoutTemplate): CompiledWorkout {
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
                                if (!lastInRound || round == rounds) {
                                    addRest(blockIndex, itemIndex, item.restSeconds, item.activeRest, worked(item.exerciseId))
                                } else {
                                    addRest(blockIndex, null, block.roundRestSeconds, block.roundActiveRest, blockMuscles)
                                }
                            }
                        }
                    }
                }
            }
            // Nothing to rest for after the very last set.
            if (steps.lastOrNull() is RestStep) steps.removeAt(steps.lastIndex)
            return CompiledWorkout(
                steps = steps.toList(),
                warnings = warnings.toList(),
                estimatedSeconds = steps.sumOf { estimateSeconds(it, library) },
            )
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
        ) {
            if (seconds <= 0) {
                if (activeRest != ActiveRest.None) warnings += CompileWarning.NoRestTimeForActiveRest(blockIndex, itemIndex)
                return
            }
            val activeId = resolve(activeRest, blockIndex, itemIndex, workedMuscles)
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
