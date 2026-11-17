package com.yonizilber.flownics.domain.run

import kotlinx.serialization.Serializable

/** What a run was, by its structure. */
enum class RunType { STEADY, PROGRESSIVE, INTERVALS, VARIABLE }

/** Where the segment boundaries came from. */
enum class SegmentSource { DETECTED, LAPS_AND_DETECTED }

/**
 * One stretch of a run that is best told as one piece: a steady part, a rep, a recovery, a
 * gradual build-up (with its slope). Speeds in m/s; pace errors in seconds per km; times in
 * seconds from the start of the recording.
 */
@Serializable
data class RunSegment(
    val startS: Int,
    val endS: Int,
    val distanceM: Int,
    /** The fitted line's speed at the start and at the end (equal for an even pace). */
    val speedStart: Double,
    val speedEnd: Double,
    /** Its line carries on from the previous segment's (a change of trend, no jump): it starts where that one ended. */
    val joined: Boolean = false,
    /** Distance over time. */
    val speedMean: Double,
    /** Grade-adjusted speed (Minetti 2002): the flat-ground speed of the same effort. */
    val gapMean: Double,
    /** How far the 5-second pace is from the fitted line: root mean square and 90th percentile. */
    val paceErrorRms: Double,
    val paceError90: Double,
    /** Too uneven to be told by a line: then [pace10], [pace50], [pace90] (s/km) tell it instead. */
    val variable: Boolean,
    val pace10: Double? = null,
    val pace50: Double? = null,
    val pace90: Double? = null,
    val gradePercent: Double,
    val climbM: Int,
    val descentM: Int,
    val hrMean: Int? = null,
    val hrMax: Int? = null,
    val hrStart: Int? = null,
    val hrEnd: Int? = null,
    /**
     * From the heart-rate fit, which starts from where the previous segment's curve ended, only
     * when the segment is long enough to settle (≥ 2 τ): the level it settles at, and its drift.
     */
    val hrSettled: Int? = null,
    val hrDriftPerMin: Double? = null,
    val hrTauS: Int? = null,
    val hrFitRms: Double? = null,
    val cadence: Int? = null,
    val powerW: Int? = null,
    /** Its effort level among the run's segments, 0 the easiest. */
    val level: Int = 0,
)

/**
 * Segments [first] to [first] + [period] × [count] − 1 are [count] repeats of the same [period]
 * segments. [tail]: the [tail] segments right after them are one more rep cut short, its first
 * parts without the rest (the last fast rep of a session has no recovery after it: a cool-down
 * follows instead).
 */
@Serializable
data class RunRepeat(val first: Int, val period: Int, val count: Int, val tail: Int = 0) {
    /** Reps in all, the cut-short one included. */
    val reps: Int get() = count + if (tail > 0) 1 else 0

    /** Segments covered. */
    val length: Int get() = period * count + tail

    /** The segments of rep [k] (0-based); the cut-short one has fewer than [period]. */
    fun partsOf(k: Int): IntRange = (first + k * period) until (first + minOf((k + 1) * period, length))
}

/** A whole run, compressed: what the coach (and the activity screen) read instead of every second. */
@Serializable
data class RunStructure(
    val version: Int,
    val type: RunType,
    val source: SegmentSource,
    val binSeconds: Int,
    val durationS: Int,
    val movingS: Int,
    val distanceM: Int,
    val segments: List<RunSegment>,
    val repeats: List<RunRepeat>,
    /** The levels' grade-adjusted speeds (m/s, time-weighted), easiest first. */
    val levels: List<Double>,
    /** Over the segments told by a line: the 5-second pace against the lines (RMS, s/km). */
    val paceErrorRms: Double,
    /** Time in segments too uneven for a line (told by percentiles instead). */
    val unevenS: Int = 0,
)
