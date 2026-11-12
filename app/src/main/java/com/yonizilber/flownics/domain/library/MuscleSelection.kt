package com.yonizilber.flownics.domain.library

import com.yonizilber.flownics.domain.model.Muscle

data class MuscleSelection(
    val primary: Set<Muscle> = emptySet(),
    val secondary: Set<Muscle> = emptySet(),
) {
    /** One tap cycles a muscle: not selected → primary → secondary → not selected. */
    fun cycle(muscle: Muscle): MuscleSelection = when (muscle) {
        in primary -> MuscleSelection(primary - muscle, secondary + muscle)
        in secondary -> MuscleSelection(primary, secondary - muscle)
        else -> MuscleSelection(primary + muscle, secondary)
    }
}

/** Moves the element at [from] to [to], leaving the list unchanged when either index is out of range. */
fun <T> List<T>.move(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
