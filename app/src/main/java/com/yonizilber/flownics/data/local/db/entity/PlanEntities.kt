package com.yonizilber.flownics.data.local.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** A workout or activity planned for a day (v9). [date] is ISO (YYYY-MM-DD), [time] HH:MM or null. */
@Entity(tableName = "planned_workouts", indices = [Index("date")])
data class PlannedWorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val time: String?,
    val kind: String,
    val workoutId: Long?,
    val activityType: String?,
    val title: String,
    val durationMinutes: Int?,
    val notes: String,
    val stepsJson: String,
    val status: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Serializable
data class PlannedStepJson(
    val kind: String,
    val durationSeconds: Int? = null,
    val distanceMeters: Int? = null,
    val paceFastSecondsPerKm: Int? = null,
    val paceSlowSecondsPerKm: Int? = null,
    val maxHeartRate: Int? = null,
    val note: String = "",
)

/**
 * Which calendar event shows which item (v9): "plan:5", "activity:7", "session:3". Kept apart from
 * the items because events belong to this phone's calendar and never go into a backup.
 */
@Entity(tableName = "calendar_links")
data class CalendarLinkEntity(
    @PrimaryKey val itemKey: String,
    val calendarId: Long,
    val eventId: Long,
    /** Of what was last written, so an unchanged item isn't rewritten on every pass. */
    val contentHash: Int,
)
