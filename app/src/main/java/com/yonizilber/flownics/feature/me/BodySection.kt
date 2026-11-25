package com.yonizilber.flownics.feature.me

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.body.WeightEntry
import com.yonizilber.flownics.domain.body.WeightSource
import com.yonizilber.flownics.domain.me.Trend
import com.yonizilber.flownics.sensors.HistoryProblem
import com.yonizilber.flownics.sensors.ScaleEvent
import com.yonizilber.flownics.sensors.ScaleProblem
import com.yonizilber.flownics.sensors.ScaleResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private fun kg(v: Double) = "%.1f".format(Locale.ROOT, v)

/** Weight: its trend, a weigh-in on the Xiaomi scale or typed in, and the latest weigh-ins. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BodySection(s: MeState, weight: Trend, viewModel: MeViewModel) {
    val weighIn by viewModel.weighIn.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var denied by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) viewModel.startWeighIn() else denied = true
    }
    TrendCard(weight, s.from, s.to)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(
            onClick = {
                denied = false
                if (viewModel.scale.hasPermission()) viewModel.startWeighIn() else ask.launch(viewModel.scale.permissions())
            },
            label = { Text(stringResource(R.string.weight_scale)) },
            leadingIcon = { Icon(Icons.Outlined.MonitorWeight, null) },
        )
        AssistChip(onClick = { adding = true }, label = { Text(stringResource(R.string.weight_add)) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
    }
    if (denied) Text(stringResource(R.string.weight_no_permission), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    if (s.weighIns.isNotEmpty()) {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                (if (showAll) s.weighIns else s.weighIns.take(RECENT)).forEach { e -> WeighInRow(e, onDelete = { viewModel.delete(e) }) }
                if (s.weighIns.size > RECENT) {
                    TextButton(onClick = { showAll = !showAll }) {
                        Text(stringResource(if (showAll) R.string.weight_show_fewer else R.string.weight_show_all, s.weighIns.size))
                    }
                }
            }
        }
    }
    Text(stringResource(R.string.me_body_circumferences_later), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    weighIn?.let { WeighInDialog(it, onRetry = viewModel::startWeighIn, onClose = viewModel::closeWeighIn) }
    if (adding) ManualWeightDialog(onAdd = viewModel::addManual, onClose = { adding = false })
}

@Composable
private fun WeighInRow(e: WeightEntry, onDelete: () -> Unit) {
    val at = Instant.ofEpochMilli(e.atMillis).atZone(ZoneId.systemDefault())
    var confirm by rememberSaveable(e.id) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.weight_kg, kg(e.kg)), style = MaterialTheme.typography.bodyLarge)
            Text(
                at.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) + " " + at.format(DateTimeFormatter.ofPattern("HH:mm")) + " · " +
                    stringResource(if (e.source == WeightSource.SCALE) R.string.weight_from_scale else R.string.weight_typed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { confirm = true }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.weight_delete)) }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.weight_delete_title)) },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text(stringResource(R.string.weight_delete)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun ManualWeightDialog(onAdd: (Double, Long) -> Boolean, onClose: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var bad by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.weight_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() || it == '.' || it == ',' }.take(6); bad = false },
                    label = { Text(stringResource(R.string.weight_kg_label)) },
                    singleLine = true,
                    isError = bad,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text(stringResource(if (bad) R.string.weight_out_of_range else R.string.weight_add_now), style = MaterialTheme.typography.bodySmall, color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = text.replace(',', '.').toDoubleOrNull()
                if (v != null && onAdd(v, System.currentTimeMillis())) onClose() else bad = true
            }) { Text(stringResource(R.string.weight_save)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** The weigh-in as it goes: step on, the live number, the stored weigh-ins, and what was kept. */
