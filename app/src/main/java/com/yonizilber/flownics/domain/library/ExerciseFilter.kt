package com.yonizilber.flownics.domain.library

import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.Muscle
import com.yonizilber.flownics.domain.model.Tags

data class ExerciseFilter(
    val query: String = "",
    val categories: Set<ExerciseCategory> = emptySet(),
    val muscle: Muscle? = null,
    /** Only exercises carrying this tag (matched ignoring case). */
    val tag: String? = null,
) {
    val isActive: Boolean get() = query.isNotBlank() || categories.isNotEmpty() || muscle != null || tag != null
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
        val tagOk = filter.tag == null || exercise.tags.any { Tags.key(it.name) == Tags.key(filter.tag) }
        categoryOk && muscleOk && tagOk && terms.all { term -> exercise.matches(term, muscleLabel) }
    }.sortedBy { it.name.lowercase() }
}

private fun Exercise.matches(term: String, muscleLabel: (Muscle) -> String): Boolean =
    name.lowercase().contains(term) ||
        equipment.lowercase().contains(term) ||
        tags.any { it.name.lowercase().contains(term) || it.note.lowercase().contains(term) } ||
        (primaryMuscles + secondaryMuscles).any { muscleLabel(it).lowercase().contains(term) }
