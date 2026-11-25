package com.yonizilber.flownics.feature.me

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.me.Metric
import com.yonizilber.flownics.domain.me.Trend
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max

/**
 * Data about the user, in one place: what Garmin measured day by day (sleep, recovery, activity)
 * and what Flownics works out from the recordings (training load). Each metric is a card with
 * its latest value, the user's own 7- and 28-day averages, and a captioned chart.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MeScreen(
    onOpenSleep: () -> Unit,
    onOpenGarmin: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: MeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val days by viewModel.days.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.me_title)) },
            actions = {
                IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.nav_settings)) }
            },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MeViewModel.RANGES.forEach { n ->
                    FilterChip(selected = n == days, onClick = { viewModel.setDays(n) }, label = { Text(stringResource(R.string.me_days, n)) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onOpenSleep, label = { Text(stringResource(R.string.me_sleep_screen)) }, leadingIcon = { Icon(Icons.Outlined.Bedtime, null) })
                AssistChip(onClick = onOpenGarmin, label = { Text(stringResource(R.string.me_garmin)) }, leadingIcon = { Icon(Icons.Outlined.Watch, null) })
            }
            val s = state ?: return@Column
            if (s.garminDays == 0) {
                Text(stringResource(R.string.me_no_garmin), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val byMetric = s.trends.associateBy { it.metric }
            Section(stringResource(R.string.me_section_recovery))
            listOf(Metric.RESTING_HR, Metric.HRV, Metric.BODY_BATTERY_WAKE, Metric.STRESS).forEach { TrendCard(byMetric.getValue(it), s.from, s.to) }
            Section(stringResource(R.string.me_section_sleep))
            listOf(Metric.SLEEP_HOURS, Metric.SLEEP_SCORE, Metric.RESPIRATION).forEach { TrendCard(byMetric.getValue(it), s.from, s.to) }
            Section(stringResource(R.string.me_section_activity))
            listOf(Metric.STEPS, Metric.VO2MAX).forEach { TrendCard(byMetric.getValue(it), s.from, s.to) }
            if (s.zonesKnown) TrendCard(byMetric.getValue(Metric.LOAD), s.from, s.to)
            else Text(stringResource(R.string.me_load_no_zones), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Section(stringResource(R.string.me_section_body))
            BodySection(s, byMetric.getValue(Metric.WEIGHT), viewModel)
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

private val Metric.titleRes: Int get() = when (this) {
    Metric.RESTING_HR -> R.string.me_rhr
    Metric.HRV -> R.string.me_hrv
    Metric.SLEEP_HOURS -> R.string.me_sleep_hours
    Metric.SLEEP_SCORE -> R.string.me_sleep_score
    Metric.BODY_BATTERY_WAKE -> R.string.me_bb
    Metric.STRESS -> R.string.me_stress
    Metric.RESPIRATION -> R.string.me_respiration
    Metric.STEPS -> R.string.me_steps
    Metric.VO2MAX -> R.string.me_vo2
    Metric.LOAD -> R.string.me_load
    Metric.WEIGHT -> R.string.me_weight
}

private fun Metric.format(v: Double): String = when (this) {
    Metric.SLEEP_HOURS -> "%d:%02d".format(Locale.ROOT, v.toInt(), ((v - v.toInt()) * 60).toInt())
    Metric.RESPIRATION, Metric.VO2MAX, Metric.WEIGHT -> "%.1f".format(Locale.ROOT, v)
    else -> "%,d".format(Locale.ROOT, Math.round(v))
}

@Composable
internal fun TrendCard(trend: Trend, from: LocalDate, to: LocalDate) {
    val m = trend.metric
    val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(stringResource(m.titleRes), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                trend.latest?.let { (d, v) ->
                    Text(m.format(v), style = MaterialTheme.typography.titleLarge)
                    Text("  " + d.format(dates), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (trend.points.isEmpty()) {
                Text(stringResource(R.string.me_no_values), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            val averages = listOfNotNull(
                trend.mean7?.let { stringResource(R.string.me_avg7, m.format(it)) },
                trend.mean28?.let { stringResource(R.string.me_avg28, m.format(it)) },
                if (trend.usualLow != null && trend.usualHigh != null) stringResource(R.string.me_usual, m.format(trend.usualLow), m.format(trend.usualHigh)) else null,
            ).joinToString(" · ")
            if (averages.isNotEmpty()) Text(averages, style = MaterialTheme.typography.bodySmall)
            // Time runs left to right in both languages, as the caption says.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column {
                    TrendChart(trend, from, to)
                    Row {
                        Text(from.format(dates), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(to.format(dates), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(
                stringResource(
                    when (m) {
                        Metric.LOAD -> R.string.me_caption_load
                        Metric.WEIGHT -> R.string.me_caption_weight
                        else -> R.string.me_caption
                    },
                    trend.points.size,
                ) + if (trend.usualLow != null) " " + stringResource(R.string.me_caption_band) else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Days left to right (oldest first, in either language direction, as the caption says); a dot per
 * day with a value, joined only to the next day, so a missing day shows as a gap. The dashed
 * line is the 28-day average; the shaded band is Garmin's usual range where it gives one. The
 * value axis is labelled with its lowest and highest values.
 */
@Composable
private fun TrendChart(trend: Trend, from: LocalDate, to: LocalDate) {
    val values = trend.points.map { it.second } + listOfNotNull(trend.mean28, trend.usualLow, trend.usualHigh)
    val lo0 = values.min()
    val hi0 = values.max()
    val pad = max((hi0 - lo0) * 0.1, if (hi0 == lo0) max(1.0, hi0 * 0.05) else 0.0)
    val lo = if (trend.metric == Metric.LOAD) 0.0 else lo0 - pad
    val hi = hi0 + pad
    val span = max(1L, ChronoUnit.DAYS.between(from, to))
    val line = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val band = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val description = stringResource(R.string.me_chart_a11y, stringResource(trend.metric.titleRes), trend.points.size, trend.metric.format(lo0), trend.metric.format(hi0))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.height(96.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(trend.metric.format(hi), style = MaterialTheme.typography.labelSmall, color = muted)
            Text(trend.metric.format(lo), style = MaterialTheme.typography.labelSmall, color = muted)
        }
        Canvas(
            Modifier.weight(1f).height(96.dp).padding(start = 6.dp).semantics { contentDescription = description },
        ) {
            // Left to right in time, whatever the layout direction.
            fun x(d: LocalDate) = ChronoUnit.DAYS.between(from, d).toFloat() / span * size.width
            fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
            if (trend.usualLow != null && trend.usualHigh != null) {
                drawRect(band, topLeft = Offset(0f, y(trend.usualHigh)), size = Size(size.width, y(trend.usualLow) - y(trend.usualHigh)))
            }
            trend.mean28?.let { m ->
                drawLine(muted, Offset(0f, y(m)), Offset(size.width, y(m)), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            }
            val pts = trend.points
            pts.zipWithNext().forEach { (a, b) ->
                if (ChronoUnit.DAYS.between(a.first, b.first) == 1L) {
                    drawLine(line, Offset(x(a.first), y(a.second)), Offset(x(b.first), y(b.second)), strokeWidth = 4f, cap = StrokeCap.Round)
                }
            }
            val radius = if (pts.size > 60) 3f else 5f
            pts.forEach { (d, v) -> drawCircle(line, radius, Offset(x(d), y(v))) }
        }
    }
}
