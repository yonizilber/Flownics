package com.yonizilber.flownics.data.transfer

import com.yonizilber.flownics.domain.coach.CoachProfile
import com.yonizilber.flownics.data.local.db.entity.CoachNoteEntity
import com.yonizilber.flownics.data.local.db.entity.CoachMessageEntity
import com.yonizilber.flownics.data.local.db.entity.CoachConversationEntity
import com.yonizilber.flownics.data.coach.CoachStore
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.yonizilber.flownics.BuildConfig
import com.yonizilber.flownics.data.local.db.FlownicsDatabase
import com.yonizilber.flownics.data.local.db.dao.TransferDao
import com.yonizilber.flownics.data.local.db.entity.MediaJson
import com.yonizilber.flownics.data.local.media.MediaStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What to put in a file for friends. Workouts bring the exercises they use. */
data class ShareSelection(val exerciseIds: Set<Long>, val workoutIds: Set<Long>, val includeMedia: Boolean = true)

/** What went into a written file. [missingMedia]: photos or videos the app no longer had, left out. */
data class ExportResult(val exercises: Int, val workouts: Int, val sessions: Int, val mediaFiles: Int, val missingMedia: Int)

/** A checked file waiting for the user's go-ahead. Nothing in the app has changed yet. */
class ImportPreview internal constructor(
    val kind: BundleKind,
    val createdAtMillis: Long,
    val exercises: Int,
    val workouts: Int,
    val sessions: Int,
    val mediaFiles: Int,
    /** Exercises in a shared file that the user already has by name. */
    val clashes: List<String>,
    /** What a restore would replace. */
    val current: Counts,
    internal val bundle: ReadBundle,
    internal val staging: File,
)

data class Counts(val exercises: Int, val workouts: Int, val sessions: Int)

data class ImportResult(val exercisesAdded: Int, val exercisesReused: Int, val workoutsAdded: Int, val sessions: Int)

/** Restoring while a workout is under way would pull the plan out from under the player. */
class WorkoutRunningException : IOException("A workout is in progress")

/**
 * Files in and out: a backup of everything, a smaller file to share with friends, and bringing
 * either back in. A file is fully read and checked (see [BundleReader] and [BundleMerge.validate])
 * before the user is asked, and applied in one database transaction, so it lands whole or not at all.
 */
