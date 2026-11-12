package com.yonizilber.flownics.data.mapper

import com.yonizilber.flownics.domain.model.ActiveRest
import com.yonizilber.flownics.domain.model.BlockStructure
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.PoolFocus
import com.yonizilber.flownics.domain.model.SetTarget
import com.yonizilber.flownics.domain.model.WorkoutBlock
import com.yonizilber.flownics.domain.model.WorkoutItem
import com.yonizilber.flownics.domain.model.WorkoutTemplate
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutMappersTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun templateRoundTripsThroughEntity() {
        val template = WorkoutTemplate(
            id = 3,
            name = "Legs + stretch",
            description = "Strength with active rest",
            blocks = listOf(
                WorkoutBlock(
                    title = "Main",
                    items = listOf(
                        WorkoutItem(1, sets = 4, target = SetTarget(reps = 8, weightKg = 62.5), restSeconds = 90, activeRest = ActiveRest.Pool()),
                        WorkoutItem(2, sets = 3, target = SetTarget(seconds = 40), restSeconds = 30, activeRest = ActiveRest.Specific(9)),
                    ),
                ),
                WorkoutBlock(
                    title = "Finisher",
                    structure = BlockStructure.CIRCUIT,
                    rounds = 3,
                    roundRestSeconds = 75,
                    roundActiveRest = ActiveRest.Pool(setOf(ExerciseCategory.BREATHING), PoolFocus.ANY),
                    items = listOf(WorkoutItem(4, target = SetTarget(distanceMeters = 200), restSeconds = 0)),
                ),
            ),
            createdAtMillis = 1,
            updatedAtMillis = 2,
        )
        assertEquals(template, template.toEntity(json).toDomain(json))
    }
}
