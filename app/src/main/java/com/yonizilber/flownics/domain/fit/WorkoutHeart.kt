package com.yonizilber.flownics.domain.fit

import com.yonizilber.flownics.domain.model.SetHeart
import com.yonizilber.flownics.domain.model.SetLog
import kotlin.math.abs
import kotlin.math.roundToInt

/** One heart-rate reading of a recording, on the wall clock. */
data class HeartPoint(val atMillis: Long, val bpm: Int)

/**
 * Lines a watch recording up with a workout done in the app. Both clocks follow network time, so
 * they agree to about a second: each set's start and end, as the player logged them, cut the
 * heart rate the watch measured. Pure, so it is unit-tested.
 */
object WorkoutHeart {

    /** How much two time spans overlap, as a share of the shorter one (0 to 1). */
    fun overlap(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Double {
        val shorter = minOf(aEnd - aStart, bEnd - bStart)
        if (shorter <= 0) return 0.0
        val common = minOf(aEnd, bEnd) - maxOf(aStart, bStart)
        return (common.toDouble() / shorter).coerceIn(0.0, 1.0)
    }

    /**
     * Heart rate in each set done: average and peak while it lasted, and the reading a minute
     * after it ended (how fast it came down). A set logged before start times were kept is taken
     * as its timed length, or [FALLBACK_SET_SECONDS], before it ended.
     */
    fun sets(logs: List<SetLog>, heart: List<HeartPoint>): List<SetHeart> {
        if (heart.isEmpty()) return emptyList()
        val sorted = heart.sortedBy { it.atMillis }
        return logs.filterNot { it.skipped }.sortedBy { it.completedAtMillis }.mapNotNull { log ->
            val end = log.completedAtMillis
            val start = log.startedAtMillis?.takeIf { it < end } ?: (end - (log.actual.seconds ?: FALLBACK_SET_SECONDS) * 1000L)
            val inSet = sorted.filter { it.atMillis in start..end }.map { it.bpm }
            if (inSet.isEmpty()) return@mapNotNull null
            val after = nearest(sorted, end + 60_000)?.takeIf { abs(it.atMillis - (end + 60_000)) <= NEAR_MS }?.bpm
            SetHeart(log.stepIndex, inSet.average().roundToInt(), inSet.max(), after)
        }
    }

    private fun nearest(points: List<HeartPoint>, at: Long): HeartPoint? = points.minByOrNull { abs(it.atMillis - at) }

    /** At most [max] points, evenly spaced, for drawing. */
    fun thin(points: List<HeartPoint>, max: Int = 400): List<HeartPoint> {
        if (points.size <= max) return points
        val step = points.size.toDouble() / max
        return (0 until max).map { points[(it * step).toInt()] }
    }

    /** A recording counts as made during a workout when they share at least half of the shorter one. */
    const val MIN_OVERLAP = 0.5
    private const val FALLBACK_SET_SECONDS = 40
    private const val NEAR_MS = 10_000L
}