@Composable
private fun WeighInDialog(event: ScaleEvent, onRetry: () -> Unit, onClose: () -> Unit) {
    val finished = event is ScaleEvent.Done || event is ScaleEvent.Failed
    AlertDialog(
        onDismissRequest = { if (finished) onClose() },
        title = { Text(stringResource(R.string.weight_scale)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (event) {
                    ScaleEvent.Searching -> {
                        Text(stringResource(R.string.weight_step_on))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is ScaleEvent.Live -> {
                        Text(stringResource(R.string.weight_kg, kg(event.kg)), style = MaterialTheme.typography.displaySmall)
                        Text(stringResource(if (event.settled) R.string.weight_settled else R.string.weight_settling))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    ScaleEvent.ReadingHistory -> {
                        Text(stringResource(R.string.weight_reading_history))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is ScaleEvent.Done -> ResultText(event.result)
                    is ScaleEvent.Failed -> Text(
                        stringResource(
                            when (event.problem) {
                                ScaleProblem.BLUETOOTH_OFF -> R.string.weight_bt_off
                                ScaleProblem.NO_PERMISSION -> R.string.weight_no_permission
                                ScaleProblem.NOT_FOUND -> R.string.weight_not_found
                                ScaleProblem.NOT_SETTLED -> R.string.weight_not_settled
                                ScaleProblem.SCAN_FAILED -> R.string.weight_scan_failed
                            },
                        ),
                    )
                }
            }
        },
        confirmButton = {
            if (finished) TextButton(onClick = onClose) { Text(stringResource(R.string.weight_close)) }
            else TextButton(onClick = onClose) { Text(stringResource(android.R.string.cancel)) }
        },
        dismissButton = {
            if (event is ScaleEvent.Failed) TextButton(onClick = onRetry) { Text(stringResource(R.string.weight_try_again)) }
        },
    )
}

@Composable
private fun ResultText(r: ScaleResult) {
    r.live?.let { Text(stringResource(R.string.weight_now, kg(it.kg)), style = MaterialTheme.typography.titleLarge) }
    if (r.clockWasOff) Text(stringResource(R.string.weight_clock_off), style = MaterialTheme.typography.bodySmall)
    when (r.historyProblem) {
        null -> {
            val dates = r.fromHistory.map { Instant.ofEpochMilli(it.atMillis).atZone(ZoneId.systemDefault()).toLocalDate() }
            val f = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
            Text(
                if (dates.isEmpty()) stringResource(R.string.weight_history_none)
                else stringResource(R.string.weight_history_some, dates.size, dates.min().format(f), dates.max().format(f)),
            )
        }
        HistoryProblem.CONNECT_FAILED -> Text(stringResource(R.string.weight_history_connect_failed))
        HistoryProblem.NO_HISTORY_ON_SCALE -> Text(stringResource(R.string.weight_history_unsupported))
        HistoryProblem.WRITE_REFUSED -> Text(stringResource(R.string.weight_history_refused))
        HistoryProblem.NO_END -> Text(stringResource(R.string.weight_history_no_end, r.fromHistory.size))
    }
    Text(stringResource(R.string.weight_saved_new, r.saved), style = MaterialTheme.typography.bodySmall)
    r.announced?.let { Text(stringResource(R.string.weight_announced, it), style = MaterialTheme.typography.bodySmall) }
    if (r.notSettled > 0) Text(stringResource(R.string.weight_skipped_unsettled, r.notSettled), style = MaterialTheme.typography.bodySmall)
    if (r.badDates > 0) Text(stringResource(R.string.weight_skipped_dates, r.badDates), style = MaterialTheme.typography.bodySmall)
    if (r.historyProblem == null && !r.confirmed) Text(stringResource(R.string.weight_not_confirmed), style = MaterialTheme.typography.bodySmall)
    if (r.notUnderstood.isNotEmpty()) {
        Text(stringResource(R.string.weight_not_understood, r.notUnderstood.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        SelectionContainer { Text(r.notUnderstood.take(10).joinToString("\n"), style = MaterialTheme.typography.labelSmall) }
    }
}

private const val RECENT = 5
