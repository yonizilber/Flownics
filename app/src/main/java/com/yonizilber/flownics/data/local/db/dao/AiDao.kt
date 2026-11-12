package com.yonizilber.flownics.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yonizilber.flownics.data.local.db.entity.AiUsageEntity
import com.yonizilber.flownics.data.local.db.entity.ExerciseAiNoteEntity
import com.yonizilber.flownics.data.local.db.entity.FeatureSpendRow
import kotlinx.coroutines.flow.Flow

@Dao
interface AiDao {

    @Insert
    suspend fun insertUsage(usage: AiUsageEntity)

    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM ai_usage WHERE atMillis >= :sinceMillis")
    suspend fun spentSince(sinceMillis: Long): Double

    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM ai_usage WHERE atMillis >= :sinceMillis")
    fun observeSpentSince(sinceMillis: Long): Flow<Double>

    @Query(
        "SELECT feature, SUM(costUsd) AS costUsd, COUNT(*) AS calls FROM ai_usage " +
            "WHERE atMillis >= :sinceMillis GROUP BY feature ORDER BY costUsd DESC",
    )
    fun observeSpendByFeature(sinceMillis: Long): Flow<List<FeatureSpendRow>>

    @Query("SELECT * FROM ai_usage ORDER BY atMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AiUsageEntity>>

    @Insert
    suspend fun insertNote(note: ExerciseAiNoteEntity)

    @Query("SELECT * FROM exercise_ai_notes WHERE exerciseId = :exerciseId ORDER BY atMillis DESC")
    fun observeNotes(exerciseId: Long): Flow<List<ExerciseAiNoteEntity>>
}
