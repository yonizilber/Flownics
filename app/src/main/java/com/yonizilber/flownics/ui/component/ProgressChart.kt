package com.yonizilber.flownics.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.yonizilber.flownics.domain.history.ProgressPoint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * One series over time: a 2dp line with ringed dots, positioned by date so gaps between workouts
 * show as gaps. Touching (or dragging across) the chart selects the nearest workout; the readout
 * above shows the selected one, or the latest. The chart is never the only way to read a value —
 * callers list the same points as text. Time runs left to right in both languages, and [caption]
 * says so along with what is plotted.
 */
@Composable
fun ProgressChart(
    points: List<ProgressPoint>,
    formatValue: (Double) -> String,
    formatDate: (Long) -> String,
    caption: String,
    accessibilityText: String,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) return
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val shown = points[selected ?: points.lastIndex]

    val colors = MaterialTheme.colorScheme
    val tickStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    val ticks = remember(points) { niceTicks(points.minOf { it.value }, points.maxOf { it.value }) }
    val tickLabels = remember(ticks) { ticks.map(formatValue) }
    val density = LocalDensity.current
    val gutterPx = remember(tickLabels, tickStyle) {
        tickLabels.maxOf { measurer.measure(it, tickStyle).size.width } + with(density) { 8.dp.toPx() }
    }
    val xs = remember(points) { xFractions(points) }

    Column(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = accessibilityText },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(formatValue(shown.value), style = MaterialTheme.typography.titleLarge)
            Text(
                formatDate(shown.atMillis),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .pointerInput(points, gutterPx) {
                        val plot = PlotArea(size.width.toFloat(), size.height.toFloat(), gutterPx, 8.dp.toPx())
                        fun nearest(x: Float) = xs.indices.minBy { abs(plot.x(xs[it]) - x) }
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            selected = nearest(down.position.x)
                            do {
                                val event = awaitPointerEvent()
                                event.changes.firstOrNull { it.pressed }?.let { selected = nearest(it.position.x) }
                            } while (event.changes.any { it.pressed })
                        }
                    },
            ) {
                val plot = PlotArea(size.width, size.height, gutterPx, 8.dp.toPx())
                val low = ticks.first()
                val high = ticks.last()
                fun y(value: Double) = plot.bottom - ((value - low) / (high - low)).toFloat() * (plot.bottom - plot.top)

                // Hairline grid with its values at the start.
                ticks.forEachIndexed { i, tick ->
                    val ty = y(tick)
                    drawLine(colors.outlineVariant, Offset(plot.left, ty), Offset(plot.right, ty), strokeWidth = 1.dp.toPx())
                    val label = measurer.measure(tickLabels[i], tickStyle)
                    drawText(label, topLeft = Offset(0f, (ty - label.size.height / 2f).coerceIn(0f, size.height - label.size.height)))
                }

                val offsets = points.mapIndexed { i, p -> Offset(plot.x(xs[i]), y(p.value)) }
                if (offsets.size > 1) {
                    val line = Path().apply {
                        moveTo(offsets.first().x, offsets.first().y)
                        offsets.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    val area = Path().apply {
                        addPath(line)
                        lineTo(offsets.last().x, plot.bottom)
                        lineTo(offsets.first().x, plot.bottom)
                        close()
                    }
                    drawPath(area, colors.primary.copy(alpha = 0.10f))
                    drawPath(line, colors.primary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                selected?.let { i ->
                    drawLine(colors.onSurfaceVariant, Offset(offsets[i].x, plot.top), Offset(offsets[i].x, plot.bottom), strokeWidth = 1.dp.toPx())
                }
                offsets.forEach { o ->
                    drawCircle(colors.surface, radius = 6.dp.toPx(), center = o)
                    drawCircle(colors.primary, radius = 4.dp.toPx(), center = o)
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = with(density) { gutterPx.toDp() })) {
                Text(formatDate(points.first().atMillis), style = tickStyle, modifier = Modifier.weight(1f))
                if (points.size > 1) Text(formatDate(points.last().atMillis), style = tickStyle)
            }
        }
        Text(caption, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

private class PlotArea(width: Float, height: Float, gutter: Float, pad: Float) {
    val left = gutter + pad
    val right = width - pad
    val top = pad
    val bottom = height - pad
    fun x(fraction: Float) = left + fraction * (right - left)
}

/** Each point's place on the time axis, 0..1. A lone point (or one day's worth) sits in the middle. */
internal fun xFractions(points: List<ProgressPoint>): List<Float> {
    val first = points.first().atMillis
    val span = points.last().atMillis - first
    return points.map { if (span == 0L) 0.5f else (it.atMillis - first).toFloat() / span }
}

/**
 * Round axis values covering [min]..[max] in about [target] steps of 1, 2, 2.5 or 5 × 10ⁿ. A flat
 * series gets room around it. Never below zero for data that isn't: no metric here is negative.
 */
internal fun niceTicks(min: Double, max: Double, target: Int = 4): List<Double> {
    var low = min
    var high = max
    if (high - low < 1e-9) {
        val pad = if (low == 0.0) 1.0 else abs(low) * 0.1
        low -= pad
        high += pad
    }
    val raw = (high - low) / (target - 1)
    val magnitude = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it * magnitude >= raw - 1e-12 } * magnitude
    var start = floor(low / step) * step
    if (min >= 0) start = start.coerceAtLeast(0.0)
    val end = ceil(high / step) * step
    // Multiplying (not adding up steps) keeps float error from piling up; labels are formatted anyway.
    val count = ((end - start) / step).roundToLong().toInt()
    return (0..count).map { start + it * step }
}
