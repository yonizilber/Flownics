package com.yonizilber.flownics.feature.library.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.history.ExerciseProgress
import com.yonizilber.flownics.domain.history.ExerciseSession
import com.yonizilber.flownics.domain.history.ProgressMetric
import com.yonizilber.flownics.domain.history.Record
import com.yonizilber.flownics.domain.model.MeasureMode
import com.yonizilber.flownics.ui.component.DateTexts
import com.yonizilber.flownics.ui.component.ProgressChart
import com.yonizilber.flownics.ui.component.labelRes
import com.yonizilber.flownics.ui.component.metricText
import com.yonizilber.flownics.ui.component.rememberDateTexts

/**
 * Records, a chart of one metric per workout, and the same values as a list (the chart's text
 * twin, and the way into each workout).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExerciseProgressSection(
    history: List<ExerciseSession>,
    measureMode: MeasureMode,
    onOpenSession: (Long) -> Unit,
) {
    if (history.isEmpty()) {
        Text(
            stringResource(R.string.progress_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val res = LocalResources.current
    val dates = rememberDateTexts()
    val available = ExerciseProgress.availableMetrics(history)
    if (available.isEmpty()) {
        // Every set was skipped or logged without numbers.
        Text(stringResource(R.string.progress_no_numbers), style = MaterialTheme.typography.bodyMedium)
        return
    }
    var chosen by rememberSaveable { mutableStateOf(defaultMetric(measureMode)) }
    val metric = chosen.takeIf { it in available } ?: available.first()
    val points = ExerciseProgress.series(history, metric)
    var showAll by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.progress_records), style = MaterialTheme.typography.titleSmall)
                ExerciseProgress.records(history).forEach { record -> RecordRow(record, dates) }
            }
        }

        if (available.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                available.forEach { m ->
                    FilterChip(selected = m == metric, onClick = { chosen = m }, label = { Text(stringResource(m.labelRes)) })
                }
            }
        }

        if (points.size >= 2) {
            val best = points.maxOf { it.value }
            ProgressChart(
                points = points,
                formatValue = { metricText(res, metric, it) },
                formatDate = dates::dayMonth,
                caption = stringResource(R.string.progress_chart_caption, stringResource(metric.descriptionRes)),
                accessibilityText = stringResource(
                    R.string.progress_chart_a11y,
                    stringResource(metric.descriptionRes),
                    points.size,
                    metricText(res, metric, points.first().value),
                    dates.date(points.first().atMillis),
                    metricText(res, metric, points.last().value),
                    dates.date(points.last().atMillis),
                    metricText(res, metric, best),
                ),
            )
        } else {
            Text(
                stringResource(R.string.progress_chart_needs_two),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Newest first, like the history tab.
        val rows = points.asReversed()
        val visible = if (showAll) rows else rows.take(RECENT_ROWS)
        Column {
            Text(
                stringResource(R.string.progress_workouts, stringResource(metric.descriptionRes)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            visible.forEachIndexed { index, point ->
                if (index > 0) HorizontalDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSession(point.sessionId) }
                        .padding(vertical = 10.dp),
                ) {
                    Text(dates.fullDate(point.atMillis), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(metricText(res, metric, point.value), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (rows.size > RECENT_ROWS) {
                TextButton(onClick = { showAll = !showAll }) {
                    Text(if (showAll) stringResource(R.string.progress_show_less) else stringResource(R.string.progress_show_all, rows.size))
                }
            }
        }
    }
}

@Composable
private fun RecordRow(record: Record, dates: DateTexts) {
    val res = LocalResources.current
    val value = metricText(res, record.metric, record.value).let { text ->
        // "60 kg × 5": a weight record says how many reps it was lifted for.
        val reps = record.set?.actual?.reps
        if (record.metric == ProgressMetric.WEIGHT && reps != null) stringResource(R.string.record_weight_reps, text, reps) else text
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(record.metric.labelRes), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Column {
            Text(value, style = MaterialTheme.typography.bodyMedium)
            Text(dates.date(record.atMillis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun defaultMetric(mode: MeasureMode): ProgressMetric = when (mode) {
    MeasureMode.REPS_WEIGHT -> ProgressMetric.WEIGHT
    MeasureMode.REPS -> ProgressMetric.REPS
    MeasureMode.TIME -> ProgressMetric.SECONDS
    MeasureMode.DISTANCE -> ProgressMetric.DISTANCE
}

/** What a chart of this metric shows, for its caption: "the heaviest set (kg)". */
private val ProgressMetric.descriptionRes: Int
    get() = when (this) {
        ProgressMetric.WEIGHT -> R.string.metric_desc_weight
        ProgressMetric.VOLUME -> R.string.metric_desc_volume
        ProgressMetric.REPS -> R.string.metric_desc_reps
        ProgressMetric.SECONDS -> R.string.metric_desc_seconds
        ProgressMetric.DISTANCE -> R.string.metric_desc_distance
    }

private const val RECENT_ROWS = 5
