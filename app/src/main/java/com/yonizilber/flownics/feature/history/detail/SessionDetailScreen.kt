package com.yonizilber.flownics.feature.history.detail

import com.yonizilber.flownics.data.garmin.ZonesInfo
import com.yonizilber.flownics.ui.component.ZonesBar
import com.yonizilber.flownics.domain.model.SetHeart
import com.yonizilber.flownics.domain.fit.HeartPoint
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.Canvas
import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Sports
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.calendar.CalendarEvents
import com.yonizilber.flownics.domain.history.ProgressMetric
import com.yonizilber.flownics.domain.history.volumeKg
import com.yonizilber.flownics.domain.model.SetLog
import com.yonizilber.flownics.domain.model.WorkoutSession
import com.yonizilber.flownics.domain.workout.Side
import com.yonizilber.flownics.domain.workout.WorkStep
import com.yonizilber.flownics.ui.component.durationText
import com.yonizilber.flownics.ui.component.formatDuration
import com.yonizilber.flownics.ui.component.formatVolume
import com.yonizilber.flownics.ui.component.labelRes
import com.yonizilber.flownics.ui.component.loggedText
import com.yonizilber.flownics.ui.component.progressText
import com.yonizilber.flownics.ui.component.rememberDateTexts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    onBack: () -> Unit,
    onOpenExercise: (Long) -> Unit,
    onOpenWorkout: (Long) -> Unit,
    onAskCoach: (Long) -> Unit,
    onOpenActivity: (Long) -> Unit,
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val watch by viewModel.watch.collectAsStateWithLifecycle()
    val zonesInfo by viewModel.zonesInfo.collectAsStateWithLifecycle()
    val loaded = state as? SessionDetailUiState.Loaded
    var menuOpen by remember { mutableStateOf(false) }
    var editingNotes by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(loaded?.session?.workoutName.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (loaded != null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.coach_ask)) },
                                    leadingIcon = { Icon(Icons.Outlined.Sports, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onAskCoach(loaded.session.id)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.session_edit_notes)) },
                                    leadingIcon = { Icon(Icons.Outlined.EditNote, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        editingNotes = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_delete)) },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        confirmDelete = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            when (val s = state) {
                SessionDetailUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                SessionDetailUiState.NotFound -> Text(
                    stringResource(R.string.session_not_found),
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
                is SessionDetailUiState.Loaded -> SessionContent(s, onOpenExercise, onOpenWorkout, watch, viewModel::keepApart, onOpenActivity, zonesInfo)
            }
        }
    }

    if (editingNotes && loaded != null) {
        NotesDialog(
            initial = loaded.session.notes,
            onDismiss = { editingNotes = false },
            onSave = {
                editingNotes = false
                viewModel.updateNotes(it)
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.session_delete_title)) },
            text = { Text(stringResource(R.string.session_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(onBack)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionContent(
    state: SessionDetailUiState.Loaded,
    onOpenExercise: (Long) -> Unit,
    onOpenWorkout: (Long) -> Unit,
    watch: WatchData?,
    onKeepApart: () -> Unit,
    onOpenActivity: (Long) -> Unit,
    zonesInfo: ZonesInfo?,
) {
    val session = state.session
    val dates = rememberDateTexts()
    val context = LocalContext.current
    val res = LocalResources.current
    val ended = session.endedAtMillis ?: session.startedAtMillis
    val seconds = ((ended - session.startedAtMillis) / 1000).toInt()
    val done = state.logs.count { !it.skipped }
    val skipped = state.logs.size - done
    val volume = state.logs.volumeKg()

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "when") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(dates.fullDate(session.startedAtMillis), style = MaterialTheme.typography.titleMedium)
                Text(
                    "${dates.time(session.startedAtMillis)}–${dates.time(ended)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "stats") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Stat(stringResource(R.string.player_summary_duration), formatDuration(seconds))
                    Stat(stringResource(R.string.player_summary_sets), done.toString())
                    if (skipped > 0) Stat(stringResource(R.string.player_summary_skipped), skipped.toString())
                    if (volume > 0) Stat(stringResource(R.string.player_summary_volume), formatVolume(volume))
                }
            }
        }
        watch?.let { w ->
            item(key = "watch") { WatchCard(w, state.logs, session, onKeepApart, onOpenActivity, zonesInfo) }
        }
        if (state.logs.size < state.plannedSets) {
            item(key = "early") {
                Text(
                    stringResource(R.string.session_ended_early, state.logs.size, state.plannedSets),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "actions") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    CalendarEvents.done(
                        context,
                        title = session.workoutName,
                        description = calendarDescription(res, session, state),
                        beginMillis = session.startedAtMillis,
                        endMillis = ended,
                    )
                }) {
                    Icon(Icons.Outlined.Event, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.calendar_add), modifier = Modifier.padding(start = 8.dp))
                }
                if (state.templateExists) {
                    OutlinedButton(onClick = { onOpenWorkout(session.templateId!!) }) {
                        Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.session_open_workout), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        if (session.notes.isNotBlank()) {
            item(key = "notes") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.session_notes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(session.notes, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (state.logs.isEmpty()) {
            item(key = "nothing") {
                Text(stringResource(R.string.session_no_sets), style = MaterialTheme.typography.bodyLarge)
            }
        }
        items(state.exercises, key = { it.exerciseId }) { exercise ->
            ExerciseCard(exercise, session, watch?.bySet.orEmpty(), onOpen = { onOpenExercise(exercise.exerciseId) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExerciseCard(exercise: ExerciseSets, session: WorkoutSession, heart: Map<Int, SetHeart>, onOpen: () -> Unit) {
    val res = LocalResources.current
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                exercise.name.ifBlank { stringResource(R.string.session_exercise_deleted) },
                style = MaterialTheme.typography.titleMedium,
                color = if (exercise.exists) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (exercise.exists) Modifier.clickable(onClick = onOpen) else Modifier,
            )
            if (!exercise.exists) {
                Text(
                    stringResource(R.string.session_exercise_removed_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (exercise.newRecords.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    exercise.newRecords.sortedBy { it.ordinal }.forEach { metric -> RecordBadge(metric) }
                }
            }
            exercise.sets.forEach { log ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        setLabel(res, log, session),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        loggedText(res, log.actual, log.skipped),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (log.skipped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
                heart[log.stepIndex]?.let { h ->
                    Text(
                        stringResource(R.string.watch_set_hr, h.avg, h.max) + (h.drop?.let { " · " + stringResource(R.string.watch_set_drop, it) } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * The watch's heart rate across the workout, with each set marked, so it shows how it climbs in a
 * set and falls in the rest. Totals above; "keep apart" if the recording wasn't of this workout.
 */
@Composable
private fun WatchCard(watch: WatchData, logs: List<SetLog>, session: WorkoutSession, onKeepApart: () -> Unit, onOpenActivity: (Long) -> Unit, zonesInfo: ZonesInfo?) {
    val a = watch.activity
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Watch, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.watch_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(
                listOfNotNull(
                    a.avgHeartRate?.let { stringResource(R.string.watch_avg_hr, it) },
                    a.maxHeartRate?.let { stringResource(R.string.watch_max_hr, it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (watch.heart.size > 1) {
                val start = session.startedAtMillis
                val end = session.endedAtMillis ?: watch.heart.last().atMillis
                val bands = logs.filterNot { it.skipped }.map { log ->
                    (log.startedAtMillis ?: (log.completedAtMillis - (log.actual.seconds ?: 40) * 1000L)) to log.completedAtMillis
                }
                HeartChart(watch.heart, bands, start, end)
                Text(stringResource(R.string.watch_chart_caption), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            zonesInfo?.let { z -> a.metrics?.let { m -> ZonesBar(m.hrHistogram, z.zones, z.maxFromRecordings) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onOpenActivity(a.id) }) { Text(stringResource(R.string.watch_open)) }
                TextButton(onClick = onKeepApart) { Text(stringResource(R.string.watch_keep_apart)) }
            }
        }
    }
}

@Composable
private fun HeartChart(points: List<HeartPoint>, bands: List<Pair<Long, Long>>, start: Long, end: Long) {
    val line = MaterialTheme.colorScheme.error
    val band = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val min = (points.minOf { it.bpm } - 5).coerceAtLeast(40)
    val max = points.maxOf { it.bpm } + 5
    val description = stringResource(R.string.watch_chart_description, points.minOf { it.bpm }, points.maxOf { it.bpm }, bands.size)
    Row {
        Column(Modifier.height(140.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("$max", style = MaterialTheme.typography.labelSmall)
            Text("$min", style = MaterialTheme.typography.labelSmall)
        }
        Canvas(
            Modifier
                .weight(1f)
                .height(140.dp)
                .padding(start = 6.dp)
                .semantics { contentDescription = description },
        ) {
            val span = (end - start).coerceAtLeast(1).toFloat()
            // Time runs left to right in every language, as on the watch.
            fun x(t: Long) = ((t - start) / span).coerceIn(0f, 1f) * size.width
            fun y(bpm: Int) = size.height - (bpm - min).toFloat() / (max - min) * size.height
            bands.forEach { (s, e) -> drawRect(band, topLeft = Offset(x(s), 0f), size = Size((x(e) - x(s)).coerceAtLeast(2f), size.height)) }
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
            val path = Path()
            points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.atMillis), y(p.bpm)) else path.lineTo(x(p.atMillis), y(p.bpm)) }
            drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

@Composable
private fun RecordBadge(metric: ProgressMetric) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Outlined.EmojiEvents, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(16.dp))
            Text(
                stringResource(R.string.record_new, stringResource(metric.labelRes)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/** "Set 2/3 (left)", from the frozen plan step the log belongs to. */
private fun setLabel(res: Resources, log: SetLog, session: WorkoutSession): String {
    val step = session.steps.getOrNull(log.stepIndex) as? WorkStep
    val base = step?.let { progressText(res, it) } ?: res.getString(R.string.session_set, log.set)
    return when (log.side) {
        Side.LEFT -> "$base (${res.getString(R.string.side_left)})"
        Side.RIGHT -> "$base (${res.getString(R.string.side_right)})"
        null -> base
    }
}

/** Plain text for the calendar event: totals, then one line per exercise, then the notes. */
private fun calendarDescription(res: Resources, session: WorkoutSession, state: SessionDetailUiState.Loaded): String {
    val done = state.logs.count { !it.skipped }
    val volume = state.logs.volumeKg()
    val ended = session.endedAtMillis ?: session.startedAtMillis
    val totals = buildList {
        add(durationText(res, ((ended - session.startedAtMillis) / 1000).toInt()))
        add(res.getQuantityString(R.plurals.history_sets, done, done))
        if (volume > 0) add(res.getString(R.string.target_weight, formatVolume(volume)))
    }.joinToString(" · ")
    val lines = state.exercises.map { exercise ->
        val sets = exercise.sets.count { !it.skipped }
        "${exercise.name.ifBlank { res.getString(R.string.session_exercise_deleted) }}: ${res.getQuantityString(R.plurals.history_sets, sets, sets)}"
    }
    return buildString {
        appendLine(totals)
        appendLine()
        lines.forEach(::appendLine)
        if (session.notes.isNotBlank()) {
            appendLine()
            appendLine(session.notes)
        }
        appendLine()
        append(res.getString(R.string.calendar_footer))
    }
}

@Composable
private fun NotesDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var notes by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.session_notes)) },
        text = {
            OutlinedTextField(value = notes, onValueChange = { notes = it }, minLines = 3, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onSave(notes) }) { Text(stringResource(R.string.session_notes_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
