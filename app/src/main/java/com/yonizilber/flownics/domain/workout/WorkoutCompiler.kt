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

    /** At the end of its time, wait for the user rather than move on. */
    val holdAtEnd: Boolean
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
    /** A timed set that doesn't move on by itself: at zero it counts on (overtime) until confirmed. */
    override val holdAtEnd: Boolean = false,
    /** A rest exercise that didn't fit in the rests, done at the end ([RestStep.slot]'s queue goes on here). */
    val fromRests: Boolean = false,
    /** With [fromRests]: its place in a round of rest exercises done together; 0 starts one. */
    val restPart: Int = 0,
) : WorkoutStep

/** One exercise done in a rest, with the target it has in the workout it came from. */
data class RestPart(val exerciseId: Long, val target: SetTarget? = null)

/**
 * A rest period; with [active] it is active rest: do that exercise while resting, or that round of
 * exercises one after the other (a superset of the combined workout). With [holdAtEnd] it waits
 * for the user at zero, counting the extra time, instead of moving on.
 */
data class RestStep(
    override val blockIndex: Int,
    override val durationSeconds: Int,
    val active: List<RestPart> = emptyList(),
    override val holdAtEnd: Boolean = false,
    /**
     * A rest between sets or rounds, where active rest may go. False for the short rest between
     * the exercises of a round (a superset or circuit), which stays plain whatever is changed.
     */
    val slot: Boolean = true,
) : WorkoutStep {
    /** The (first) exercise done in this rest. */
    val activeExerciseId: Long? get() = active.firstOrNull()?.exerciseId

    /** A plain rest again: nothing done in it, nor waiting at zero. */
    fun plain(): RestStep = copy(active = emptyList(), holdAtEnd = false)
}

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
        /** How often each pool exercise was used so far, so the rotation varies. */
        private val poolUses = mutableMapOf<Long, Int>()

        /** The rest-filler workout's sets still to come, each with the rest that followed it there. */
        private val filler = ArrayDeque<List<WorkoutStep>>()

        private var confirmActiveRest = false

        fun run(template: WorkoutTemplate, workouts: Map<Long, WorkoutTemplate> = emptyMap()): CompiledWorkout {
            confirmActiveRest = template.confirmActiveRest
            val fillerSource = template.restFiller?.let { f ->
                workouts[f.workoutId]?.takeIf { it.id != template.id } ?: run {
                    warnings += CompileWarning.MissingRestFiller
                    null
                }
            }
            // The other workout as it would run on its own (its own filler ignored), one entry per exercise.
            fillerSource?.let { source ->
                val f = checkNotNull(template.restFiller)
                // Supersets and circuits of two or more exercises, whose rounds go into a rest whole.
                val together = if (f.roundsTogether) {
                    source.blocks.indices.filter { b ->
                        source.blocks[b].structure == BlockStructure.CIRCUIT && source.blocks[b].items.count { library[it.exerciseId] != null } > 1
                    }.toSet()
                } else {
                    emptySet()
                }
                filler += units(Compilation(library).run(source.copy(restFiller = null)).steps, eachOnce = !f.repeatSets, together)
            }
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
                            addWork(blockIndex, itemIndex, item.exerciseId, set, sets, 1, 1, item.target, item.confirmEnd)
                            addRest(blockIndex, itemIndex, item.restSeconds, item.activeRest, worked(item.exerciseId))
                        }
                    }
                    BlockStructure.CIRCUIT -> {
                        val rounds = block.rounds.coerceAtLeast(1)
                        val blockMuscles = present.flatMap { worked(it.value.exerciseId) }.toSet()
                        for (round in 1..rounds) {
                            present.forEachIndexed { position, (itemIndex, item) ->
                                addWork(blockIndex, itemIndex, item.exerciseId, 1, 1, round, rounds, item.target, item.confirmEnd)
                                val lastInRound = position == present.lastIndex
                                when {
                                    // The short rest between exercises of a round: never active rest.
                                    !lastInRound -> {
                                        if (round == 1 && item.activeRest != ActiveRest.None) {
                                            warnings += CompileWarning.ActiveRestInsideRound(blockIndex, itemIndex)
                                        }
                                        addRest(blockIndex, itemIndex, item.restSeconds, ActiveRest.None, emptySet(), fill = false)
                                    }
                                    // After a round, the last one included: the round's rest (before the
                                    // next round, or before the next block). Only if it has none, the
                                    // last exercise's own rest and active rest.
                                    block.roundRestSeconds > 0 ->
                                        addRest(blockIndex, null, block.roundRestSeconds, block.roundActiveRest.takeIf { it != ActiveRest.None } ?: item.activeRest, blockMuscles)
                                    else -> addRest(blockIndex, itemIndex, item.restSeconds, item.activeRest, worked(item.exerciseId))
                                }
                            }
                        }
                    }
                }
            }
            // After the very last set, a plain rest has nothing to rest for and goes; an active one
            // stays: the last set deserves its recovery as much as the others (a stretch, the next
            // exercise of the combined workout), and what is left of it follows.
            (steps.lastOrNull() as? RestStep)?.let { last ->
                if (last.active.isEmpty()) steps.removeAt(steps.lastIndex)
            }
            val fillerUsed = fillerTotal - filler.size
            var extraTitle: String? = null
            if (fillerSource != null && template.restFiller?.leftoversAtEnd == true && filler.isNotEmpty()) {
                // What didn't fit in the rests is done at the end, as its own block.
                extraTitle = fillerSource.name
                val extraBlock = template.blocks.size
                filler.forEach { unit ->
                    var part = -1
                    unit.forEach { step ->
                        steps += when (step) {
                            is WorkStep -> {
                                if (step.side != Side.RIGHT) part++
                                step.copy(blockIndex = extraBlock, fromRests = true, restPart = part)
                            }
                            // The rests between them are their own, not rests to fill.
                            is RestStep -> step.plain().copy(blockIndex = extraBlock, slot = false)
                        }
                    }
                }
                filler.clear()
            }
            // The end block's own last rest is plain: nothing comes after it.
            if ((steps.lastOrNull() as? RestStep)?.active?.isEmpty() == true) steps.removeAt(steps.lastIndex)
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
         * The other workout's exercises in its order, each a set (both sides of a timed one-sided
         * hold) with the rest after it; in the blocks of [together], a whole round of the superset
         * or circuit is one. With [eachOnce], only the first set of each (the first round of a
         * superset): unlike sets, a filler exercise isn't repeated.
         */
        private fun units(source: List<WorkoutStep>, eachOnce: Boolean, together: Set<Int> = emptySet()): List<List<WorkoutStep>> {
            val units = mutableListOf<MutableList<WorkoutStep>>()
            source.forEach { step ->
                val first = units.lastOrNull()?.first() as? WorkStep
                val sameRound = step is WorkStep && first != null && step.blockIndex in together &&
                    first.blockIndex == step.blockIndex && first.round == step.round
                val startsNew = step is WorkStep && step.side != Side.RIGHT && !sameRound
                if (startsNew || units.isEmpty()) units += mutableListOf(step) else units.last() += step
            }
            val sets = units.filter { it.first() is WorkStep }
            if (!eachOnce) return sets
            return sets
                .filter { unit -> (unit.first() as WorkStep).let { it.set == 1 && it.round == 1 } }
                .distinctBy { unit -> (unit.first() as WorkStep).let { it.blockIndex to it.itemIndex } }
        }

        /** The exercises of a unit of the other workout, each once (one-sided holds have two steps). */
        private fun partsOf(unit: List<WorkoutStep>): List<RestPart> =
            unit.filterIsInstance<WorkStep>().filter { it.side != Side.RIGHT }.map { RestPart(it.exerciseId, it.target) }

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
            confirmEnd: Boolean,
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
                    holdAtEnd = timed && confirmEnd,
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
            // With a rest-filler workout, its next exercise fills the rest, in place of any active
            // rest set here: combining the workouts is the choice that counts.
            val parts = if (fill && filler.isNotEmpty()) {
                partsOf(filler.removeFirst())
            } else {
                listOfNotNull(resolve(activeRest, blockIndex, itemIndex, workedMuscles)?.let { RestPart(it) })
            }
            if (parts.isNotEmpty()) {
                // A round of several needs the time of each of them.
                val each = parts.map { p -> activeRestSeconds(library.getValue(p.exerciseId)) ?: p.target?.seconds?.times(if (library.getValue(p.exerciseId).unilateral) 2 else 1) }
                val needed = if (each.all { it == null }) null else each.sumOf { it ?: 0 }
                if (needed != null && needed > seconds) {
                    warnings += CompileWarning.ActiveRestTooLong(blockIndex, itemIndex, parts.first().exerciseId, needed, seconds)
                }
            }
            // Active rest waits at zero for the user (when the workout says so): a stretch may need longer.
            steps += RestStep(blockIndex, seconds, parts, holdAtEnd = parts.isNotEmpty() && confirmActiveRest, slot = fill)
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
                    if (candidates.isEmpty()) {
                        warnings += CompileWarning.EmptyActiveRestPool(blockIndex, itemIndex)
                        null
                    } else {
                        // The least-used exercise for the worked muscles; once those have all been
                        // used more than another of the pool, that other one, so a pool with a
                        // single match doesn't repeat it in every rest.
                        fun uses(e: Exercise) = poolUses[e.id] ?: 0
                        val bestFocused = focused.minByOrNull(::uses)
                        val bestAny = candidates.minBy(::uses)
                        val pick = if (bestFocused != null && uses(bestFocused) <= uses(bestAny)) bestFocused else bestAny
                        poolUses[pick.id] = uses(pick) + 1
                        pick.id
                    }
                }
            }
    }
}
