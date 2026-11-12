package com.yonizilber.flownics.feature.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.calendar.CalendarSync
import com.yonizilber.flownics.domain.model.ActivityFormat
import com.yonizilber.flownics.domain.model.ActivityType
import com.yonizilber.flownics.domain.model.PlanKind
import com.yonizilber.flownics.domain.model.SegmentKind
import com.yonizilber.flownics.domain.plan.DoneItem
import com.yonizilber.flownics.domain.plan.StepVerdict
import com.yonizilber.flownics.feature.activity.displayTitle
import com.yonizilber.flownics.feature.activity.icon
import com.yonizilber.flownics.feature.activity.labelRes
import com.yonizilber.flownics.feature.activity.summary
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Plan a day: a saved workout, or an activity (a run with its parts and pace targets). Once
 * something matching is done that day, the plan shows it, and for a run, each part beside its target.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlanEditScreen(
    onClose: () -> Unit,
    onOpenWorkout: (Long) -> Unit,
    onOpenSession: (Long) -> Unit,
    onOpenActivity: (Long) -> Unit,
    viewModel: PlanEditViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val workouts by viewModel.workoutList.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    var addRepeats by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var workoutMenu by remember { mutableStateOf(false) }

    LaunchedEffect(s.closed) { if (s.closed) onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (s.isNew) R.string.plan_new else R.string.plan_edit)) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
                actions = {
                    if (!s.isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete)) }
                },
            )
        },
    ) { innerPadding ->
        if (s.loading) return@Scaffold
        Column(
            Modifier.padding(innerPadding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            outcome?.done?.let { done -> DoneCard(done, onOpenSession, onOpenActivity) }
            outcome?.comparison?.takeIf { it.isNotEmpty() }?.let { rows ->
                Text(stringResource(R.string.plan_vs_actual), style = MaterialTheme.typography.titleMedium)
                rows.forEachIndexed { i, row ->
                    val planned = listOfNotNull(
                        row.planned.distanceMeters?.let { stringResource(R.string.activity_km, ActivityFormat.formatKm(it)) },
                        row.planned.durationSeconds?.let { ActivityFormat.formatDuration(it) },
                        CalendarSync.paceRange(row.planned)?.let { stringResource(R.string.activity_pace, it) },
                    ).joinToString(" · ")
                    val actual = row.actual?.let { a ->
                        listOfNotNull(
                            a.paceSecondsPerKm?.let { stringResource(R.string.activity_pace, ActivityFormat.formatPace(it)) },
                            a.durationSeconds?.let { ActivityFormat.formatDuration(it) },
                            a.avgHeartRate?.let { stringResource(R.string.activity_hr, it) },
                        ).joinToString(" · ")
                    } ?: stringResource(R.string.plan_part_missing)
                    val verdictColor = when (row.verdict) {
                        StepVerdict.OK -> MaterialTheme.colorScheme.primary
                        StepVerdict.NO_TARGET -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.error
                    }
                    Column {
                        Text("${i + 1}. ${stringResource(row.planned.kind.labelRes)}: $planned", style = MaterialTheme.typography.bodyMedium)
                        Text("→ $actual · ${stringResource(verdictRes(row.verdict))}", style = MaterialTheme.typography.bodySmall, color = verdictColor)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.weight(1f)) {
                    Text(s.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)), maxLines = 1)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.plan_at_time), modifier = Modifier.weight(1f))
                if (s.time != null) OutlinedButton(onClick = { pickTime = true }) { Text(s.time.toString()) }
                Switch(checked = s.time != null, onCheckedChange = { on -> viewModel.update { it.copy(time = if (on) LocalTime.of(18, 0) else null) }; if (on) pickTime = true })
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.kind == PlanKind.ACTIVITY, onClick = { viewModel.update { it.copy(kind = PlanKind.ACTIVITY) } }, label = { Text(stringResource(R.string.plan_kind_activity)) })
                FilterChip(selected = s.kind == PlanKind.WORKOUT, onClick = { viewModel.update { it.copy(kind = PlanKind.WORKOUT) } }, label = { Text(stringResource(R.string.plan_kind_workout)) })
            }
            if (s.kind == PlanKind.WORKOUT) {
                Box {
                    OutlinedButton(onClick = { workoutMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(workouts.firstOrNull { it.id == s.workoutId }?.name ?: stringResource(R.string.plan_pick_workout), modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = workoutMenu, onDismissRequest = { workoutMenu = false }) {
                        workouts.forEach { w ->
                            DropdownMenuItem(text = { Text(w.name) }, onClick = { viewModel.update { it.copy(workoutId = w.id) }; workoutMenu = false })
                        }
                    }
                }
                if ("workout" in s.errors) Text(stringResource(R.string.plan_pick_workout_error), color = MaterialTheme.colorScheme.error)
                s.workoutId?.let { id ->
                    OutlinedButton(onClick = { onOpenWorkout(id) }) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.plan_open_workout), modifier = Modifier.padding(start = 6.dp))
                    }
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActivityType.entries.forEach { type ->
                        FilterChip(
                            selected = s.activityType == type,
                            onClick = { viewModel.update { it.copy(activityType = type) } },
                            label = { Text(stringResource(type.labelRes)) },
                            leadingIcon = { Icon(type.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = s.title,
                onValueChange = { v -> viewModel.update { it.copy(title = v) } },
                label = { Text(stringResource(R.string.activity_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Field(s.durationMinutes, { v -> viewModel.update { it.copy(durationMinutes = v) } }, R.string.plan_minutes, stringResource(R.string.plan_minutes_auto), "duration" in s.errors, Modifier.fillMaxWidth())
            OutlinedTextField(
                value = s.notes,
                onValueChange = { v -> viewModel.update { it.copy(notes = v) } },
                label = { Text(stringResource(R.string.plan_notes)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            if (s.kind == PlanKind.ACTIVITY) {
                Text(stringResource(R.string.plan_steps), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.plan_steps_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if ("steps" in s.errors) Text(stringResource(R.string.plan_steps_error), color = MaterialTheme.colorScheme.error)
                s.steps.forEachIndexed { i, step ->
                    StepRow(i, step, onChange = { t -> viewModel.updateStep(step.key, t) }, onRemove = { viewModel.removeStep(step.key) })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SegmentKind.entries.forEach { kind -> OutlinedButton(onClick = { viewModel.addStep(kind) }) { Text("+ " + stringResource(kind.labelRes)) } }
                    OutlinedButton(onClick = { addRepeats = true }) {
                        Icon(Icons.Outlined.Repeat, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.activity_add_repeats), modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }

            if (!s.isNew) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.plan_skipped), modifier = Modifier.weight(1f))
                    Switch(checked = s.skipped, onCheckedChange = { v -> viewModel.update { it.copy(skipped = v) } })
                }
            }
            Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.plan_save)) }
        }
    }

    if (pickDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = s.date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { ms -> viewModel.update { it.copy(date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()) } }
                    pickDate = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = picker) }
    }
    if (pickTime) {
        val t = s.time ?: LocalTime.of(18, 0)
        val picker = rememberTimePickerState(initialHour = t.hour, initialMinute = t.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.update { it.copy(time = LocalTime.of(picker.hour, picker.minute)) }
                    pickTime = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.cancel)) } },
            text = { TimePicker(state = picker) },
        )
    }
    if (addRepeats) RepeatsDialog(onDismiss = { addRepeats = false }, onAdd = { n, work, rest ->
        viewModel.addRepeats(n, work, rest)
        addRepeats = false
    })
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.plan_delete_title)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun DoneCard(done: DoneItem, onOpenSession: (Long) -> Unit, onOpenActivity: (Long) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.plan_status_done), style = MaterialTheme.typography.labelLarge)
                when (done) {
                    is DoneItem.Workout -> Text(done.session.workoutName, style = MaterialTheme.typography.bodyLarge)
                    is DoneItem.Logged -> {
                        Text(done.activity.displayTitle(), style = MaterialTheme.typography.bodyLarge)
                        Text(done.activity.summary(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            TextButton(onClick = {
                when (done) {
                    is DoneItem.Workout -> onOpenSession(done.session.id)
                    is DoneItem.Logged -> onOpenActivity(done.activity.id)
                }
            }) { Text(stringResource(R.string.coach_proposal_open)) }
        }
    }
}

@Composable
private fun StepRow(index: Int, step: StepDraft, onChange: ((StepDraft) -> StepDraft) -> Unit, onRemove: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}. " + stringResource(step.kind.labelRes), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_delete)) }
            }
            Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(step.duration, { v -> onChange { it.copy(duration = v) } }, R.string.activity_duration, "4:30", false, Modifier.weight(1f), KeyboardType.Text)
                Field(step.distanceKm, { v -> onChange { it.copy(distanceKm = v) } }, R.string.activity_distance, "1", false, Modifier.weight(1f), KeyboardType.Decimal)
            }
            Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(step.pace, { v -> onChange { it.copy(pace = v) } }, R.string.plan_pace, "4:20-4:30", false, Modifier.weight(1f), KeyboardType.Text)
                Field(step.maxHeartRate, { v -> onChange { it.copy(maxHeartRate = v) } }, R.string.plan_hr_cap, "165", false, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RepeatsDialog(onDismiss: () -> Unit, onAdd: (Int, StepDraft, StepDraft?) -> Unit) {
    var reps by rememberSaveable { mutableStateOf("6") }
    var workKm by rememberSaveable { mutableStateOf("1") }
    var workTime by rememberSaveable { mutableStateOf("") }
    var workPace by rememberSaveable { mutableStateOf("") }
    var restTime by rememberSaveable { mutableStateOf("2:00") }
    val n = reps.toIntOrNull()?.takeIf { it in 1..50 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.activity_add_repeats)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(reps, { reps = it }, R.string.activity_repeats_count, "6", n == null, Modifier.fillMaxWidth())
                Text(stringResource(R.string.segment_work), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(workKm, { workKm = it }, R.string.activity_distance, "1", false, Modifier.weight(1f), KeyboardType.Decimal)
                    Field(workTime, { workTime = it }, R.string.activity_duration, "4:30", false, Modifier.weight(1f), KeyboardType.Text)
                }
                Field(workPace, { workPace = it }, R.string.plan_pace, "4:20-4:30", false, Modifier.fillMaxWidth(), KeyboardType.Text)
                Text(stringResource(R.string.segment_recovery), style = MaterialTheme.typography.titleSmall)
                Field(restTime, { restTime = it }, R.string.activity_duration, "2:00", false, Modifier.fillMaxWidth(), KeyboardType.Text)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    n?.let {
                        onAdd(
                            it,
                            StepDraft(0, SegmentKind.WORK, duration = workTime, distanceKm = workKm, pace = workPace),
                            restTime.takeIf { r -> r.isNotBlank() }?.let { r -> StepDraft(0, SegmentKind.RECOVERY, duration = r) },
                        )
                    }
                },
                enabled = n != null,
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    placeholder: String,
    isError: Boolean,
    modifier: Modifier,
    keyboard: KeyboardType = KeyboardType.Number,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label), maxLines = 1) },
        placeholder = { Text(placeholder) },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier,
    )
}

private fun verdictRes(v: StepVerdict) = when (v) {
    StepVerdict.OK -> R.string.plan_verdict_ok
    StepVerdict.FASTER -> R.string.plan_verdict_faster
    StepVerdict.SLOWER -> R.string.plan_verdict_slower
    StepVerdict.HR_HIGH -> R.string.plan_verdict_hr
    StepVerdict.NOT_DONE -> R.string.plan_verdict_missing
    StepVerdict.NO_TARGET -> R.string.plan_verdict_no_target
}
