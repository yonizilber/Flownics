package com.yonizilber.flownics.domain.repository

import com.yonizilber.flownics.domain.ai.AiModel
import com.yonizilber.flownics.domain.ai.ExerciseVariation
import kotlinx.coroutines.flow.Flow

/** A variation kept under an exercise. [addedExerciseId] is set once it was added to the library. */
data class SavedVariation(
    val id: Long,
    val exerciseId: Long,
    val variation: ExerciseVariation,
    val modelId: String,
    val atMillis: Long,
    val addedExerciseId: Long?,
)

interface VariationRepository {
    fun observeFor(exerciseId: Long): Flow<List<SavedVariation>>

    /** Keeps the new ones; a name already saved for this exercise is skipped. Returns how many were new. */
    suspend fun saveNew(exerciseId: Long, variations: List<ExerciseVariation>, model: AiModel): Int

    suspend fun delete(id: Long)

    suspend fun markAdded(id: Long, exerciseId: Long)
}
