package com.yonizilber.flownics.data.repository

import com.yonizilber.flownics.data.garmin.RecordingLinker
import com.yonizilber.flownics.data.local.db.dao.SessionDao
import com.yonizilber.flownics.data.mapper.toDomain
import com.yonizilber.flownics.data.mapper.toEntity
import com.yonizilber.flownics.domain.model.SessionHeader
import com.yonizilber.flownics.domain.model.SetLog
import com.yonizilber.flownics.domain.model.WorkoutSession
import com.yonizilber.flownics.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject

class SessionRepositoryImpl @Inject constructor(
    private val dao: SessionDao,
    private val json: Json,
    private val linker: RecordingLinker,
) : SessionRepository {

    override suspend fun create(session: WorkoutSession): Long = dao.insert(session.copy(id = 0).toEntity(json))

    override suspend fun inProgress(): WorkoutSession? = dao.inProgress()?.toDomain(json)

    override suspend fun updateIndex(sessionId: Long, index: Int) = dao.updateIndex(sessionId, index)

    override suspend fun saveLog(log: SetLog) = dao.upsertLog(log.toEntity())

    override suspend fun logs(sessionId: Long): List<SetLog> = dao.logs(sessionId).map { it.toDomain() }

    override suspend fun lastLog(exerciseId: Long): SetLog? = dao.lastLog(exerciseId)?.toDomain()

    override suspend fun complete(sessionId: Long, endedAtMillis: Long, notes: String) {
        dao.complete(sessionId, endedAtMillis, notes)
        // A watch recording already imported for this time now joins the workout.
        linker.link()
    }

    override suspend fun delete(sessionId: Long) {
        dao.delete(sessionId)
        linker.link()
    }

    override fun observeCompleted(): Flow<List<SessionHeader>> = dao.observeCompleted().map { rows -> rows.map { it.toDomain() } }

    override fun observeCompletedLogs(): Flow<List<SetLog>> = dao.observeCompletedLogs().map { rows -> rows.map { it.toDomain() } }

    override fun observeExerciseLogs(exerciseId: Long): Flow<List<SetLog>> =
        dao.observeExerciseLogs(exerciseId).map { rows -> rows.map { it.toDomain() } }

    override fun observe(sessionId: Long): Flow<WorkoutSession?> = dao.observe(sessionId).map { it?.toDomain(json) }

    override fun observeLogs(sessionId: Long): Flow<List<SetLog>> = dao.observeLogs(sessionId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun updateNotes(sessionId: Long, notes: String) = dao.updateNotes(sessionId, notes)
}
