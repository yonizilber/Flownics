package com.yonizilber.flownics.domain.library

import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.Muscle
import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseFilterTest {

    private val squat = Exercise(
        id = 1,
        name = "סקוואט",
        category = ExerciseCategory.STRENGTH,
        primaryMuscles = setOf(Muscle.QUADS, Muscle.GLUTES),
        equipment = "מוט",
    )
    private val hamstringStretch = Exercise(
        id = 2,
        name = "Hamstring stretch",
        category = ExerciseCategory.FLEXIBILITY,
        secondaryMuscles = setOf(Muscle.HAMSTRINGS),
    )
    private val plank = Exercise(id = 3, name = "Plank", category = ExerciseCategory.CORE, primaryMuscles = setOf(Muscle.ABS))
    private val all = listOf(plank, squat, hamstringStretch)

    private val hebrewLabels = mapOf(Muscle.QUADS to "ירך קדמית", Muscle.HAMSTRINGS to "ירך אחורית")
    private val label: (Muscle) -> String = { hebrewLabels[it] ?: it.name }

    @Test
    fun emptyFilterReturnsAllSortedByName() {
        assertEquals(listOf(hamstringStretch, plank, squat), all.applyFilter(ExerciseFilter()))
    }

    @Test
    fun queryMatchesNameCaseInsensitively() {
        assertEquals(listOf(plank), all.applyFilter(ExerciseFilter(query = "  PLA ")))
    }

    @Test
    fun queryMatchesMuscleLabelInHebrew() {
        val result = all.applyFilter(ExerciseFilter(query = "ירך"), label)
        assertEquals(listOf(hamstringStretch, squat), result)
    }

    @Test
    fun queryMatchesEquipment() {
        assertEquals(listOf(squat), all.applyFilter(ExerciseFilter(query = "מוט"), label))
    }

    @Test
    fun allQueryTermsMustMatch() {
        assertEquals(emptyList<Exercise>(), all.applyFilter(ExerciseFilter(query = "plank מוט"), label))
    }

    @Test
    fun categoriesAreOrCombined() {
        val filter = ExerciseFilter(categories = setOf(ExerciseCategory.CORE, ExerciseCategory.FLEXIBILITY))
        assertEquals(listOf(hamstringStretch, plank), all.applyFilter(filter))
    }

    @Test
    fun muscleFilterIncludesSecondaryMuscles() {
        assertEquals(listOf(hamstringStretch), all.applyFilter(ExerciseFilter(muscle = Muscle.HAMSTRINGS)))
    }
}
