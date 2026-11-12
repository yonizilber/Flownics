package com.yonizilber.flownics.domain.repository

import com.yonizilber.flownics.domain.model.WorkoutTemplate
import kotlinx.coroutines.flow.Flow

interface WorkoutRepository {
    /** Most recently edited first. */
    fun observeAll(): Flow<List<WorkoutTemplate>>

    fun observe(id: Long): Flow<WorkoutTemplate?>

    suspend fun get(id: Long): WorkoutTemplate?

    /** Inserts when [WorkoutTemplate.id] is 0, otherwise updates. Returns the saved id. */
    suspend fun save(template: WorkoutTemplate): Long

    suspend fun delete(id: Long)

    suspend fun duplicate(id: Long, newName: String): Long
}
