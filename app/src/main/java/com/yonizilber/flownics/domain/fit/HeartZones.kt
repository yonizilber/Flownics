package com.yonizilber.flownics.domain.fit

import com.yonizilber.flownics.domain.model.Activity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Heart-rate zones and training load. Five zones by heart-rate reserve (Karvonen) when the
 * resting heart rate is known, else by share of the maximum: 50–60, 60–70, 70–80, 80–90, 90–100 %.
 * Load is Edwards' TRIMP: minutes in each zone times the zone's number (1 to 5), so an hour easy
 * and twenty minutes hard can be compared. Pure, so it is unit-tested.
 */
data class HeartZones(val max: Int, val resting: Int? = null) {

    /** Lower bound of zones 1 to 5, and the top (max). */
    val bounds: List<Int> get() = listOf(0.5, 0.6, 0.7, 0.8, 0.9, 1.0).map { share ->
        val r = resting
        if (r != null && r < max) (r + share * (max - r)).roundToInt() else (share * max).roundToInt()
    }

    /** 0 below zone 1, 1–5 for the zones. */
    fun zoneOf(bpm: Int): Int {
        val b = bounds
        return when {
            bpm < b[0] -> 0
            bpm < b[1] -> 1
            bpm < b[2] -> 2
            bpm < b[3] -> 3
            bpm < b[4] -> 4
            else -> 5
        }
    }

    /** Seconds in zones 1–5 from a histogram (seconds per [HISTOGRAM_STEP]-bpm bucket from [HISTOGRAM_FROM]). */
    fun secondsInZones(histogram: List<Int>): IntArray {
        val out = IntArray(5)
        histogram.forEachIndexed { i, seconds ->
            val bpm = HISTOGRAM_FROM + i * HISTOGRAM_STEP + HISTOGRAM_STEP / 2
            val z = zoneOf(bpm)
            if (z > 0) out[z - 1] += seconds
        }
        return out
    }

    fun trimp(histogram: List<Int>): Int =
        secondsInZones(histogram).withIndex().sumOf { (i, s) -> s / 60.0 * (i + 1) }.roundToInt()

    companion object {
        const val HISTOGRAM_FROM = 40
        const val HISTOGRAM_STEP = 5
        const val HISTOGRAM_BUCKETS = 37 // 40–224 bpm

        /** Seconds spent at each heart rate, in buckets, from readings (a gap over 10 s counts as 10 s). */
        fun histogram(points: List<HeartPoint>): List<Int> {
            val out = IntArray(HISTOGRAM_BUCKETS)
            points.sortedBy { it.atMillis }.zipWithNext { a, b ->
                val seconds = ((b.atMillis - a.atMillis) / 1000).toInt().coerceIn(0, MAX_GAP_SECONDS)
                val i = ((a.bpm - HISTOGRAM_FROM) / HISTOGRAM_STEP).coerceIn(0, HISTOGRAM_BUCKETS - 1)
                out[i] += seconds
            }
            return out.toList()
        }

        private const val MAX_GAP_SECONDS = 10
    }
}

/** Load over the last weeks, and how this week compares with the four before it. */
data class LoadSummary(
    /** TRIMP per day, oldest first, for the last [days] days (today last). */
    val daily: List<Pair<LocalDate, Int>>,
    val last7: Int,
    /** The weekly average over the last 28 days. */
    val weeklyAverage28: Int,
    /** last7 ÷ weeklyAverage28; null without enough history. */
    val ratio: Double?,
    /** Activities and workouts with heart rate counted, and those without (not in the load). */
    val counted: Int,
    val withoutHeartRate: Int,
)

object TrainingLoad {

    /** [recordings]: every recording with heart rate (standalone or attached to a workout). */
    fun summary(recordings: List<Activity>, zones: HeartZones, today: LocalDate, zone: ZoneId, days: Int = 28): LoadSummary {
        val from = today.minusDays(days - 1L)
        val byDay = mutableMapOf<LocalDate, Int>()
        var counted = 0
        var without = 0
        recordings.forEach { a ->
            val date = Instant.ofEpochMilli(a.startedAtMillis).atZone(zone).toLocalDate()
            if (date < from || date > today) return@forEach
            val histogram = a.metrics?.hrHistogram.orEmpty()
            if (histogram.isEmpty() || histogram.sum() == 0) {
                without++
            } else {
                counted++
                byDay[date] = (byDay[date] ?: 0) + zones.trimp(histogram)
            }
        }
        val daily = (0 until days).map { from.plusDays(it.toLong()) }.map { it to (byDay[it] ?: 0) }
        val last7 = daily.takeLast(7).sumOf { it.second }
        val avg = daily.sumOf { it.second } / (days / 7)
        return LoadSummary(daily, last7, avg, if (avg > 0 && daily.take(days - 7).any { it.second > 0 }) last7.toDouble() / avg else null, counted, without)
    }
}
