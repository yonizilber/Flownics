package com.yonizilber.flownics.feature.history.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Button
import com.yonizilber.flownics.domain.history.MuscleLoad
import com.yonizilber.flownics.domain.model.Activity
import com.yonizilber.flownics.feature.activity.displayTitle
import com.yonizilber.flownics.feature.activity.icon
import com.yonizilber.flownics.feature.activity.summary
import com.yonizilber.flownics.domain.model.Muscle
import com.yonizilber.flownics.domain.history.PeriodTotals
import com.yonizilber.flownics.feature.ai.AiActionButton
import com.yonizilber.flownics.ui.component.DateTexts
import com.yonizilber.flownics.ui.component.MuscleLoadCard
import com.yonizilber.flownics.ui.component.formatDuration
import com.yonizilber.flownics.ui.component.formatVolume
import com.yonizilber.flownics.ui.component.labelRes
import com.yonizilber.flownics.ui.component.rememberDateTexts
import java.text.DecimalFormat

@Composable
fun HistoryScreen(
    onOpenSession: (Long) -> Unit,
    onOpenActivity: (Long) -> Unit,
    onLogActivity: () -> Unit,
    onAskAi: () -> Unit,
    onOpenMuscle: (Muscle) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dates = rememberDateTexts()

    when {
        state.loading -> Box(modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        state.items.isEmpty() -> EmptyHistory(onLogActivity, modifier.fillMaxSize())
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "week") { WeekCard(state.lastWeek, state.lastWeekKm) }
            item(key = "actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onLogActivity, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.activity_log), modifier = Modifier.padding(start = 6.dp))
                    }
                    AiActionButton(
                        text = stringResource(R.string.ai_history_ask_short),
                        enabled = true,
                        outlined = true,
                        onClick = onAskAi,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item(key = "muscles") {
                MuscleLoadCard(
                    load = state.lastWeekMuscles,
                    title = stringResource(R.string.history_muscles_title),
                    emptyText = stringResource(R.string.history_muscles_none),
                    caption = stringResource(R.string.history_muscles_caption),
                    untouchedTitle = stringResource(R.string.history_muscles_untouched),
                    onMuscleClick = onOpenMuscle,
                )
            }
            state.items.forEachIndexed { index, item ->
                val previous = state.items.getOrNull(index - 1)
                if (previous == null || !dates.sameMonth(previous.atMillis, item.atMillis)) {
                    item(key = "month-${item.key}") {
                        Text(
                            dates.monthYear(item.atMillis),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                item(key = item.key) {
                    when (item) {
                        is HistoryItem.Workout -> SessionCard(item.entry, dates, onClick = { onOpenSession(item.entry.header.id) })
                        is HistoryItem.Done -> ActivityCard(item.activity, dates, onClick = { onOpenActivity(item.activity.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekCard(totals: PeriodTotals, km: Double) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.history_last_7_days),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatTile(stringResource(R.string.history_stat_workouts), totals.workouts.toString())
                StatTile(stringResource(R.string.history_stat_time), formatDuration(totals.seconds.toInt()))
                StatTile(stringResource(R.string.history_stat_sets), totals.setsDone.toString())
                if (km > 0) StatTile(stringResource(R.string.history_stat_km), String.format(java.util.Locale.ROOT, "%.1f", km))
                StatTile(stringResource(R.string.history_stat_volume), stringResource(R.string.target_weight, formatVolume(totals.volumeKg)))
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun SessionCard(entry: HistoryEntry, dates: DateTexts, onClick: () -> Unit) {
    val header = entry.header
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${dates.fullDate(header.startedAtMillis)} · ${dates.time(header.startedAtMillis)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(header.workoutName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sessionSummary(header.durationSeconds.toInt(), entry.setsDone, entry.volumeKg), style = MaterialTheme.typography.bodyMedium)
            if (header.notes.isNotBlank()) {
                Text(
                    header.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ActivityCard(activity: Activity, dates: DateTexts, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(activity.type.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${dates.fullDate(activity.startedAtMillis)} · ${dates.time(activity.startedAtMillis)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(activity.displayTitle(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(activity.summary(), style = MaterialTheme.typography.bodyMedium)
                if (activity.segments.isNotEmpty()) {
                    Text(
                        LocalResources.current.getQuantityString(R.plurals.activity_segments_count, activity.segments.size, activity.segments.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** "45 min · 18 sets · 2,400 kg"; volume left out when nothing was weighted. */
@Composable
fun sessionSummary(seconds: Int, setsDone: Int, volumeKg: Double): String = buildList {
    add(formatDuration(seconds))
    add(LocalResources.current.getQuantityString(R.plurals.history_sets, setsDone, setsDone))
    if (volumeKg > 0) add(stringResource(R.string.target_weight, formatVolume(volumeKg)))
}.joinToString(" · ")

@Composable
private fun EmptyHistory(onLogActivity: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.history_empty_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.history_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onLogActivity) { Text(stringResource(R.string.activity_log)) }
    }
}