@Singleton
class DataTransfer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: FlownicsDatabase,
    private val dao: TransferDao,
    private val media: MediaStorage,
    private val json: Json,
    private val coach: CoachStore,
) {
    companion object {
        /** Under cacheDir; exposed to the app the user shares with through the FileProvider. */
        const val SHARE_DIR = "share"
        private const val IMPORT_DIR = "import"
    }

    private val writer = BundleWriter(json)
    private val reader = BundleReader(json)

    /** Everything except AI keys and an unfinished workout. */
    suspend fun exportBackup(out: OutputStream): ExportResult = withContext(Dispatchers.IO) {
        val logs = dao.completedLogs().groupBy { it.sessionId }
        val data = BundleData(
            exercises = dao.exercises().map { it.toDto(json) },
            variations = dao.variations().map { it.toDto() },
            aiNotes = dao.aiNotes().map { it.toDto(json) },
            workouts = dao.workouts().map { it.toDto(json) },
            sessions = dao.completedSessions().map { it.toDto(json, logs[it.id].orEmpty()) },
            aiUsage = dao.aiUsage().map { it.toDto() },
            activities = dao.activities().map { it.toDto(json) },
            coachConversations = dao.coachMessages().groupBy { it.conversationId }.let { messages ->
                dao.coachConversations().map { c ->
                    CoachConversationDto(c.id, c.title, c.createdAtMillis, c.updatedAtMillis, messages[c.id].orEmpty().map { CoachMessageDto(it.atMillis, it.kind, it.text, it.dataJson) })
                }
            },
            coachNotes = dao.coachNotes().map { CoachNoteDto(it.id, it.text, it.createdAtMillis) },
            coachProfile = coach.profile.value.let { CoachProfileDto(it.goals, it.limitations, it.equipment, it.schedule) },
            plans = dao.plans().map { it.toDto(json) },
        )
        write(out, BundleKind.BACKUP, data, includeMedia = true)
    }

    suspend fun exportShare(selection: ShareSelection, out: OutputStream): ExportResult = withContext(Dispatchers.IO) {
        write(out, BundleKind.SHARE, shareData(selection), selection.includeMedia)
    }

    /** Writes a backup into a document the user created (system "save as"); a failed one is removed. */
    suspend fun exportBackupTo(uri: Uri): ExportResult = writeTo(uri) { exportBackup(it) }

    suspend fun exportShareTo(selection: ShareSelection, uri: Uri): ExportResult = writeTo(uri) { exportShare(selection, it) }

    private suspend fun writeTo(uri: Uri, export: suspend (OutputStream) -> ExportResult): ExportResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        try {
            resolver.openOutputStream(uri, "w").use { out ->
                out ?: throw IOException("Cannot open the file")
                export(out)
            }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw e
        }
    }

    /**
     * A share file in the app's cache, for the system share sheet. Earlier ones are deleted first,
     * so at most one is ever left behind.
     */
    suspend fun shareFile(selection: ShareSelection, title: String): Pair<File, ExportResult> = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, SHARE_DIR)
        dir.deleteRecursively()
        dir.mkdirs()
        val safeTitle = title.map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else '_' }
            .joinToString("").trim().take(60).ifEmpty { "share" }
        val file = File(dir, "Flownics - $safeTitle.zip")
        try {
            val result = file.outputStream().use { exportShare(selection, it) }
            file to result
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    /** Sizes for the share screen: how many exercises a selection really brings, and its media bytes. */
    suspend fun shareSize(selection: ShareSelection): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val data = shareData(selection)
        val bytes = data.exercises.flatMap { it.media }.filter { it.kind != KIND_LINK }
            .sumOf { runCatching { media.file(it.location).length() }.getOrDefault(0L) }
        data.exercises.size to bytes
    }

    private suspend fun shareData(selection: ShareSelection): BundleData {
        val workouts = dao.workouts().filter { it.id in selection.workoutIds }.map { it.toDto(json) }
        val wanted = selection.exerciseIds + workouts.flatMap { BundleMerge.referencedExercises(it.blocks) }
        val exercises = dao.exercises().filter { it.id in wanted }.map { it.toDto(json) }
        val ids = exercises.map { it.id }.toSet()
        return BundleData(
            // A family link to an exercise that isn't coming along would point at nothing.
            exercises = exercises.map { if (it.variationOf in ids) it else it.copy(variationOf = null) },
            variations = dao.variations().filter { it.exerciseId in ids }.map { v ->
                v.toDto().let { if (it.addedExerciseId in ids) it else it.copy(addedExerciseId = null) }
            },
            aiNotes = dao.aiNotes().filter { it.exerciseId in ids }.map { it.toDto(json) },
            workouts = workouts,
        )
    }

    private fun write(out: OutputStream, kind: BundleKind, data: BundleData, includeMedia: Boolean): ExportResult {
        val files = mutableMapOf<String, File>()
        var missing = 0
        val exercises = data.exercises.map { exercise ->
            val kept = exercise.media.filter { m ->
                when {
                    m.kind == KIND_LINK -> true
                    !includeMedia -> false
                    else -> {
                        val file = runCatching { media.file(m.location) }.getOrNull()
                        if (file != null && file.isFile && isSafeMediaName(m.location)) {
                            files[m.location] = file
                            true
                        } else {
                            missing++
                            false
                        }
                    }
                }
            }
            exercise.copy(media = kept)
        }
        val manifest = BundleManifest(kind = kind, createdAtMillis = System.currentTimeMillis(), appVersion = BuildConfig.VERSION_NAME)
        writer.write(out, manifest, data.copy(exercises = exercises), files)
        return ExportResult(exercises.size, data.workouts.size, data.sessions.size, files.size, missing)
    }

    /** Reads and checks a file; throws [BundleException] (or IOException) if it can't be used. */
    suspend fun read(uri: Uri): ImportPreview = withContext(Dispatchers.IO) {
        // One preview at a time: anything still here is left over from a preview the app never finished.
        File(context.cacheDir, IMPORT_DIR).deleteRecursively()
        val staging = File(context.cacheDir, "$IMPORT_DIR/${UUID.randomUUID()}")
        try {
            val bundle = context.contentResolver.openInputStream(uri).use { input ->
                input ?: throw IOException("Cannot open the file")
                reader.read(input, staging)
            }
            BundleMerge.validate(bundle.data, bundle.manifest.kind, json)
            val mine = dao.exercises().map { it.id to it.name }
            ImportPreview(
                kind = bundle.manifest.kind,
                createdAtMillis = bundle.manifest.createdAtMillis,
                exercises = bundle.data.exercises.size,
                workouts = bundle.data.workouts.size,
                sessions = bundle.data.sessions.size,
                mediaFiles = bundle.media.size,
                clashes = if (bundle.manifest.kind == BundleKind.SHARE) BundleMerge.clashes(bundle.data, mine) else emptyList(),
                current = Counts(mine.size, dao.workouts().size, dao.completedCount()),
                bundle = bundle,
                staging = staging,
            )
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    /** Drops a preview the user didn't go ahead with. */
    fun discard(preview: ImportPreview) {
        preview.staging.deleteRecursively()
    }

    /** Adds a shared file's exercises and workouts to the library; nothing already there changes. */
    suspend fun importShare(preview: ImportPreview, reuseExisting: Boolean): ImportResult = withContext(Dispatchers.IO) {
        check(preview.kind == BundleKind.SHARE)
        val data = preview.bundle.data
        val plan = BundleMerge.planShare(
            data,
            dao.exercises().map { it.id to it.name },
            dao.workouts().map { it.name },
            reuseExisting,
        )
        val created = mutableListOf<String>()
        try {
            val placed = plan.insert.associate { it.id to placeMedia(it.media, preview.bundle, created) }
            db.withTransaction {
                val ids = plan.reuse.toMutableMap()
                plan.insert.forEach { e ->
                    ids[e.id] = dao.insertExercise(e.toEntity(json, id = 0, variationOf = null, media = placed.getValue(e.id)))
                }
                // Family links once every new exercise has its id.
                plan.insert.forEach { e -> e.variationOf?.let { ids[it] }?.let { dao.setVariationOf(ids.getValue(e.id), it) } }
                val added = plan.insert.map { it.id }.toSet()
                dao.insertVariations(
                    data.variations.filter { it.exerciseId in added }
                        .map { it.toEntity(exerciseId = ids.getValue(it.exerciseId), addedExerciseId = it.addedExerciseId?.let(ids::get)) },
                )
                dao.insertNotes(data.aiNotes.filter { it.exerciseId in added }.map { it.toEntity(json, ids.getValue(it.exerciseId)) })
                plan.workouts.forEach { w -> dao.insertWorkout(w.toEntity(json, id = 0, blocks = BundleMerge.remap(w.blocks, ids))) }
            }
        } catch (e: Exception) {
            media.delete(created)
            throw e
        } finally {
            discard(preview)
        }
        ImportResult(plan.insert.size, plan.reuse.size, plan.workouts.size, 0)
    }

    /** Replaces everything in the app with a backup. Refused while a workout is under way. */
    suspend fun restore(preview: ImportPreview): ImportResult = withContext(Dispatchers.IO) {
        check(preview.kind == BundleKind.BACKUP)
        if (dao.inProgressCount() > 0) throw WorkoutRunningException()
        val data = preview.bundle.data
        val created = mutableListOf<String>()
        try {
            val exercises = data.exercises.map { it.toEntity(json, media = placeMedia(it.media, preview.bundle, created)) }
            db.withTransaction {
                dao.deleteSessions()
                dao.deleteWorkouts()
                dao.deleteExercises()
                dao.deleteUsage()
                dao.deleteActivities()
                dao.deleteCoachConversations()
                dao.deleteCoachNotes()
                // Calendar links stay: the next sync updates each event to what its item is now,
                // and deletes the events of items the backup doesn't have.
                dao.deletePlans()
                dao.insertExercises(exercises)
                dao.insertVariations(data.variations.map { it.toEntity() })
                dao.insertNotes(data.aiNotes.map { it.toEntity(json) })
                dao.insertWorkouts(data.workouts.map { it.toEntity(json) })
                dao.insertSessions(data.sessions.map { it.toEntity(json) })
                dao.insertLogs(data.sessions.flatMap { s -> s.logs.map { it.toEntity(s.id) } })
                dao.insertUsage(data.aiUsage.map { it.toEntity() })
                dao.insertActivities(data.activities.map { it.toEntity(json) })
                dao.insertCoachConversations(data.coachConversations.map { CoachConversationEntity(it.id, it.title, it.createdAtMillis, it.updatedAtMillis) })
                dao.insertCoachMessages(
                    data.coachConversations.flatMap { c -> c.messages.map { CoachMessageEntity(conversationId = c.id, atMillis = it.atMillis, kind = it.kind, text = it.text, dataJson = it.dataJson) } },
                )
                dao.insertCoachNotes(data.coachNotes.map { CoachNoteEntity(it.id, it.text, it.createdAtMillis) })
                dao.insertPlans(data.plans.map { it.toEntity(json) })
            }
            // A backup from before the coach existed has no profile: the current one stays.
            data.coachProfile?.let { coach.saveProfile(CoachProfile(it.goals, it.limitations, it.equipment, it.schedule)) }
        } catch (e: Exception) {
            media.delete(created)
            throw e
        } finally {
            discard(preview)
        }
        // The old photos and videos belong to exercises that no longer exist.
        media.deleteAllExcept(created.toSet())
        ImportResult(data.exercises.size, 0, data.workouts.size, data.sessions.size)
    }

    /** Copies one exercise's media out of the staging folder under new names, recording each in [created]. */
    private suspend fun placeMedia(list: List<MediaJson>, bundle: ReadBundle, created: MutableList<String>): List<MediaJson> =
        list.map { m ->
            if (m.kind == KIND_LINK) {
                m
            } else {
                val staged = bundle.media.getValue(m.location)
                val name = media.adopt(staged, m.location.substringAfterLast('.', if (m.kind == KIND_IMAGE) "jpg" else "mp4"))
                created += name
                m.copy(location = name)
            }
        }
}
