package com.yonizilber.flownics.data.local.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** A run, ride or other activity logged outside the player (v8). Segments are a JSON column. */
@Entity(tableName = "activities", indices = [Index("startedAtMillis"), Index("externalId"), Index("attachedSessionId")])
data class ActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val title: String,
    val startedAtMillis: Long,
    val durationSeconds: Int,
    val distanceMeters: Int?,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val elevationGainMeters: Int?,
    val rpe: Int?,
    val notes: String,
    val segmentsJson: String,
    val source: String,
    /** v10: which recording it came from (see Activity.externalId). */
    val externalId: String? = null,
    /** v10: ActivityMetrics as JSON, for a recording. */
    val metricsJson: String? = null,
    /** v11: the workout session this recording was made during. */
    val attachedSessionId: Long? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val keepSeparate: Boolean = false,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Serializable
data class SegmentJson(
    val kind: String,
    val durationSeconds: Int? = null,
    val distanceMeters: Int? = null,
    val avgHeartRate: Int? = null,
    val note: String = "",
    val cadenceSpm: Int? = null,
    val stepLengthCm: Int? = null,
)
