package com.yonizilber.flownics.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yonizilber.flownics.data.local.db.entity.RunStructureEntity

@Dao
interface RunStructureDao {
    @Query("SELECT * FROM run_structures WHERE activityId = :id")
    suspend fun get(id: Long): RunStructureEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: RunStructureEntity)

    @Query("DELETE FROM run_structures WHERE activityId = :id")
    suspend fun delete(id: Long)
}
