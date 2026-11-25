package com.yonizilber.flownics.data.repository

import com.yonizilber.flownics.data.local.db.dao.WeightDao
import com.yonizilber.flownics.data.local.db.entity.WeightEntity
import com.yonizilber.flownics.domain.body.WeightEntry
import com.yonizilber.flownics.domain.body.WeightSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WeightRepository @Inject constructor(private val dao: WeightDao) {

    fun observeAll(): Flow<List<WeightEntry>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun all(): List<WeightEntry> = dao.all().map { it.toDomain() }

    /** Stores the weigh-ins not stored yet; returns how many were new. */
    suspend fun add(entries: List<WeightEntry>): Int = entries.count { dao.insert(it.toEntity()) > 0 }

    suspend fun delete(id: Long) = dao.delete(id)

    private fun WeightEntity.toDomain() = WeightEntry(id, atMillis, kg, WeightSource.valueOf(source), externalKey)

    private fun WeightEntry.toEntity() = WeightEntity(id, atMillis, kg, source.name, key)
}
