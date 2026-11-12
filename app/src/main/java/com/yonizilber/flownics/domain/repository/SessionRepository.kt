package com.yonizilber.flownics.domain.repository

import com.yonizilber.flownics.domain.model.SessionHeader
import com.yonizilber.flownics.domain.model.SetLog
import com.yonizilber.flownics.domain.model.WorkoutSession
import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    /** Saves a new session; returns its id. */
    suspend fun create(session: WorkoutSession): Long

    /** The workout left running, if any (at most one at a time). */
    suspend fun inProgress(): WorkoutSession?

    suspend fun updateIndex(sessionId: Long, index: Int)

    /** Inserts, or replaces the log already saved for the same step (after going back and redoing it). */
    suspend fun saveLog(log: SetLog)

    suspend fun logs(sessionId: Long): List<SetLog>

    /** The latest non-skipped set of this exercise from a finished workout. */
    suspend fun lastLog(exerciseId: Long): SetLog?

    suspend fun complete(sessionId: Long, endedAtMillis: Long, notes: String)

    /** Deletes the session and its logs. */
    suspend fun delete(sessionId: Long)

    /** Finished workouts, newest first. */
    fun observeCompleted(): Flow<List<SessionHeader>>

    /** Every set logged in a finished workout, oldest first. */
    fun observeCompletedLogs(): Flow<List<SetLog>>

    /** One exercise's sets from finished workouts, oldest first. */
    fun observeExerciseLogs(exerciseId: Long): Flow<List<SetLog>>

    fun observe(sessionId: Long): Flow<WorkoutSession?>

    fun observeLogs(sessionId: Long): Flow<List<SetLog>>

    suspend fun updateNotes(sessionId: Long, notes: String)
}
