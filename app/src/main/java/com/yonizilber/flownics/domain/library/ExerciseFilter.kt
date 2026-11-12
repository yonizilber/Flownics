package com.yonizilber.flownics.domain.library

import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.Muscle

data class ExerciseFilter(
    val query: String = "",
    val categories: Set<ExerciseCategory> = emptySet(),
    val muscle: Muscle? = null,
) {
    val isActive: Boolean get() = query.isNotBlank() || categories.isNotEmpty() || muscle != null
}

/**
 * Filters in memory: a personal library is at most a few hundred exercises, and this keeps search
 * correct for Hebrew (no SQLite LIKE/collation surprises). [muscleLabel] maps a muscle to the text
 * the user sees, so typing "ירך" finds exercises tagged with that muscle too.
 */
fun List<Exercise>.applyFilter(
    filter: ExerciseFilter,
    muscleLabel: (Muscle) -> String = { it.name },
): List<Exercise> {
    val terms = filter.query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return filter { exercise ->
        val categoryOk = filter.categories.isEmpty() || exercise.categories.any { it in filter.categories }
        val muscleOk = filter.muscle == null ||
            filter.muscle in exercise.primaryMuscles ||
            filter.muscle in exercise.secondaryMuscles
        categoryOk && muscleOk && terms.all { term -> exercise.matches(term, muscleLabel) }
    }.sortedBy { it.name.lowercase() }
}

private fun Exercise.matches(term: String, muscleLabel: (Muscle) -> String): Boolean =
    name.lowercase().contains(term) ||
        equipment.lowercase().contains(term) ||
        (primaryMuscles + secondaryMuscles).any { muscleLabel(it).lowercase().contains(term) }
