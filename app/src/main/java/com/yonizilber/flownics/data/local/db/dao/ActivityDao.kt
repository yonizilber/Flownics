package com.yonizilber.flownics.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.yonizilber.flownics.data.local.db.entity.ActivityEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activities ORDER BY startedAtMillis DESC")
    fun observeAll(): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun get(id: Long): ActivityEntity?

    @Insert
    suspend fun insert(activity: ActivityEntity): Long

    @Update
    suspend fun update(activity: ActivityEntity)

    @Query("SELECT * FROM activities ORDER BY startedAtMillis")
    suspend fun all(): List<ActivityEntity>

    @Query("SELECT * FROM activities WHERE externalId = :externalId LIMIT 1")
    suspend fun byExternalId(externalId: String): ActivityEntity?

    @Query("DELETE FROM activities WHERE id = :id")
    suspend fun delete(id: Long)
}
