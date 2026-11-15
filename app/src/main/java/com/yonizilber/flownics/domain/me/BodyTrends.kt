package com.yonizilber.flownics.domain.me

import com.yonizilber.flownics.domain.wellness.WellnessDay
import java.time.LocalDate

/** What the "Me" screen follows over time, and where each comes from. */
enum class Metric(val source: Source) {
    RESTING_HR(Source.GARMIN),
    HRV(Source.GARMIN),
    SLEEP_HOURS(Source.GARMIN),
    SLEEP_SCORE(Source.GARMIN),
    BODY_BATTERY_WAKE(Source.GARMIN),
    STRESS(Source.GARMIN),
    RESPIRATION(Source.GARMIN),
    STEPS(Source.GARMIN),
    VO2MAX(Source.GARMIN),
    /** Edwards TRIMP per day, worked out by Flownics from the heart rate of recordings. */
    LOAD(Source.FLOWNICS),
    ;

    enum class Source { GARMIN, FLOWNICS }
}

/**
 * One metric over a range of days: each day that has a value (no day is filled in), the latest,
 * and the user's own averages over the last 7 and 28 days of the range, so a value is read
 * against the user's usual, not a population norm. [usualLow]..[usualHigh] is Garmin's own
 * baseline range where it gives one (HRV).
 */
data class Trend(
    val metric: Metric,
    val points: List<Pair<LocalDate, Double>>,
    val latest: Pair<LocalDate, Double>?,
    val mean7: Double?,
    val mean28: Double?,
    val usualLow: Double? = null,
    val usualHigh: Double? = null,
)

object BodyTrends {

    /** Every metric from [from] to [to]; [load] is TRIMP by day (0 on a day with no recording). */
    fun of(days: List<WellnessDay>, load: Map<LocalDate, Int>, from: LocalDate, to: LocalDate): List<Trend> {
        val inRange = days.filter { it.date in from..to }.sortedBy { it.date }
        fun series(value: (WellnessDay) -> Number?) = inRange.mapNotNull { d -> value(d)?.toDouble()?.let { d.date to it } }
        val latestHrv = inRange.lastOrNull { it.hrvBaselineLow != null && it.hrvBaselineHigh != null }
        return Metric.entries.map { m ->
            val points = when (m) {
                Metric.RESTING_HR -> series { it.restingHr }
                Metric.HRV -> series { it.hrvLastNight }
                Metric.SLEEP_HOURS -> series { d -> d.sleepSeconds?.let { it / 3600.0 } }
                Metric.SLEEP_SCORE -> series { it.sleepScore }
                Metric.BODY_BATTERY_WAKE -> series { it.bodyBatteryAtWake }
                Metric.STRESS -> series { it.stressAvg }
                Metric.RESPIRATION -> series { it.sleepAvgRespiration }
                Metric.STEPS -> series { it.steps }
                Metric.VO2MAX -> series { it.vo2Max }
                // A day without a recording is a day of no load: 0 is a real value here.
                Metric.LOAD -> generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.map { it to (load[it] ?: 0).toDouble() }.toList()
            }
            Trend(
                metric = m,
                points = points,
                latest = points.lastOrNull(),
                mean7 = mean(points, to.minusDays(6), to),
                mean28 = mean(points, to.minusDays(27), to),
                usualLow = if (m == Metric.HRV) latestHrv?.hrvBaselineLow?.toDouble() else null,
                usualHigh = if (m == Metric.HRV) latestHrv?.hrvBaselineHigh?.toDouble() else null,
            )
        }
    }

    private fun mean(points: List<Pair<LocalDate, Double>>, from: LocalDate, to: LocalDate): Double? =
        points.filter { it.first in from..to }.map { it.second }.takeIf { it.isNotEmpty() }?.average()
}
