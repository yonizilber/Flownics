package com.yonizilber.flownics.data.mapper

import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.ExerciseMedia
import com.yonizilber.flownics.domain.model.ExerciseStage
import com.yonizilber.flownics.domain.model.MeasureMode
import com.yonizilber.flownics.domain.model.MediaKind
import com.yonizilber.flownics.domain.model.Muscle
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExerciseMappersTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun musclesEncodeWithDelimitersForSafeLikeQueries() {
        assertEquals(",CHEST,TRICEPS,", encodeMuscles(setOf(Muscle.TRICEPS, Muscle.CHEST)))
        assertEquals("", encodeMuscles(emptySet()))
        assertEquals(setOf(Muscle.CHEST, Muscle.TRICEPS), decodeMuscles(",CHEST,TRICEPS,"))
        assertEquals(emptySet<Muscle>(), decodeMuscles(""))
    }

    @Test
    fun exerciseRoundTripsThroughEntity() {
        val exercise = Exercise(
            id = 7,
            name = "Bulgarian split squat",
            category = ExerciseCategory.STRENGTH,
            primaryMuscles = setOf(Muscle.QUADS, Muscle.GLUTES),
            secondaryMuscles = setOf(Muscle.ADDUCTORS),
            equipment = "Bench, dumbbells",
            instructions = listOf("Rear foot on the bench", "Lower until the back knee nears the floor"),
            level = 3,
            variationOf = 2,
            cues = listOf("Front knee tracks over toes", "Torso slightly forward"),
            stages = listOf(ExerciseStage("Lower", "3 seconds down", 3), ExerciseStage("Drive up")),
            media = listOf(ExerciseMedia(MediaKind.IMAGE, "a.jpg"), ExerciseMedia(MediaKind.LINK, "https://youtu.be/x")),
            measureMode = MeasureMode.REPS_WEIGHT,
            unilateral = true,
            notes = "Start light",
            createdAtMillis = 10,
            updatedAtMillis = 20,
        )
        assertEquals(exercise, exercise.toEntity(json).toDomain(json))
    }

    @Test
    fun unknownCategoryFailsLoudly() {
        val entity = Exercise(name = "x", category = ExerciseCategory.CORE).toEntity(json).copy(category = "YOGA")
        assertThrows(IllegalArgumentException::class.java) { entity.toDomain(json) }
    }
}
