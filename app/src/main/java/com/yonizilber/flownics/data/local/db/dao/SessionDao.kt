package com.yonizilber.flownics.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yonizilber.flownics.data.local.db.entity.SessionHeaderRow
import com.yonizilber.flownics.data.local.db.entity.SetLogEntity
import com.yonizilber.flownics.data.local.db.entity.WorkoutSessionEntity
import kotlinx.coroutines.flow.Flow

// Status literals in these queries are SessionStatus names.
@Dao
interface SessionDao {
    @Insert
    suspend fun insert(entity: WorkoutSessionEntity): Long

    @Query("SELECT * FROM workout_sessions WHERE status = 'IN_PROGRESS' ORDER BY startedAtMillis DESC LIMIT 1")
    suspend fun inProgress(): WorkoutSessionEntity?

    @Query("UPDATE workout_sessions SET currentIndex = :index WHERE id = :id")
    suspend fun updateIndex(id: Long, index: Int)

    @Query("UPDATE workout_sessions SET status = 'COMPLETED', endedAtMillis = :endedAtMillis, notes = :notes WHERE id = :id")
    suspend fun complete(id: Long, endedAtMillis: Long, notes: String)

    @Query("DELETE FROM workout_sessions WHERE id = :id")
    suspend fun delete(id: Long)

    // REPLACE relies on the unique (sessionId, stepIndex) index: redoing a step overwrites its log.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLog(log: SetLogEntity)

    @Query("SELECT * FROM set_logs WHERE sessionId = :sessionId ORDER BY stepIndex")
    suspend fun logs(sessionId: Long): List<SetLogEntity>

    @Query(
        "SELECT l.* FROM set_logs l INNER JOIN workout_sessions s ON s.id = l.sessionId " +
            "WHERE l.exerciseId = :exerciseId AND l.skipped = 0 AND s.status = 'COMPLETED' " +
            "ORDER BY l.completedAtMillis DESC LIMIT 1",
    )
    suspend fun lastLog(exerciseId: Long): SetLogEntity?

    // History reads below cover finished sessions only; the one in progress belongs to the player.

    @Query(
        "SELECT id, templateId, workoutName, startedAtMillis, endedAtMillis, notes FROM workout_sessions " +
            "WHERE status = 'COMPLETED' ORDER BY startedAtMillis DESC",
    )
    fun observeCompleted(): Flow<List<SessionHeaderRow>>

    @Query(
        "SELECT l.* FROM set_logs l INNER JOIN workout_sessions s ON s.id = l.sessionId " +
            "WHERE s.status = 'COMPLETED' ORDER BY l.completedAtMillis",
    )
    fun observeCompletedLogs(): Flow<List<SetLogEntity>>

    @Query(
        "SELECT l.* FROM set_logs l INNER JOIN workout_sessions s ON s.id = l.sessionId " +
            "WHERE l.exerciseId = :exerciseId AND s.status = 'COMPLETED' ORDER BY l.completedAtMillis",
    )
    fun observeExerciseLogs(exerciseId: Long): Flow<List<SetLogEntity>>

    @Query("SELECT * FROM workout_sessions WHERE id = :id")
    fun observe(id: Long): Flow<WorkoutSessionEntity?>

    @Query("SELECT * FROM set_logs WHERE sessionId = :sessionId ORDER BY stepIndex")
    fun observeLogs(sessionId: Long): Flow<List<SetLogEntity>>

    @Query("UPDATE workout_sessions SET notes = :notes WHERE id = :id")
    suspend fun updateNotes(id: Long, notes: String)
}
