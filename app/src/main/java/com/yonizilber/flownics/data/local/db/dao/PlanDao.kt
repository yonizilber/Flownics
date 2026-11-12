package com.yonizilber.flownics.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yonizilber.flownics.data.local.db.entity.CalendarLinkEntity
import com.yonizilber.flownics.data.local.db.entity.PlannedWorkoutEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanDao {
    @Query("SELECT * FROM planned_workouts ORDER BY date, time")
    fun observeAll(): Flow<List<PlannedWorkoutEntity>>

    @Query("SELECT * FROM planned_workouts WHERE id = :id")
    suspend fun get(id: Long): PlannedWorkoutEntity?

    @Query("SELECT * FROM planned_workouts WHERE date BETWEEN :from AND :to ORDER BY date, time")
    suspend fun between(from: String, to: String): List<PlannedWorkoutEntity>

    @Insert
    suspend fun insert(plan: PlannedWorkoutEntity): Long

    @Update
    suspend fun update(plan: PlannedWorkoutEntity)

    @Query("DELETE FROM planned_workouts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM calendar_links WHERE itemKey = :key")
    suspend fun link(key: String): CalendarLinkEntity?

    @Query("SELECT * FROM calendar_links")
    suspend fun links(): List<CalendarLinkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putLink(link: CalendarLinkEntity)

    @Query("DELETE FROM calendar_links WHERE itemKey = :key")
    suspend fun deleteLink(key: String)

    @Query("DELETE FROM calendar_links")
    suspend fun deleteAllLinks()
}
