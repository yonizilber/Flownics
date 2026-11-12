package com.yonizilber.flownics.data.garmin

import android.content.Context
import com.yonizilber.flownics.data.local.db.dao.ActivityDao
import com.yonizilber.flownics.data.local.db.dao.SessionDao
import com.yonizilber.flownics.data.mapper.toDomain
import com.yonizilber.flownics.data.repository.toDomain
import com.yonizilber.flownics.data.repository.toEntity
import com.yonizilber.flownics.domain.fit.FitActivity
import com.yonizilber.flownics.domain.fit.WorkoutHeart
import com.yonizilber.flownics.domain.model.ActivityMetrics
import com.yonizilber.flownics.domain.model.ActivitySource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Joins a watch recording to the workout it was made during: when a recording and a workout from
 * the app overlap by at least half of the shorter one, the recording belongs to that workout. It
 * then isn't listed as a second activity; the workout shows its heart rate, set by set.
 *
 * Runs after every import and every saved or deleted workout, and is safe to run any time: it
 * also lets go of recordings whose workout was deleted, and never touches one the user kept apart.
 */
@Singleton
class RecordingLinker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val activities: ActivityDao,
    private val sessions: SessionDao,
    private val json: Json,
) {
    private val mutex = Mutex()

    suspend fun link() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val done = sessions.observeCompleted().first().map { it.toDomain() }
            val ids = done.map { it.id }.toSet()
            activities.all().map { it.toDomain(json) }.forEach { a0 ->
                // Recordings imported before heart-rate zones existed get their histogram now.
                val a = backfill(a0)
                when {
                    // Its workout was deleted: it stands on its own again.
                    a.attachedSessionId != null && a.attachedSessionId !in ids ->
                        activities.update(a.copy(attachedSessionId = null, metrics = a.metrics?.copy(sets = emptyList())).toEntity(json))
                    a.attachedSessionId == null && !a.keepSeparate && a.source == ActivitySource.FIT -> {
                        val session = done.filter { s ->
                            WorkoutHeart.overlap(a.startedAtMillis, a.endedAtMillis, s.startedAtMillis, s.endedAtMillis) >= WorkoutHeart.MIN_OVERLAP
                        }.maxByOrNull { s -> WorkoutHeart.overlap(a.startedAtMillis, a.endedAtMillis, s.startedAtMillis, s.endedAtMillis) }
                        if (session != null) {
                            val heart = runCatching { FitActivity.heart(file(a.id).readBytes()) }.getOrDefault(emptyList())
                            val sets = WorkoutHeart.sets(sessions.logs(session.id).map { it.toDomain() }, heart)
                            activities.update(a.copy(attachedSessionId = session.id, metrics = (a.metrics ?: ActivityMetrics()).copy(sets = sets)).toEntity(json))
                        }
                    }
                }
            }
        }
    }

    private suspend fun backfill(a: com.yonizilber.flownics.domain.model.Activity): com.yonizilber.flownics.domain.model.Activity {
        if (a.source != ActivitySource.FIT || a.metrics == null || a.metrics.hrHistogram.isNotEmpty()) return a
        val f = file(a.id)
        if (!f.exists()) return a
        val histogram = runCatching { com.yonizilber.flownics.domain.fit.HeartZones.histogram(FitActivity.heart(f.readBytes())) }.getOrNull() ?: return a
        val updated = a.copy(metrics = a.metrics.copy(hrHistogram = histogram))
        activities.update(updated.toEntity(json))
        return updated
    }

    /** Keeps a recording apart from the workout it overlaps (the user's choice; it isn't joined again). */
    suspend fun keepApart(activityId: Long) = mutex.withLock {
        withContext(Dispatchers.IO) {
            activities.get(activityId)?.toDomain(json)?.let { a ->
                activities.update(a.copy(attachedSessionId = null, keepSeparate = true, metrics = a.metrics?.copy(sets = emptyList())).toEntity(json))
            }
        }
    }

    /** The recording on this phone, kept when it was imported. */
    fun file(activityId: Long) = File(context.filesDir, "${FitImporter.FIT_DIR}/$activityId.fit")
}
