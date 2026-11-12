package com.yonizilber.flownics.domain.model

import java.time.LocalDate
import java.time.LocalTime

/** What a planned day holds: a saved workout from the app, or an activity (a run with targets, a ride…). */
enum class PlanKind { WORKOUT, ACTIVITY }

/** [DONE] is never stored: it is worked out by matching with what was done (see PlanMatching). */
enum class PlanStatus { PLANNED, SKIPPED }

/**
 * One part of a planned activity, with its targets: a pace range (seconds per km, faster first),
 * a heart-rate ceiling, or neither. Either a time or a distance says how long it lasts.
 */
data class PlannedStep(
    val kind: SegmentKind,
    val durationSeconds: Int? = null,
    val distanceMeters: Int? = null,
    val paceFastSecondsPerKm: Int? = null,
    val paceSlowSecondsPerKm: Int? = null,
    val maxHeartRate: Int? = null,
    val note: String = "",
) {
    /** Roughly how long it takes: its time, or its distance at the middle of its pace range. */
    fun estimatedSeconds(): Int? = durationSeconds ?: distanceMeters?.let { m ->
        val pace = listOfNotNull(paceFastSecondsPerKm, paceSlowSecondsPerKm).takeIf { it.isNotEmpty() }?.average() ?: return null
        (m / 1000.0 * pace).toInt()
    }
}

data class PlannedWorkout(
    val id: Long = 0,
    val date: LocalDate,
    /** Null: some time that day. */
    val time: LocalTime? = null,
    val kind: PlanKind,
    /** The saved workout, for [PlanKind.WORKOUT]. */
    val workoutId: Long? = null,
    /** What kind of activity, for [PlanKind.ACTIVITY]. */
    val activityType: ActivityType? = null,
    val title: String = "",
    val durationMinutes: Int? = null,
    /** What to aim for, in words (the coach's reasoning or the user's own notes). */
    val notes: String = "",
    val steps: List<PlannedStep> = emptyList(),
    val status: PlanStatus = PlanStatus.PLANNED,
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
)
