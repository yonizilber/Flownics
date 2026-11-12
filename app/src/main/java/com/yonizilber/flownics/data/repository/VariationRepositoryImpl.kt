package com.yonizilber.flownics.data.repository

import com.yonizilber.flownics.data.local.db.dao.ExerciseVariationDao
import com.yonizilber.flownics.data.local.db.entity.ExerciseVariationEntity
import com.yonizilber.flownics.domain.ai.AiModel
import com.yonizilber.flownics.domain.ai.ExerciseVariation
import com.yonizilber.flownics.domain.ai.VariationKind
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.ExerciseLevel
import com.yonizilber.flownics.domain.repository.SavedVariation
import com.yonizilber.flownics.domain.repository.VariationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class VariationRepositoryImpl @Inject constructor(
    private val dao: ExerciseVariationDao,
) : VariationRepository {

    override fun observeFor(exerciseId: Long): Flow<List<SavedVariation>> =
        dao.observeFor(exerciseId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun saveNew(exerciseId: Long, variations: List<ExerciseVariation>, model: AiModel): Int {
        val known = dao.getFor(exerciseId).map { it.name.trim().lowercase() }.toMutableSet()
        val now = System.currentTimeMillis()
        val fresh = variations.filter { known.add(it.name.trim().lowercase()) }
        dao.insertAll(
            fresh.map {
                ExerciseVariationEntity(
                    exerciseId = exerciseId,
                    name = it.name,
                    kind = it.kind.name,
                    difference = it.difference,
                    equipment = it.equipment,
                    category = it.category?.name.orEmpty(),
                    why = it.why,
                    level = it.level,
                    modelId = model.id,
                    atMillis = now,
                    addedExerciseId = null,
                )
            },
        )
        return fresh.size
    }

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun markAdded(id: Long, exerciseId: Long) = dao.markAdded(id, exerciseId)

    private fun ExerciseVariationEntity.toDomain() = SavedVariation(
        id = id,
        exerciseId = exerciseId,
        variation = ExerciseVariation(
            name = name,
            // Written by this app from the same enums; an unknown name means a newer version wrote it.
            kind = VariationKind.valueOf(kind),
            difference = difference,
            equipment = equipment,
            category = category.takeIf { it.isNotEmpty() }?.let { ExerciseCategory.valueOf(it) },
            why = why,
            level = level?.coerceIn(ExerciseLevel.RANGE),
        ),
        modelId = modelId,
        atMillis = atMillis,
        addedExerciseId = addedExerciseId,
    )
}
