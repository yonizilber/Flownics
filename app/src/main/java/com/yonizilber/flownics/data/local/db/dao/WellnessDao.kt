package com.yonizilber.flownics.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.yonizilber.flownics.data.local.db.entity.GarminRawEntity
import com.yonizilber.flownics.data.local.db.entity.GarminRawIndexRow
import com.yonizilber.flownics.data.local.db.entity.WellnessDayEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WellnessDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRaw(raw: GarminRawEntity)

    @Query("SELECT endpoint, startDate, endDate, fetchedAtMillis FROM garmin_raw")
    suspend fun rawIndex(): List<GarminRawIndexRow>

    @Query("SELECT * FROM garmin_raw WHERE endpoint = :endpoint")
    suspend fun raw(endpoint: String): List<GarminRawEntity>

    @Query("SELECT COUNT(*) FROM garmin_raw")
    suspend fun rawCount(): Int

    @Query("SELECT * FROM wellness_days WHERE date BETWEEN :from AND :to ORDER BY date")
    fun observe(from: String, to: String): Flow<List<WellnessDayEntity>>

    @Query("SELECT * FROM wellness_days WHERE date BETWEEN :from AND :to ORDER BY date")
    suspend fun between(from: String, to: String): List<WellnessDayEntity>

    @Query("DELETE FROM wellness_days")
    suspend fun clearDays()

    @Query("DELETE FROM garmin_raw")
    suspend fun clearRaw()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDays(days: List<WellnessDayEntity>)

    /** The days are always rebuilt whole from the saved answers. */
    @Transaction
    suspend fun replaceDays(days: List<WellnessDayEntity>) {
        clearDays()
        insertDays(days)
    }
}
