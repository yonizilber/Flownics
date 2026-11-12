package com.yonizilber.flownics.domain.repository

import com.yonizilber.flownics.domain.model.Exercise
import kotlinx.coroutines.flow.Flow

interface ExerciseRepository {
    fun observeAll(): Flow<List<Exercise>>

    fun observe(id: Long): Flow<Exercise?>

    suspend fun get(id: Long): Exercise?

    /** Inserts when [Exercise.id] is 0, otherwise updates. Returns the saved id. */
    suspend fun save(exercise: Exercise): Long

    /** Deletes the exercise and any of its media files that no other exercise uses. */
    suspend fun delete(id: Long)

    /** Copies the exercise (including its media files) under a new name. Returns the new id. */
    suspend fun duplicate(id: Long, newName: String): Long

    /**
     * Makes [id] a style of [baseId]'s family (null: its own exercise again). Styles of [id] move
     * with it, so a family is always one base with styles under it.
     */
    suspend fun setStyleOf(id: Long, baseId: Long?)

    /** Makes [id] the default (base) of its family; the old base becomes one of its styles. */
    suspend fun makeFamilyDefault(id: Long)
}
