package com.yonizilber.flownics.data.garmin

import com.yonizilber.flownics.domain.fit.HeartPoint
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
            val all = activities.all().map { it.toDomain(json) }
            // A watch recording of a workout is richer than the live heart rate read during it: it replaces it.
            val sensorBySession = all.filter { it.source == ActivitySource.SENSOR && it.attachedSessionId != null }.associateBy { it.attachedSessionId!! }
            all.forEach { a0 ->
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
                            sensorBySession[session.id]?.let { s ->
                                activities.delete(s.id)
                                sensorFile(s.id).delete()
                            }
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

    private fun sensorFile(activityId: Long) = File(context.filesDir, "$SENSOR_DIR/$activityId.csv")

    /** The heart rate of a recording over time: from its FIT file, or the readings of a sensor. */
    suspend fun heart(a: com.yonizilber.flownics.domain.model.Activity): List<HeartPoint> = withContext(Dispatchers.IO) {
        runCatching {
            when (a.source) {
                ActivitySource.FIT -> FitActivity.heart(file(a.id).readBytes())
                ActivitySource.SENSOR -> sensorFile(a.id).readLines().mapNotNull { line ->
                    line.split(',').takeIf { it.size == 2 }?.let { (t, b) -> HeartPoint(t.toLong(), b.toInt()) }
                }
                ActivitySource.MANUAL -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Keeps the live heart rate read during a workout as that workout's recording (unless a watch
     * recording already joined it): totals, zones and set by set, with the readings in a file.
     */
    suspend fun saveSensor(sessionId: Long, points: List<HeartPoint>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (points.size < MIN_SENSOR_POINTS) return@withContext
            val all = activities.all().map { it.toDomain(json) }
            if (all.any { it.attachedSessionId == sessionId }) return@withContext
            val sorted = points.sortedBy { it.atMillis }
            val logs = sessions.logs(sessionId).map { it.toDomain() }
            val activity = com.yonizilber.flownics.domain.model.Activity(
                type = com.yonizilber.flownics.domain.model.ActivityType.OTHER,
                title = "",
                startedAtMillis = sorted.first().atMillis,
                durationSeconds = ((sorted.last().atMillis - sorted.first().atMillis) / 1000).toInt().coerceAtLeast(1),
                avgHeartRate = sorted.map { it.bpm }.average().toInt(),
                maxHeartRate = sorted.maxOf { it.bpm },
                source = ActivitySource.SENSOR,
                attachedSessionId = sessionId,
                metrics = ActivityMetrics(
                    sets = WorkoutHeart.sets(logs, sorted),
                    hrHistogram = com.yonizilber.flownics.domain.fit.HeartZones.histogram(sorted),
                    samples = sorted.size,
                ),
            )
            val id = activities.insert(activity.toEntity(json))
            File(context.filesDir, SENSOR_DIR).mkdirs()
            sensorFile(id).writeText(sorted.joinToString("\n") { "${it.atMillis},${it.bpm}" })
        }
    }

    private companion object {
        const val SENSOR_DIR = "hr"
        const val MIN_SENSOR_POINTS = 10
    }
}
