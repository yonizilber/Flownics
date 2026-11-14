package com.yonizilber.flownics.domain.player

import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.MeasureMode
import com.yonizilber.flownics.domain.model.SetTarget
import com.yonizilber.flownics.domain.workout.RestStep
import com.yonizilber.flownics.domain.workout.Side
import com.yonizilber.flownics.domain.workout.WorkStep
import com.yonizilber.flownics.domain.workout.WorkoutStep

/** Steps after an edit, and where the steps from [insertedAt] on moved by [shift] (for the logs). */
data class EditResult(val steps: List<WorkoutStep>, val insertedAt: Int, val shift: Int)

/**
 * Changes to a workout while it runs, because the day rarely goes as planned. Each takes the
 * steps and the current position and gives the new steps; nothing done so far moves. Pure, so it
 * is unit-tested.
 */
object SessionEdits {

    /** One more set of the current (or just done) exercise, right after it, with the same rest. */
    fun addSet(steps: List<WorkoutStep>, index: Int): EditResult? {
        val workIndex = (minOf(index, steps.lastIndex) downTo 0).firstOrNull { steps[it] is WorkStep } ?: return null
        val work = steps[workIndex] as WorkStep
        // Both sides of a one-sided timed set go together.
        val first = if (work.side == Side.RIGHT && (steps.getOrNull(workIndex - 1) as? WorkStep)?.side == Side.LEFT) workIndex - 1 else workIndex
        val last = if ((steps[first] as WorkStep).side == Side.LEFT && (steps.getOrNull(first + 1) as? WorkStep)?.side == Side.RIGHT) first + 1 else first
        val group = (first..last).map { steps[it] as WorkStep }
        val rest = steps.getOrNull(last + 1) as? RestStep
        val after = last + 1 + if (rest != null) 1 else 0
        val added = group.map { it.copy(set = it.set + 1, totalSets = it.totalSets + 1) } + listOfNotNull(rest?.plain())
        val result = insert(steps, maxOf(after, index + 1), added)
        // The exercise has one set more: every set of it says so, and the later ones move up one.
        fun same(s: WorkStep) = s.exerciseId == work.exerciseId && s.blockIndex == work.blockIndex && s.itemIndex == work.itemIndex && s.round == work.round
        val renumbered = result.steps.mapIndexed { i, s ->
            if (s !is WorkStep || !same(s)) {
                s
            } else {
                val later = i >= result.insertedAt + result.shift
                s.copy(set = if (later) s.set + 1 else s.set, totalSets = if (i in result.insertedAt until result.insertedAt + result.shift) s.totalSets else s.totalSets + 1)
            }
        }
        return result.copy(steps = renumbered)
    }

    /** A new exercise added during the workout: [sets] sets (both sides when one-sided and timed), each with [restSeconds]. */
    fun exerciseSteps(exercise: Exercise, sets: Int, target: SetTarget, restSeconds: Int, blockIndex: Int): List<WorkoutStep> {
        val timed = exercise.measureMode == MeasureMode.TIME && (target.seconds ?: 0) > 0
        val sides = if (exercise.unilateral && timed) listOf(Side.LEFT, Side.RIGHT) else listOf(null)
        return (1..sets.coerceAtLeast(1)).flatMap { set ->
            sides.map { side ->
                WorkStep(blockIndex, 0, exercise.id, set, sets, 1, 1, target, side, if (timed) target.seconds else null)
            } + listOfNotNull(if (restSeconds > 0) RestStep(blockIndex, restSeconds) else null)
        }
    }

    /** [added] goes after the current step and its rest, or at the end when the workout was over. */
    fun addAfterCurrent(steps: List<WorkoutStep>, index: Int, finished: Boolean, added: List<WorkoutStep>): EditResult {
        val at = when {
            finished || index >= steps.size -> steps.size
            steps.getOrNull(index + 1) is RestStep && steps[index] is WorkStep -> index + 2
            else -> index + 1
        }
        return insert(steps, at, added)
    }

    private fun insert(steps: List<WorkoutStep>, at: Int, added: List<WorkoutStep>): EditResult {
        val clamped = at.coerceIn(0, steps.size)
        return EditResult(steps.take(clamped) + added + steps.drop(clamped), clamped, added.size)
    }
}
