package com.yonizilber.flownics.domain.model

import kotlinx.serialization.Serializable

/** A workout done outside the player: a run, a ride, a swim… logged by hand or (later) imported. */
enum class ActivityType { RUN, WALK, HIKE, RIDE, SWIM, OTHER }

/** What one part of an activity was, so intervals keep their shape instead of becoming an average. */
enum class SegmentKind { WARMUP, WORK, RECOVERY, COOLDOWN, STEADY }

data class ActivitySegment(
    val kind: SegmentKind,
    val durationSeconds: Int? = null,
    val distanceMeters: Int? = null,
    val avgHeartRate: Int? = null,
    val note: String = "",
    /** Steps per minute (running, walking), from a recording. */
    val cadenceSpm: Int? = null,
    val stepLengthCm: Int? = null,
) {
    /** Seconds per kilometre, when both are known. */
    val paceSecondsPerKm: Int? get() = pace(durationSeconds, distanceMeters)
}

/**
 * [FIT]: read from a watch recording (a file the user imported, or synced from Garmin).
 * [SENSOR]: live heart rate the app read over Bluetooth during one of its workouts.
 */
enum class ActivitySource { MANUAL, FIT, SENSOR }

/** One whole kilometre of a recorded activity. */
@Serializable
data class KmSplit(val km: Int, val seconds: Int, val avgHeartRate: Int? = null, val cadence: Int? = null)

/** What a recording adds beyond the totals, computed from its second-by-second samples. */
@Serializable
data class ActivityMetrics(
    /** Steps per minute for running and walking ([cadenceIsSteps]); pedal turns per minute for a ride. */
    val cadence: Int? = null,
    val cadenceIsSteps: Boolean = true,
    val stepLengthCm: Int? = null,
    /** False when worked out from speed and cadence rather than measured by the watch or a pod. */
    val stepLengthMeasured: Boolean = false,
    val verticalOscillationMm: Int? = null,
    val groundContactMs: Int? = null,
    val avgPowerW: Int? = null,
    /** Heart-rate drift against pace, first half to second, in percent (steady efforts only). */
    val decouplingPercent: Double? = null,
    val splits: List<KmSplit> = emptyList(),
    val samples: Int = 0,
    /** For a recording attached to a workout: heart rate in each set, by the set's step. */
    val sets: List<SetHeart> = emptyList(),
    /** Seconds at each heart rate (see HeartZones.histogram); zones and load are worked out from it. */
    val hrHistogram: List<Int> = emptyList(),
)

/** Heart rate during one set of a workout, and how far it fell in the minute after. */
@Serializable
data class SetHeart(val stepIndex: Int, val avg: Int, val max: Int, val afterMinute: Int? = null) {
    val drop: Int? get() = afterMinute?.let { max - it }
}

data class Activity(
    val id: Long = 0,
    val type: ActivityType,
    val title: String,
    val startedAtMillis: Long,
    val durationSeconds: Int,
    val distanceMeters: Int? = null,
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    val elevationGainMeters: Int? = null,
    /** How hard it felt, 1 (very easy) to 10 (maximal). */
    val rpe: Int? = null,
    val notes: String = "",
    /** The parts in order (warm-up, each repeat and recovery, cool-down). Empty when not recorded. */
    val segments: List<ActivitySegment> = emptyList(),
    val source: ActivitySource = ActivitySource.MANUAL,
    /** For a recording: which one it was, so the same file or Garmin activity is never imported twice. */
    val externalId: String? = null,
    val metrics: ActivityMetrics? = null,
    /**
     * A recording made during a workout in the app: it belongs to that session (shown with it,
     * heart rate set by set) instead of being a second, overlapping activity.
     */
    val attachedSessionId: Long? = null,
    /** The user chose to keep this recording apart from the workout it overlaps. */
    val keepSeparate: Boolean = false,
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
) {
    val endedAtMillis: Long get() = startedAtMillis + durationSeconds * 1000L
    val paceSecondsPerKm: Int? get() = pace(durationSeconds, distanceMeters)

    companion object {
        val RPE_RANGE = 1..10
    }
}

private fun pace(seconds: Int?, meters: Int?): Int? =
    if (seconds != null && meters != null && seconds > 0 && meters > 0) (seconds * 1000L / meters).toInt() else null
