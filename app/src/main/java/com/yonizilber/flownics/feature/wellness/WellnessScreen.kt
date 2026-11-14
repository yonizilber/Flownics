package com.yonizilber.flownics.feature.wellness

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.wellness.SleepLevel
import com.yonizilber.flownics.domain.wellness.WellnessDay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Sleep and recovery from the watch: one night in detail (its stages over time, with its
 * numbers), then every day in a list. All of it as Garmin worked it out; the screen says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WellnessScreen(onBack: () -> Unit, onOpenGarmin: () -> Unit, viewModel: WellnessViewModel = hiltViewModel()) {
    val days by viewModel.days.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wellness_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
    ) { innerPadding ->
        val list = days
        when {
            list == null -> Box(Modifier.padding(innerPadding).fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            list.isEmpty() -> Column(Modifier.padding(innerPadding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.wellness_empty), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onOpenGarmin) { Text(stringResource(R.string.garmin_title)) }
            }
            else -> {
                val day = list.firstOrNull { it.date.toString() == selected } ?: list.firstOrNull { it.hasSleep } ?: list.first()
                LazyColumn(Modifier.padding(innerPadding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { NightCard(day) }
                    item {
                        Text(stringResource(R.string.wellness_source_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item { Text(stringResource(R.string.wellness_days), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                    items(list, key = { it.date.toString() }) { d ->
                        DayRow(d, selected = d.date == day.date, onClick = { selected = d.date.toString() })
                    }
                }
            }
        }
    }
}

@Composable
private fun NightCard(day: WellnessDay) {
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(day.date.format(date), style = MaterialTheme.typography.titleMedium)
            if (day.stages.isNotEmpty()) {
                Hypnogram(day)
                Text(stringResource(R.string.wellness_hypnogram_caption), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!day.stagesConfirmed) {
                    Text(stringResource(R.string.wellness_stages_unconfirmed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            } else if (day.hasSleep) {
                Text(stringResource(R.string.wellness_no_timeline), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StageLegend()
            HorizontalDivider()
            WellnessFacts(day)
        }
    }
}

/** The colour of each stage, in light and dark; each stage also has its own row and its name beside it. */
@Composable
fun stageColor(level: SleepLevel): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (level) {
        SleepLevel.DEEP -> if (dark) Color(0xFF6E8BFF) else Color(0xFF1F3FBF)
        SleepLevel.LIGHT -> if (dark) Color(0xFF7CC8F8) else Color(0xFF3A9AD9)
        SleepLevel.REM -> if (dark) Color(0xFFCB8CF5) else Color(0xFF8E3CC9)
        SleepLevel.AWAKE -> if (dark) Color(0xFFF5B15C) else Color(0xFFC46A00)
    }
}

@Composable
fun stageName(level: SleepLevel): String = stringResource(
    when (level) {
        SleepLevel.DEEP -> R.string.wellness_deep
        SleepLevel.LIGHT -> R.string.wellness_light
        SleepLevel.REM -> R.string.wellness_rem
        SleepLevel.AWAKE -> R.string.wellness_awake
    },
)

private val ROWS = listOf(SleepLevel.AWAKE, SleepLevel.REM, SleepLevel.LIGHT, SleepLevel.DEEP)

/** The night stage by stage: a row per stage (awake on top, deep at the bottom), time left to right. */
@Composable
private fun Hypnogram(day: WellnessDay) {
    val stages = day.stages
    val start = stages.first().startMillis
    val end = stages.last().endMillis
    val colors = ROWS.associateWith { stageColor(it) }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().format(time)
    val description = stringResource(R.string.wellness_hypnogram_description, clock(start), clock(end))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.height(ROW_HEIGHT * ROWS.size), verticalArrangement = Arrangement.SpaceEvenly) {
            ROWS.forEach { Text(stageName(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        // Time runs left to right whatever the language, as on a clock axis.
        Canvas(
            Modifier.weight(1f).height(ROW_HEIGHT * ROWS.size).padding(start = 8.dp).semantics { contentDescription = description },
        ) {
            val rowH = size.height / ROWS.size
            val span = (end - start).coerceAtLeast(1).toFloat()
            ROWS.indices.forEach { i -> drawLine(grid, Offset(0f, rowH * (i + 1)), Offset(size.width, rowH * (i + 1)), strokeWidth = 1f) }
            stages.forEach { s ->
                val row = ROWS.indexOf(s.level)
                val x = (s.startMillis - start) / span * size.width
                // A 1 px gap between stretches keeps them apart.
                val w = ((s.endMillis - s.startMillis) / span * size.width - 1f).coerceAtLeast(1f)
                drawRoundRect(colors.getValue(s.level), Offset(x, row * rowH + rowH * 0.15f), Size(w, rowH * 0.7f), CornerRadius(3f, 3f))
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 48.dp)) {
        Text(clock(start), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(clock(end), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val ROW_HEIGHT = 26.dp

@Composable
private fun StageLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ROWS.reversed().forEach { level ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(stageColor(level)))
                Text(stageName(level), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Every number Garmin gave for the day, each with what it is. */
@Composable
fun WellnessFacts(day: WellnessDay) {
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().format(time)
    val lines = buildList {
        day.sleepSeconds?.let { s ->
            val window = if (day.sleepStartMillis != null && day.sleepEndMillis != null) " (${clock(day.sleepStartMillis)}–${clock(day.sleepEndMillis)})" else ""
            add(stringResource(R.string.wellness_sleep_line, hm(s)) + window)
        }
        val parts = listOfNotNull(
            day.deepSeconds?.let { stageName(SleepLevel.DEEP) + " " + hm(it) },
            day.lightSeconds?.let { stageName(SleepLevel.LIGHT) + " " + hm(it) },
            day.remSeconds?.let { stageName(SleepLevel.REM) + " " + hm(it) },
            day.awakeSeconds?.let { stageName(SleepLevel.AWAKE) + " " + hm(it) },
        )
        if (parts.isNotEmpty()) add(parts.joinToString(" · "))
        day.sleepScore?.let { add(stringResource(R.string.wellness_score_line, it)) }
        day.hrvLastNight?.let { v ->
            add(
                stringResource(R.string.wellness_hrv_line, v) +
                    (day.hrvWeeklyAvg?.let { " · " + stringResource(R.string.wellness_hrv_week, it) } ?: "") +
                    (if (day.hrvBaselineLow != null && day.hrvBaselineHigh != null) " · " + stringResource(R.string.wellness_hrv_baseline, day.hrvBaselineLow, day.hrvBaselineHigh) else "") +
                    (day.hrvStatus?.let { " · $it" } ?: ""),
            )
        }
        day.restingHr?.let { add(stringResource(R.string.wellness_rhr_line, it)) }
        if (day.bodyBatteryHigh != null || day.bodyBatteryAtWake != null) {
            add(
                listOfNotNull(
                    day.bodyBatteryAtWake?.let { stringResource(R.string.wellness_bb_wake, it) },
                    if (day.bodyBatteryHigh != null && day.bodyBatteryLow != null) stringResource(R.string.wellness_bb_range, day.bodyBatteryLow, day.bodyBatteryHigh) else null,
                    day.bodyBatteryCharged?.let { stringResource(R.string.wellness_bb_charged, it) },
                    day.bodyBatteryDrained?.let { stringResource(R.string.wellness_bb_drained, it) },
                ).joinToString(" · "),
            )
        }
        day.stressAvg?.let { add(stringResource(R.string.wellness_stress_line, it)) }
        day.sleepAvgRespiration?.let { add(stringResource(R.string.wellness_respiration_line, it)) }
        day.spo2Avg?.let { add(stringResource(R.string.wellness_spo2_line, it) + (day.spo2Lowest?.let { l -> " · " + stringResource(R.string.wellness_spo2_low, l) } ?: "")) }
        day.steps?.let { add(stringResource(R.string.wellness_steps_line, it)) }
        day.vo2Max?.let { add(stringResource(R.string.wellness_vo2_line, it)) }
    }
    if (lines.isEmpty()) {
        Text(stringResource(R.string.wellness_nothing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun DayRow(day: WellnessDay, selected: Boolean, onClick: () -> Unit) {
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(day.date.format(date), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                listOfNotNull(
                    day.sleepSeconds?.let { hm(it) },
                    day.hrvLastNight?.let { stringResource(R.string.wellness_hrv_short, it) },
                    day.restingHr?.let { stringResource(R.string.wellness_rhr_short, it) },
                    day.bodyBatteryAtWake?.let { stringResource(R.string.wellness_bb_short, it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StageShares(day)
    }
}

/** The night's time in each stage as one bar, deep to awake, from Garmin's totals. */
@Composable
private fun StageShares(day: WellnessDay) {
    val shares = listOf(SleepLevel.DEEP to day.deepSeconds, SleepLevel.LIGHT to day.lightSeconds, SleepLevel.REM to day.remSeconds, SleepLevel.AWAKE to day.awakeSeconds)
        .mapNotNull { (l, s) -> s?.takeIf { it > 0 }?.let { l to it } }
    val total = shares.sumOf { it.second }
    if (total == 0) return
    Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        shares.forEach { (level, s) ->
            Box(Modifier.weight(s.toFloat() / total).height(8.dp).background(stageColor(level)))
        }
    }
}

private fun hm(seconds: Int): String = "%d:%02d".format(seconds / 3600, seconds % 3600 / 60)
