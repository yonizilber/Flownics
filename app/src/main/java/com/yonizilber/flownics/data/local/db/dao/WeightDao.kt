package com.yonizilber.flownics.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yonizilber.flownics.data.local.db.entity.WeightEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WeightDao {
    @Query("SELECT * FROM body_weights ORDER BY atMillis")
    fun observeAll(): Flow<List<WeightEntity>>

    @Query("SELECT * FROM body_weights ORDER BY atMillis")
    suspend fun all(): List<WeightEntity>

    /** -1 for a scale reading already stored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: WeightEntity): Long

    @Query("DELETE FROM body_weights WHERE id = :id")
    suspend fun delete(id: Long)
}
