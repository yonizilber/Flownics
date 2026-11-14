package com.yonizilber.flownics.feature.activity

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.run.RunAnalysis
import com.yonizilber.flownics.domain.run.RunFormat
import com.yonizilber.flownics.domain.run.RunStructure
import com.yonizilber.flownics.domain.run.RunType

/**
 * What the app (and the coach) made of a recorded run: its type, a chart of the pace with the
 * segments' lines over it and the heart rate in its own panel below, then the segments and the
 * repeats as text. Everything the chart shows is also in the text.
 */
@Composable
fun RunStructureCard(s: RunStructure, rows: List<RunAnalysis.DetailRow>) {
    var all by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.run_structure_title), style = MaterialTheme.typography.titleSmall)
            Text(typeName(s.type) + s.repeats.joinToString("") { r ->
                val parts = s.segments.subList(r.first, r.first + r.period)
                " · ${r.count} × (" + parts.joinToString(" + ") { p -> "${RunFormat.clock(p.endS - p.startS)} @ ${RunFormat.pace(p.speedMean)}" } + ")"
            }, style = MaterialTheme.typography.bodyLarge)
            if (rows.isNotEmpty()) {
                PaceChart(s, rows)
                HeartChart(s, rows)
                Text(stringResource(R.string.run_structure_caption), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                if (s.unevenS >= s.movingS - s.binSeconds) stringResource(R.string.run_structure_all_uneven)
                else stringResource(R.string.run_structure_error, s.paceErrorRms) +
                    if (s.unevenS > 0) " " + stringResource(R.string.run_structure_uneven_time, RunFormat.clock(s.unevenS)) else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            val shown = if (all) s.segments else s.segments.take(SHOWN)
            shown.forEachIndexed { i, g ->
                val pace = if (g.variable) {
                    stringResource(R.string.run_segment_uneven, secs(g.pace10), secs(g.pace90))
                } else if (RunFormat.pace(g.speedStart) == RunFormat.pace(g.speedEnd)) {
                    RunFormat.pace(g.speedMean)
                } else {
                    "${RunFormat.pace(g.speedStart)}→${RunFormat.pace(g.speedEnd)}"
                }
                val heart = listOfNotNull(
                    g.hrStart?.let { "♥ $it→${g.hrEnd}" },
                    g.hrSettled?.let { stringResource(R.string.run_segment_settled, it, g.hrDriftPerMin ?: 0.0) },
                ).joinToString(" · ")
                Text(
                    "${i + 1}. ${RunFormat.clock(g.startS)}–${RunFormat.clock(g.endS)} · ${"%.2f".format(g.distanceM / 1000.0)} km · $pace" +
                        (if (g.climbM >= 5) " · ↑${g.climbM} m" else "") + (if (heart.isNotEmpty()) " · $heart" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (s.segments.size > SHOWN) {
                TextButton(onClick = { all = !all }) {
                    Text(stringResource(if (all) R.string.run_structure_fewer else R.string.run_structure_all, s.segments.size))
                }
            }
            s.repeats.forEach { r ->
                Text(stringResource(R.string.run_repeats_title, r.count), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                for (k in 0 until r.count) {
                    val parts = s.segments.subList(r.first + k * r.period, r.first + (k + 1) * r.period)
                    Text(
                        "${k + 1}. " + parts.joinToString(" | ") { p -> "${RunFormat.clock(p.endS - p.startS)} @ ${RunFormat.pace(p.speedMean)}" + (p.hrEnd?.let { " ♥$it" } ?: "") },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun typeName(t: RunType): String = stringResource(
    when (t) {
        RunType.STEADY -> R.string.run_type_steady
        RunType.PROGRESSIVE -> R.string.run_type_progressive
        RunType.INTERVALS -> R.string.run_type_intervals
        RunType.VARIABLE -> R.string.run_type_variable
    },
)

/** Pace every 10 s (thin, faster higher) and each segment's line (thick), or its 10–90 % band when uneven. Time left to right. */
@Composable
private fun PaceChart(s: RunStructure, rows: List<RunAnalysis.DetailRow>) {
    val data = MaterialTheme.colorScheme.outline
    val fit = MaterialTheme.colorScheme.primary
    val band = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val speeds = rows.mapNotNull { it.speed } + s.segments.flatMap { listOf(it.speedStart, it.speedEnd) }
    // The axis covers what's typical, not a GPS spike or a standstill.
    val sorted = speeds.sorted()
    val lo = sorted[(sorted.size * 0.02).toInt()].coerceAtLeast(0.8)
    val hi = sorted[((sorted.size - 1) * 0.98).toInt()].coerceAtLeast(lo + 0.5)
    val description = stringResource(R.string.run_pace_chart_description, s.segments.size, RunFormat.pace(hi), RunFormat.pace(lo))
    Row {
        Column(Modifier.width(40.dp).height(CHART_HEIGHT), verticalArrangement = Arrangement.SpaceBetween) {
            Text(RunFormat.pace(hi), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(RunFormat.pace(lo), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.weight(1f).height(CHART_HEIGHT).semantics { contentDescription = description }) {
            val total = s.durationS.toFloat().coerceAtLeast(1f)
            fun x(t: Int) = t / total * size.width
            fun y(v: Double) = (size.height * (1 - ((v.coerceIn(lo, hi) - lo) / (hi - lo)))).toFloat()
            s.segments.filter { it.variable }.forEach { g ->
                val top = y(1000.0 / (g.pace10 ?: 1.0))
                val bottom = y(1000.0 / (g.pace90 ?: 1.0))
                drawRect(band, Offset(x(g.startS), minOf(top, bottom)), Size(x(g.endS) - x(g.startS), kotlin.math.abs(bottom - top).coerceAtLeast(2f)))
            }
            line(rows.map { r -> r.speed?.let { x(r.t) to y(it) } }, data, 1.5f)
            s.segments.filter { !it.variable }.forEach { g ->
                drawLine(fit, Offset(x(g.startS), y(g.speedStart)), Offset(x(g.endS), y(g.speedEnd)), strokeWidth = 4f)
            }
        }
    }
}

/** Heart rate every 10 s, in its own panel with its own scale. */
@Composable
private fun HeartChart(s: RunStructure, rows: List<RunAnalysis.DetailRow>) {
    val hr = rows.mapNotNull { it.hr }
    if (hr.size < 2) return
    val color = MaterialTheme.colorScheme.error
    val lo = hr.min()
    val hi = hr.max().coerceAtLeast(lo + 10)
    val description = stringResource(R.string.run_heart_chart_description, lo, hi)
    Row {
        Column(Modifier.width(40.dp).height(HEART_HEIGHT), verticalArrangement = Arrangement.SpaceBetween) {
            Text("♥$hi", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("♥$lo", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(HEART_HEIGHT).semantics { contentDescription = description }) {
                val total = s.durationS.toFloat().coerceAtLeast(1f)
                line(rows.map { r -> r.hr?.let { r.t / total * size.width to size.height * (1 - (it - lo).toFloat() / (hi - lo)) } }, color, 2f)
            }
        }
    }
}

/** A line through the points, broken where one is missing (a stop). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.line(points: List<Pair<Float, Float>?>, color: Color, width: Float) {
    val path = Path()
    var open = false
    points.forEach { p ->
        if (p == null) {
            open = false
        } else if (!open) {
            path.moveTo(p.first, p.second)
            open = true
        } else {
            path.lineTo(p.first, p.second)
        }
    }
    drawPath(path, color, style = Stroke(width))
}

private fun secs(pace: Double?): String = pace?.let { RunFormat.pace(1000.0 / it) } ?: "-"

private val CHART_HEIGHT = 120.dp
private val HEART_HEIGHT = 70.dp
private const val SHOWN = 12
