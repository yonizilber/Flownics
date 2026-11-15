package com.yonizilber.flownics.domain.run

import java.util.Locale
import kotlin.math.roundToInt

/**
 * A run's structure as compact text for the coach, at three depths: [summary] (a few words, in the
 * training log), [structure] (every segment as one row, repeats rep by rep) and [detail] (the
 * readings themselves for a stretch, binned). Columns are named once; "-" is unknown.
 */
object RunFormat {

    fun pace(speed: Double): String {
        val s = RunAnalysis.paceOf(speed).roundToInt()
        return "%d:%02d".format(Locale.ROOT, s / 60, s % 60)
    }

    fun clock(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
    }

    private fun km(m: Int) = "%.2f".format(Locale.ROOT, m / 1000.0)

    /** A few words: the type, and for intervals what the repeats were. */
    fun summary(s: RunStructure): String = buildString {
        append(s.type.name.lowercase())
        s.repeats.forEach { r -> append(", ").append(repeatName(s, r)) }
        append(" (${s.segments.size} segments)")
    }

    private fun repeatName(s: RunStructure, r: RunRepeat): String {
        val parts = s.segments.subList(r.first, r.first + r.period)
        return "${r.count}×(" + parts.joinToString(" + ") { p -> "${clock(p.endS - p.startS)}/${p.distanceM} m @ ${pace(p.speedMean)}" } + ")"
    }

    /** Every segment as a row, then each repeat rep by rep. [heading] names the run (date, id). */
    fun structure(s: RunStructure, heading: String?): String = buildString {
        heading?.let { appendLine(it) }
        appendLine(
            "${km(s.distanceM)} km, moving ${clock(s.movingS)} of ${clock(s.durationS)}; type ${s.type.name.lowercase()}. " +
                (if (s.source == SegmentSource.LAPS_AND_DETECTED) "Segments: the watch's workout or manual laps, split further where the pace changed. " else "Segments: where the pace changed (auto-laps ignored). ") +
                "From ${s.binSeconds}-s data; " +
                (if (s.unevenS >= s.movingS - s.binSeconds) "all of it too uneven for a line, told by percentiles."
                else "the segments' lines are off the data by ±${s.paceErrorRms} s/km (RMS)" + (if (s.unevenS > 0) "; ${clock(s.unevenS)} too uneven for a line, told by percentiles." else ".")),
        )
        appendLine("Lines are continuous through the run where the data is (a bend, not a jump); a pace marked '=' starts where the previous segment's line ended. Heart rate is fitted as one curve too, each segment starting from the previous one's end.")
        appendLine("Columns: # | from-to | km | pace start→end (mean) | grade-adj pace | grade %, climb m | HR start→end, peak | HR settled, drift/min | cadence | fit ±s/km | level")
        s.segments.forEachIndexed { i, g -> appendLine(row(i + 1, g)) }
        s.repeats.forEach { r ->
            appendLine("Repeats: segments ${r.first + 1}-${r.first + r.period * r.count} are ${repeatName(s, r)}.")
            appendLine("rep | " + (0 until r.period).joinToString(" | ") { "part ${it + 1}: time, pace, HR end/peak" })
            for (k in 0 until r.count) {
                val parts = s.segments.subList(r.first + k * r.period, r.first + (k + 1) * r.period)
                appendLine("${k + 1} | " + parts.joinToString(" | ") { p -> "${clock(p.endS - p.startS)}, ${pace(p.speedMean)}, ${p.hrEnd ?: "-"}/${p.hrMax ?: "-"}" })
            }
        }
        append("Levels (grade-adjusted pace, easiest first): " + s.levels.joinToString(", ") { pace(it) } + ".")
    }

    private fun row(n: Int, g: RunSegment): String {
        val paceText = if (g.variable) {
            "uneven: p10 ${g.pace10?.let(::secs)} / median ${g.pace50?.let(::secs)} / p90 ${g.pace90?.let(::secs)} (mean ${pace(g.speedMean)})"
        } else {
            (if (g.joined) "=" else "") + "${pace(g.speedStart)}→${pace(g.speedEnd)} (${pace(g.speedMean)})"
        }
        val hr = if (g.hrStart != null) "${g.hrStart}→${g.hrEnd}, ${g.hrMax}" else "-"
        val settled = g.hrSettled?.let { "$it, ${signed(g.hrDriftPerMin ?: 0.0)} (τ ${g.hrTauS}s, ±${g.hrFitRms})" } ?: "-"
        return listOf(
            n.toString(),
            "${clock(g.startS)}-${clock(g.endS)}",
            km(g.distanceM),
            paceText,
            pace(g.gapMean),
            "${signed(g.gradePercent)}, +${g.climbM}/-${g.descentM}",
            hr,
            settled,
            g.cadence?.toString() ?: "-",
            "±${g.paceErrorRms}",
            g.level.toString(),
        ).joinToString(" | ")
    }

    private fun secs(paceSeconds: Double): String {
        val s = paceSeconds.roundToInt()
        return "%d:%02d".format(Locale.ROOT, s / 60, s % 60)
    }

    private fun signed(v: Double) = (if (v >= 0) "+" else "") + "%.1f".format(Locale.ROOT, v)

    /** The readings for a stretch, one row per step. */
    fun detail(rows: List<RunAnalysis.DetailRow>, stepS: Int): String = buildString {
        appendLine("Every ${stepS}s: time | pace | grade-adj pace | HR | cadence | altitude m | grade %   ('stop' = not moving)")
        rows.forEach { r ->
            if (r.speed == null) {
                appendLine("${clock(r.t)} | stop")
            } else {
                appendLine(
                    listOf(clock(r.t), pace(r.speed), r.gap?.let(::pace) ?: "-", r.hr?.toString() ?: "-", r.cadence?.toString() ?: "-", r.altitude?.toString() ?: "-", r.gradePercent?.toString() ?: "-")
                        .joinToString(" | "),
                )
            }
        }
    }.trimEnd()
}
