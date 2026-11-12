package com.yonizilber.flownics.data.repository

import com.yonizilber.flownics.data.local.db.dao.WorkoutTemplateDao
import com.yonizilber.flownics.data.mapper.toDomain
import com.yonizilber.flownics.data.mapper.toEntity
import com.yonizilber.flownics.domain.model.WorkoutTemplate
import com.yonizilber.flownics.domain.repository.WorkoutRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject

class WorkoutRepositoryImpl @Inject constructor(
    private val dao: WorkoutTemplateDao,
    private val json: Json,
) : WorkoutRepository {

    override fun observeAll(): Flow<List<WorkoutTemplate>> =
        dao.observeAll().map { list -> list.map { it.toDomain(json) } }

    override fun observe(id: Long): Flow<WorkoutTemplate?> = dao.observe(id).map { it?.toDomain(json) }

    override suspend fun get(id: Long): WorkoutTemplate? = dao.get(id)?.toDomain(json)

    override suspend fun save(template: WorkoutTemplate): Long {
        val now = System.currentTimeMillis()
        val existing = if (template.id != 0L) dao.get(template.id) else null
        return if (existing == null) {
            dao.insert(template.copy(id = 0, createdAtMillis = now, updatedAtMillis = now).toEntity(json))
        } else {
            dao.update(template.copy(createdAtMillis = existing.createdAtMillis, updatedAtMillis = now).toEntity(json))
            template.id
        }
    }

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun duplicate(id: Long, newName: String): Long {
        val source = get(id) ?: error("Workout $id not found")
        return save(source.copy(id = 0, name = newName))
    }
}
