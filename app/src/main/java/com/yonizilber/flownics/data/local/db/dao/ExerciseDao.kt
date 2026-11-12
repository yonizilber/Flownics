package com.yonizilber.flownics.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.yonizilber.flownics.data.local.db.entity.ExerciseEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseDao {
    @Query("SELECT * FROM exercises")
    fun observeAll(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercises WHERE id = :id")
    fun observe(id: Long): Flow<ExerciseEntity?>

    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun get(id: Long): ExerciseEntity?

    @Query("SELECT * FROM exercises")
    suspend fun getAllEntities(): List<ExerciseEntity>

    @Query("SELECT mediaJson FROM exercises")
    suspend fun getAllMediaJson(): List<String>

    @Insert
    suspend fun insert(entity: ExerciseEntity): Long

    @Update
    suspend fun update(entity: ExerciseEntity)

    @Query("DELETE FROM exercises WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE exercises SET variationOf = :base WHERE id IN (:ids)")
    suspend fun setVariationOf(ids: List<Long>, base: Long?)

    @Query("SELECT id FROM exercises WHERE variationOf = :id")
    suspend fun stylesOf(id: Long): List<Long>
}
