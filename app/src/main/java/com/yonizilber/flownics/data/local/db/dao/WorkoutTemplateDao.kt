package com.yonizilber.flownics.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.yonizilber.flownics.data.local.db.entity.WorkoutTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutTemplateDao {
    @Query("SELECT * FROM workout_templates ORDER BY updatedAtMillis DESC")
    fun observeAll(): Flow<List<WorkoutTemplateEntity>>

    @Query("SELECT * FROM workout_templates WHERE id = :id")
    fun observe(id: Long): Flow<WorkoutTemplateEntity?>

    @Query("SELECT * FROM workout_templates WHERE id = :id")
    suspend fun get(id: Long): WorkoutTemplateEntity?

    @Insert
    suspend fun insert(entity: WorkoutTemplateEntity): Long

    @Update
    suspend fun update(entity: WorkoutTemplateEntity)

    @Query("DELETE FROM workout_templates WHERE id = :id")
    suspend fun delete(id: Long)
}
