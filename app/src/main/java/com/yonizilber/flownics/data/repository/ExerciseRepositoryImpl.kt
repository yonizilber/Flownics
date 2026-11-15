package com.yonizilber.flownics.data.repository

import com.yonizilber.flownics.data.local.db.dao.ExerciseDao
import com.yonizilber.flownics.data.local.media.MediaStorage
import com.yonizilber.flownics.data.mapper.decodeMedia
import com.yonizilber.flownics.data.mapper.toDomain
import com.yonizilber.flownics.data.mapper.toEntity
import com.yonizilber.flownics.domain.library.Families
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.MediaKind
import com.yonizilber.flownics.domain.model.TagInfo
import com.yonizilber.flownics.domain.model.Tags
import com.yonizilber.flownics.data.local.db.entity.TagInfoEntity
import com.yonizilber.flownics.domain.repository.ExerciseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject

class ExerciseRepositoryImpl @Inject constructor(
    private val dao: ExerciseDao,
    private val mediaStorage: MediaStorage,
    private val json: Json,
) : ExerciseRepository {

    override fun observeAll(): Flow<List<Exercise>> =
        dao.observeAll().map { list -> list.map { it.toDomain(json) } }

    override fun observe(id: Long): Flow<Exercise?> =
        dao.observe(id).map { it?.toDomain(json) }

    override suspend fun get(id: Long): Exercise? = dao.get(id)?.toDomain(json)

    override suspend fun save(exercise: Exercise): Long {
        val now = System.currentTimeMillis()
        val existing = if (exercise.id != 0L) dao.get(exercise.id)?.toDomain(json) else null
        return if (existing == null) {
            dao.insert(exercise.copy(id = 0, createdAtMillis = now, updatedAtMillis = now).toEntity(json))
        } else {
            dao.update(exercise.copy(createdAtMillis = existing.createdAtMillis, updatedAtMillis = now).toEntity(json))
            // Media removed in the editor is deleted once the edit is saved, not before, so
            // cancelling an edit never loses a photo.
            deleteUnreferenced(existing.localMediaFiles() - exercise.localMediaFiles())
            exercise.id
        }
    }

    override suspend fun delete(id: Long) {
        val existing = dao.get(id)?.toDomain(json) ?: return
        // Deleting a family's base keeps the family: its first style becomes the new default.
        val styles = dao.stylesOf(id).mapNotNull { dao.get(it)?.toDomain(json) }.sortedBy { it.name.lowercase() }
        styles.firstOrNull()?.let { heir ->
            dao.setVariationOf(listOf(heir.id), existing.variationOf)
            dao.setVariationOf(styles.drop(1).map { it.id }, heir.id)
        }
        dao.delete(id)
        deleteUnreferenced(existing.localMediaFiles())
    }

    override suspend fun duplicate(id: Long, newName: String): Long {
        val source = dao.get(id)?.toDomain(json) ?: error("Exercise $id not found")
        // Each exercise owns its own media files, so editing or deleting the copy never touches
        // the original's photos.
        val media = source.media.map { item ->
            if (item.kind == MediaKind.LINK) item else item.copy(location = mediaStorage.copy(item.location))
        }
        return save(source.copy(id = 0, name = newName, media = media))
    }

    override suspend fun setStyleOf(id: Long, baseId: Long?) {
        val all = dao.getAllEntities().map { it.toDomain(json) }
        val byId = all.associateBy { it.id }
        val exercise = byId[id] ?: return
        val base = baseId?.let(byId::get)?.let { Families.baseOf(it, byId) }
        if (base?.id == id) return
        val target = base?.id
        // Its own styles come along, so no family ends up nested inside another.
        dao.setVariationOf(all.filter { it.variationOf == id }.map { it.id }, target ?: exercise.id)
        dao.setVariationOf(listOf(id), target)
    }

    override suspend fun makeFamilyDefault(id: Long) {
        val all = dao.getAllEntities().map { it.toDomain(json) }
        val exercise = all.firstOrNull { it.id == id } ?: return
        val family = Families.familyOf(exercise, all)
        if (family.base.id == id) return
        dao.setVariationOf(family.members.filter { it.id != id }.map { it.id }, id)
        dao.setVariationOf(listOf(id), null)
    }

    override fun observeTagInfos(): Flow<List<TagInfo>> =
        dao.observeTagInfos().map { rows -> rows.map { TagInfo(it.name, it.description) } }

    override suspend fun setTagDescription(name: String, description: String) {
        val key = Tags.key(name)
        if (key.isEmpty()) return
        if (description.isBlank()) dao.deleteTagInfo(key) else dao.upsertTagInfo(TagInfoEntity(key, name.trim(), description.trim()))
    }

    override suspend fun renameTag(from: String, to: String) {
        val fromKey = Tags.key(from)
        val name = to.trim()
        if (fromKey.isEmpty() || name.isEmpty() || fromKey == Tags.key(name) && from.trim() == name) return
        val toKey = Tags.key(name)
        dao.getAllEntities().map { it.toDomain(json) }.forEach { e ->
            val old = e.tags.firstOrNull { Tags.key(it.name) == fromKey } ?: return@forEach
            // Already carries the new name: the two become one, with both notes.
            val other = e.tags.firstOrNull { Tags.key(it.name) == toKey && toKey != fromKey }
            val tags = if (other != null) {
                e.tags.filter { it !== old }.map { t -> if (t === other) t.copy(note = listOf(t.note, old.note).filter { it.isNotBlank() }.joinToString("; ")) else t }
            } else {
                e.tags.map { t -> if (t === old) t.copy(name = name) else t }
            }
            dao.update(e.copy(tags = tags).toEntity(json))
        }
        val info = dao.getTagInfos().firstOrNull { it.key == fromKey }
        if (info != null) {
            dao.deleteTagInfo(fromKey)
            val target = dao.getTagInfos().firstOrNull { it.key == Tags.key(name) }
            val description = listOfNotNull(target?.description, info.description).filter { it.isNotBlank() }.joinToString("\n")
            dao.upsertTagInfo(TagInfoEntity(Tags.key(name), name, description))
        }
    }

    private suspend fun deleteUnreferenced(candidates: Set<String>) {
        if (candidates.isEmpty()) return
        val stillUsed = dao.getAllMediaJson()
            .flatMap { decodeMedia(json, it) }
            .filter { it.kind != MediaKind.LINK }
            .map { it.location }
            .toSet()
        mediaStorage.delete(candidates - stillUsed)
    }

    private fun Exercise.localMediaFiles(): Set<String> =
        media.filter { it.kind != MediaKind.LINK }.map { it.location }.toSet()
}
