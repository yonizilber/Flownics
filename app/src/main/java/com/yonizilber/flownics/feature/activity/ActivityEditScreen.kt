package com.yonizilber.flownics.feature.activity

import com.yonizilber.flownics.ui.component.ZonesBar
import com.yonizilber.flownics.domain.model.ActivityMetrics
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.calendar.CalendarEvents
import com.yonizilber.flownics.domain.model.ActivityFormat
import com.yonizilber.flownics.domain.model.ActivityType
import com.yonizilber.flownics.domain.model.SegmentKind
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Log a run, ride or anything done outside the player, by hand. Only the length is required.
 * The parts (warm-up, each repeat, recovery) can be added so an interval session keeps its shape.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ActivityEditScreen(onClose: () -> Unit, onSaved: () -> Unit, viewModel: ActivityEditViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val structure by viewModel.structure.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    var addRepeats by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(s.savedId, s.deleted) {
        if (s.savedId != null) onSaved()
        if (s.deleted) onClose()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (s.isNew) R.string.activity_new else R.string.activity_edit)) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
                actions = {
                    viewModel.saved?.let { a ->
                        val title = a.title.ifBlank { stringResource(a.type.labelRes) }
                        IconButton(onClick = { CalendarEvents.done(context, title, a.notes, a.startedAtMillis, a.endedAtMillis) }) {
                            Icon(Icons.Outlined.Event, contentDescription = stringResource(R.string.calendar_add))
                        }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete)) }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (s.loading) return@Scaffold
        Column(
            Modifier.padding(innerPadding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val zonesInfo by viewModel.zonesInfo.collectAsStateWithLifecycle()
            viewModel.saved?.metrics?.let { m ->
                RecordingCard(m)
                structure?.let { (s, rows) -> RunStructureCard(s, rows) }
                zonesInfo?.let { z -> ZonesBar(m.hrHistogram, z.zones, z.maxFromRecordings) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActivityType.entries.forEach { type ->
                    FilterChip(
                        selected = s.type == type,
                        onClick = { viewModel.update { it.copy(type = type) } },
                        label = { Text(stringResource(type.labelRes)) },
                        leadingIcon = { Icon(type.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
            OutlinedTextField(
                value = s.title,
                onValueChange = { v -> viewModel.update { it.copy(title = v) } },
                label = { Text(stringResource(R.string.activity_title)) },
                placeholder = { Text(stringResource(s.type.labelRes)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.weight(1f)) {
                    Text(s.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
                }
                OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.weight(1f)) {
                    Text(s.time.format(DateTimeFormatter.ofPattern("HH:mm")))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    s.duration, { v -> viewModel.update { it.copy(duration = v) } }, R.string.activity_duration, "45:30",
                    ActivityField.DURATION in s.errors, Modifier.weight(1f), KeyboardType.Text,
                    error = R.string.activity_duration_error,
                )
                NumberField(
                    s.distanceKm, { v -> viewModel.update { it.copy(distanceKm = v) } }, R.string.activity_distance, "8.2",
                    ActivityField.DISTANCE in s.errors, Modifier.weight(1f), KeyboardType.Decimal,
                )
            }
            val pace = paceOf(s.duration, s.distanceKm)
            if (pace != null && s.type != ActivityType.SWIM && s.type != ActivityType.RIDE) {
                Text(stringResource(R.string.activity_pace_is, pace), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(s.avgHeartRate, { v -> viewModel.update { it.copy(avgHeartRate = v) } }, R.string.activity_avg_hr, "152", ActivityField.AVG_HR in s.errors, Modifier.weight(1f))
                NumberField(s.maxHeartRate, { v -> viewModel.update { it.copy(maxHeartRate = v) } }, R.string.activity_max_hr, "175", ActivityField.MAX_HR in s.errors, Modifier.weight(1f))
                NumberField(s.elevation, { v -> viewModel.update { it.copy(elevation = v) } }, R.string.activity_elevation, "80", ActivityField.ELEVATION in s.errors, Modifier.weight(1f))
            }
            Text(stringResource(R.string.activity_rpe), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..10).forEach { n ->
                    FilterChip(
                        selected = s.rpe == n,
                        onClick = { viewModel.update { it.copy(rpe = if (it.rpe == n) null else n) } },
                        label = { Text("$n") },
                    )
                }
            }
            Text(stringResource(R.string.activity_rpe_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text(stringResource(R.string.activity_segments), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.activity_segments_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ActivityField.SEGMENTS in s.errors) Text(stringResource(R.string.activity_segments_error), color = MaterialTheme.colorScheme.error)
            s.segments.forEachIndexed { index, seg ->
                SegmentRow(index, seg, onChange = { t -> viewModel.updateSegment(seg.key, t) }, onRemove = { viewModel.removeSegment(seg.key) })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(SegmentKind.WARMUP, SegmentKind.WORK, SegmentKind.RECOVERY, SegmentKind.COOLDOWN, SegmentKind.STEADY).forEach { kind ->
                    OutlinedButton(onClick = { viewModel.addSegment(kind) }) { Text("+ " + stringResource(kind.labelRes)) }
                }
                OutlinedButton(onClick = { addRepeats = true }) {
                    Icon(Icons.Outlined.Repeat, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.activity_add_repeats), modifier = Modifier.padding(start = 6.dp))
                }
            }

            OutlinedTextField(
                value = s.notes,
                onValueChange = { v -> viewModel.update { it.copy(notes = v) } },
                label = { Text(stringResource(R.string.activity_notes)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.activity_save)) }
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
        val picker = rememberTimePickerState(initialHour = s.time.hour, initialMinute = s.time.minute, is24Hour = true)
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
    if (addRepeats) RepeatsDialog(onDismiss = { addRepeats = false }, onAdd = { n, wd, wk, rd, rk ->
        viewModel.addRepeats(n, wd, wk, rd, rk)
        addRepeats = false
    })
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.activity_delete_title)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** What the watch recorded beyond the totals: cadence, step length, form, drift, and each kilometre. */
@Composable
private fun RecordingCard(m: ActivityMetrics) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.fit_recorded_title), style = MaterialTheme.typography.titleSmall)
            val lines = listOfNotNull(
                m.cadence?.let { stringResource(if (m.cadenceIsSteps) R.string.fit_cadence else R.string.fit_cadence_rpm, it) },
                m.stepLengthCm?.let { stringResource(if (m.stepLengthMeasured) R.string.fit_step_length else R.string.fit_step_length_derived, it) },
                m.verticalOscillationMm?.let { stringResource(R.string.fit_vertical_oscillation, it / 10.0) },
                m.groundContactMs?.let { stringResource(R.string.fit_ground_contact, it) },
                m.avgPowerW?.let { stringResource(R.string.fit_power, it) },
                m.decouplingPercent?.let { stringResource(R.string.fit_decoupling, it) },
            )
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (m.decouplingPercent != null) Text(stringResource(R.string.fit_decoupling_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (m.splits.isNotEmpty() && m.cadenceIsSteps) {
                Text(stringResource(R.string.fit_splits), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                m.splits.forEach { s ->
                    Text(
                        listOfNotNull(
                            stringResource(R.string.fit_split_km, s.km),
                            ActivityFormat.formatDuration(s.seconds),
                            s.avgHeartRate?.let { stringResource(R.string.activity_hr, it) },
                            s.cadence?.let { stringResource(R.string.fit_cadence_short, it) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun SegmentRow(index: Int, seg: SegmentDraft, onChange: ((SegmentDraft) -> SegmentDraft) -> Unit, onRemove: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}. " + stringResource(seg.kind.labelRes), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                paceOf(seg.duration, seg.distanceKm)?.let { Text(stringResource(R.string.activity_pace, it), style = MaterialTheme.typography.bodySmall) }
                listOfNotNull(
                    seg.cadenceSpm?.let { stringResource(R.string.fit_cadence_short, it) },
                    seg.stepLengthCm?.let { stringResource(R.string.fit_step_short, it) },
                ).takeIf { it.isNotEmpty() }?.let { Text(" · " + it.joinToString(" · "), style = MaterialTheme.typography.bodySmall) }
                IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_delete)) }
            }
            Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(seg.duration, { v -> onChange { it.copy(duration = v) } }, R.string.activity_duration, "4:30", false, Modifier.weight(1f), KeyboardType.Text)
                NumberField(seg.distanceKm, { v -> onChange { it.copy(distanceKm = v) } }, R.string.activity_distance, "1", false, Modifier.weight(1f), KeyboardType.Decimal)
                NumberField(seg.avgHeartRate, { v -> onChange { it.copy(avgHeartRate = v) } }, R.string.activity_avg_hr, "165", false, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RepeatsDialog(onDismiss: () -> Unit, onAdd: (Int, String, String, String, String) -> Unit) {
    var reps by rememberSaveable { mutableStateOf("6") }
    var workTime by rememberSaveable { mutableStateOf("") }
    var workKm by rememberSaveable { mutableStateOf("1") }
    var restTime by rememberSaveable { mutableStateOf("2:00") }
    var restKm by rememberSaveable { mutableStateOf("") }
    val n = reps.toIntOrNull()?.takeIf { it in 1..50 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.activity_add_repeats)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(reps, { reps = it }, R.string.activity_repeats_count, "6", n == null, Modifier.fillMaxWidth())
                Text(stringResource(R.string.segment_work), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(workTime, { workTime = it }, R.string.activity_duration, "4:30", false, Modifier.weight(1f), KeyboardType.Text)
                    NumberField(workKm, { workKm = it }, R.string.activity_distance, "1", false, Modifier.weight(1f), KeyboardType.Decimal)
                }
                Text(stringResource(R.string.segment_recovery), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(restTime, { restTime = it }, R.string.activity_duration, "2:00", false, Modifier.weight(1f), KeyboardType.Text)
                    NumberField(restKm, { restKm = it }, R.string.activity_distance, "0.4", false, Modifier.weight(1f), KeyboardType.Decimal)
                }
                Text(stringResource(R.string.activity_repeats_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { n?.let { onAdd(it, workTime, workKm, restTime, restKm) } }, enabled = n != null) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    placeholder: String,
    isError: Boolean,
    modifier: Modifier,
    keyboard: KeyboardType = KeyboardType.Number,
    error: Int? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label), maxLines = 1) },
        placeholder = { Text(placeholder) },
        isError = isError,
        supportingText = if (isError && error != null) ({ Text(stringResource(error)) }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier,
    )
}

private fun paceOf(duration: String, km: String): String? {
    val seconds = ActivityFormat.parseDuration(duration) ?: return null
    val meters = ActivityFormat.parseKm(km) ?: return null
    return ActivityFormat.formatPace((seconds * 1000L / meters).toInt())
}

