package com.yonizilber.flownics.domain.library

import com.yonizilber.flownics.domain.model.Exercise

/**
 * An exercise and its styles: the same movement done another way (rope or band face pull, wide or
 * close grip). The base is the default; each style is still its own exercise, with its own
 * history, pointing at the base through [Exercise.variationOf].
 */
data class ExerciseFamily(val base: Exercise, val styles: List<Exercise>) {
    val members: List<Exercise> get() = listOf(base) + styles
}

object Families {

    /**
     * The base of [exercise]'s family: follows [Exercise.variationOf] up to an exercise that has
     * none (or whose base is gone). A loop, which the app never makes but a hand-edited file
     * could, stops where it would repeat.
     */
    fun baseOf(exercise: Exercise, byId: Map<Long, Exercise>): Exercise {
        var current = exercise
        val seen = mutableSetOf(current.id)
        while (true) {
            val up = current.variationOf?.let(byId::get) ?: return current
            if (!seen.add(up.id)) return current
            current = up
        }
    }

    /** Every exercise in its family, families sorted by the base's name, styles by name. */
    fun group(library: Collection<Exercise>): List<ExerciseFamily> {
        val byId = library.associateBy { it.id }
        return library.groupBy { baseOf(it, byId).id }
            .map { (baseId, members) ->
                val base = byId.getValue(baseId)
                ExerciseFamily(base, members.filter { it.id != baseId }.sortedBy { it.name.lowercase() })
            }
            .sortedBy { it.base.name.lowercase() }
    }

    /** The family [exercise] belongs to, itself included. */
    fun familyOf(exercise: Exercise, library: Collection<Exercise>): ExerciseFamily {
        val byId = library.associateBy { it.id }
        val base = baseOf(exercise, byId)
        return ExerciseFamily(base, library.filter { it.id != base.id && baseOf(it, byId).id == base.id }.sortedBy { it.name.lowercase() })
    }
}
