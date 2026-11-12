package com.yonizilber.flownics.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import com.yonizilber.flownics.data.local.db.dao.ActivityDao
import com.yonizilber.flownics.data.local.db.entity.ActivityEntity
import com.yonizilber.flownics.data.local.db.entity.SegmentJson
import com.yonizilber.flownics.domain.model.Activity
import com.yonizilber.flownics.domain.model.ActivityMetrics
import com.yonizilber.flownics.domain.model.ActivitySegment
import com.yonizilber.flownics.domain.model.ActivitySource
import com.yonizilber.flownics.domain.model.ActivityType
import com.yonizilber.flownics.domain.model.SegmentKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Activities logged outside the player. Unknown names (from a newer version) fail loudly in valueOf. */
@Singleton
class ActivityRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ActivityDao,
    private val json: Json,
) {
    /** Activities on their own: recordings attached to a workout are shown with that workout instead. */
    fun observeAll(): Flow<List<Activity>> = dao.observeAll().map { rows -> rows.filter { it.attachedSessionId == null }.map { it.toDomain(json) } }

    /** Recordings made during workouts, by session id. */
    fun observeAttached(): Flow<Map<Long, Activity>> =
        dao.observeAll().map { rows -> rows.filter { it.attachedSessionId != null }.associate { it.attachedSessionId!! to it.toDomain(json) } }

    fun observeAttachedTo(sessionId: Long): Flow<Activity?> = observeAttached().map { it[sessionId] }

    suspend fun get(id: Long): Activity? = dao.get(id)?.toDomain(json)

    /** Inserts when [Activity.id] is 0, otherwise updates. Returns the saved id. */
    suspend fun save(activity: Activity): Long {
        val now = System.currentTimeMillis()
        return if (activity.id == 0L) {
            dao.insert(activity.copy(createdAtMillis = now, updatedAtMillis = now).toEntity(json))
        } else {
            dao.update(activity.copy(updatedAtMillis = now).toEntity(json))
            activity.id
        }
    }

    /** Also deletes the recording kept for it, if any. */
    suspend fun delete(id: Long) {
        dao.delete(id)
        File(context.filesDir, "fit/$id.fit").delete()
        File(context.filesDir, "hr/$id.csv").delete()
    }

    /** The activity already made from this recording, if any. */
    suspend fun byExternalId(externalId: String): Activity? = dao.byExternalId(externalId)?.toDomain(json)
}

private val segmentList = ListSerializer(SegmentJson.serializer())

fun ActivityEntity.toDomain(json: Json) = Activity(
    id = id,
    type = ActivityType.valueOf(type),
    title = title,
    startedAtMillis = startedAtMillis,
    durationSeconds = durationSeconds,
    distanceMeters = distanceMeters,
    avgHeartRate = avgHeartRate,
    maxHeartRate = maxHeartRate,
    elevationGainMeters = elevationGainMeters,
    rpe = rpe,
    notes = notes,
    segments = json.decodeFromString(segmentList, segmentsJson).map {
        ActivitySegment(SegmentKind.valueOf(it.kind), it.durationSeconds, it.distanceMeters, it.avgHeartRate, it.note, it.cadenceSpm, it.stepLengthCm)
    },
    source = ActivitySource.valueOf(source),
    externalId = externalId,
    metrics = metricsJson?.let { json.decodeFromString(ActivityMetrics.serializer(), it) },
    attachedSessionId = attachedSessionId,
    keepSeparate = keepSeparate,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun Activity.toEntity(json: Json) = ActivityEntity(
    id = id,
    type = type.name,
    title = title,
    startedAtMillis = startedAtMillis,
    durationSeconds = durationSeconds,
    distanceMeters = distanceMeters,
    avgHeartRate = avgHeartRate,
    maxHeartRate = maxHeartRate,
    elevationGainMeters = elevationGainMeters,
    rpe = rpe,
    notes = notes,
    segmentsJson = json.encodeToString(
        segmentList,
        segments.map { SegmentJson(it.kind.name, it.durationSeconds, it.distanceMeters, it.avgHeartRate, it.note, it.cadenceSpm, it.stepLengthCm) },
    ),
    source = source.name,
    externalId = externalId,
    metricsJson = metrics?.let { json.encodeToString(ActivityMetrics.serializer(), it) },
    attachedSessionId = attachedSessionId,
    keepSeparate = keepSeparate,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)
