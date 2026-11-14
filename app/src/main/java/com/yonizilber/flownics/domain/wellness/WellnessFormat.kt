package com.yonizilber.flownics.domain.wellness

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Days of sleep and recovery for the coach, as a compact table: the columns named once, then one
 * short line a day, "-" where Garmin gave nothing. No value is made up or averaged away.
 */
object WellnessFormat {

    const val HEADER = "date | sleep h:mm (deep/light/REM/awake min) bed-wake | score | HRV night/7d (usual) status | RHR | BB wake, low-high | stress | breaths/min | steps | VO2max"

    fun table(days: List<WellnessDay>, zone: ZoneId): String {
        if (days.isEmpty()) return "No sleep or recovery data from Garmin for these dates."
        val lines = days.sortedBy { it.date }.map { line(it, zone) }
        val unconfirmed = days.count { it.stages.isNotEmpty() && !it.stagesConfirmed }
        return buildString {
            appendLine("Sleep and recovery from Garmin (its own calculations; sleep dated by the morning it ends; BB = Body Battery, Garmin's estimate):")
            appendLine(HEADER)
            lines.forEach(::appendLine)
            if (unconfirmed > 0) append("Note: on $unconfirmed nights the stage timeline didn't add up to Garmin's totals; the totals are Garmin's.")
        }.trimEnd()
    }

    fun line(d: WellnessDay, zone: ZoneId): String {
        fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().toString().take(5)
        val sleep = d.sleepSeconds?.let { s ->
            val parts = listOf(d.deepSeconds, d.lightSeconds, d.remSeconds, d.awakeSeconds).joinToString("/") { it?.let { v -> (v / 60).toString() } ?: "-" }
            val window = if (d.sleepStartMillis != null && d.sleepEndMillis != null) " ${clock(d.sleepStartMillis)}-${clock(d.sleepEndMillis)}" else ""
            "%d:%02d (%s)%s".format(Locale.ROOT, s / 3600, s % 3600 / 60, parts, window)
        } ?: "-"
        val hrv = d.hrvLastNight?.let { n ->
            n.toString() + "/" + (d.hrvWeeklyAvg?.toString() ?: "-") +
                (if (d.hrvBaselineLow != null && d.hrvBaselineHigh != null) " (${d.hrvBaselineLow}-${d.hrvBaselineHigh})" else "") +
                (d.hrvStatus?.let { " ${it.lowercase()}" } ?: "")
        } ?: "-"
        val bb = if (d.bodyBatteryAtWake != null || d.bodyBatteryHigh != null) {
            (d.bodyBatteryAtWake?.toString() ?: "-") + ", " + (d.bodyBatteryLow?.toString() ?: "-") + "-" + (d.bodyBatteryHigh?.toString() ?: "-")
        } else {
            "-"
        }
        return listOf(
            d.date.toString(),
            sleep,
            d.sleepScore?.toString() ?: "-",
            hrv,
            d.restingHr?.toString() ?: "-",
            bb,
            d.stressAvg?.toString() ?: "-",
            d.sleepAvgRespiration?.let { "%.1f".format(Locale.ROOT, it) } ?: "-",
            d.steps?.toString() ?: "-",
            d.vo2Max?.let { "%.1f".format(Locale.ROOT, it) } ?: "-",
        ).joinToString(" | ")
    }
}
