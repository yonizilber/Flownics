package com.yonizilber.flownics.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yonizilber.flownics.data.local.db.entity.ExerciseVariationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseVariationDao {
    @Query("SELECT * FROM exercise_variations WHERE exerciseId = :exerciseId ORDER BY atMillis, id")
    fun observeFor(exerciseId: Long): Flow<List<ExerciseVariationEntity>>

    @Query("SELECT * FROM exercise_variations WHERE exerciseId = :exerciseId")
    suspend fun getFor(exerciseId: Long): List<ExerciseVariationEntity>

    @Insert
    suspend fun insertAll(variations: List<ExerciseVariationEntity>)

    @Query("DELETE FROM exercise_variations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE exercise_variations SET addedExerciseId = :exerciseId WHERE id = :id")
    suspend fun markAdded(id: Long, exerciseId: Long)
}
