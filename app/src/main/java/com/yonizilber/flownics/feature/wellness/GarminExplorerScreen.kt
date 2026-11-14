package com.yonizilber.flownics.feature.wellness

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.wellness.GarminEndpoint
import com.yonizilber.flownics.domain.wellness.WellnessParser
import java.time.LocalDate

/**
 * Asks Garmin one thing and shows the answer as it came: what Flownics understood from it, its
 * shape field by field, and the text itself. So it's plain what each number is based on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GarminExplorerScreen(onBack: () -> Unit, viewModel: GarminExplorerViewModel = hiltViewModel()) {
    val result by viewModel.result.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    var endpoint by rememberSaveable { mutableStateOf(GarminEndpoint.SLEEP) }
    var epochDay by rememberSaveable { mutableLongStateOf(LocalDate.now().minusDays(1).toEpochDay()) }
    var menu by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val day = LocalDate.ofEpochDay(epochDay)
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(viewModel::save) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.explorer_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier.padding(innerPadding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.explorer_intro), style = MaterialTheme.typography.bodyMedium)
            Box {
                OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(endpointName(endpoint))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    GarminEndpoint.entries.forEach { e ->
                        DropdownMenuItem(text = { Text(endpointName(e)) }, onClick = { endpoint = e; menu = false })
                    }
                }
            }
            Text(endpoint.path, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (endpoint.maxDays > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { epochDay-- }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = stringResource(R.string.explorer_day_before)) }
                    Text(
                        if (endpoint.maxDays > 1) stringResource(R.string.explorer_week_to, day.toString()) else day.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { if (epochDay < LocalDate.now().toEpochDay()) epochDay++ }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = stringResource(R.string.explorer_day_after)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { viewModel.fetch(endpoint, day) }, enabled = !busy) { Text(stringResource(R.string.explorer_fetch)) }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            result?.let { r ->
                Text(
                    stringResource(R.string.explorer_answer, endpointName(r.endpoint), r.bytes.size / 1024.0, r.millis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { save.launch("garmin-${r.endpoint.name.lowercase()}-${r.end}.json") }) { Text(stringResource(R.string.explorer_save)) }
                    if (saved) Text(stringResource(R.string.explorer_saved), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(stringResource(R.string.explorer_save_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (r.notJson) Text(stringResource(R.string.explorer_not_json), color = MaterialTheme.colorScheme.error)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(R.string.explorer_tab_read, R.string.explorer_tab_shape, R.string.explorer_tab_text).forEachIndexed { i, label ->
                        SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(stringResource(label)) }
                    }
                }
                when (tab) {
                    0 -> ReadingCard(r.reading)
                    1 -> OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp).horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.explorer_shape_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            r.outline.forEach { line ->
                                Text(
                                    "${line.path}  ${line.kind}" + (line.example?.let { "  = $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                    softWrap = false,
                                )
                            }
                        }
                    }
                    else -> OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            if (r.pretty.length >= GarminExplorerViewModel.MAX_SHOWN) {
                                Text(stringResource(R.string.explorer_cut), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            SelectionContainer(Modifier.horizontalScroll(rememberScrollState())) {
                                Text(r.pretty, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp), softWrap = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What Flownics took from the answer, day by day, and what it looked for but didn't find. */
@Composable
private fun ReadingCard(reading: WellnessParser.Reading?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (reading == null) {
                Text(stringResource(R.string.explorer_not_read), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Text(stringResource(R.string.explorer_found, reading.found.joinToString().ifEmpty { "—" }), style = MaterialTheme.typography.bodyMedium)
            if (reading.missing.isNotEmpty()) {
                Text(stringResource(R.string.explorer_missing, reading.missing.joinToString()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (reading.days.isEmpty()) Text(stringResource(R.string.explorer_no_days), style = MaterialTheme.typography.bodySmall)
            reading.days.forEach { d ->
                Text(d.date.toString(), style = MaterialTheme.typography.titleSmall)
                WellnessFacts(d)
            }
        }
    }
}

@Composable
fun endpointName(e: GarminEndpoint): String = stringResource(
    when (e) {
        GarminEndpoint.SLEEP -> R.string.endpoint_sleep
        GarminEndpoint.SLEEP_SUMMARIES -> R.string.endpoint_sleep_summaries
        GarminEndpoint.HRV -> R.string.endpoint_hrv
        GarminEndpoint.HRV_SUMMARIES -> R.string.endpoint_hrv_summaries
        GarminEndpoint.HEART_RATE -> R.string.endpoint_heart_rate
        GarminEndpoint.BODY_BATTERY -> R.string.endpoint_body_battery
        GarminEndpoint.STRESS -> R.string.endpoint_stress
        GarminEndpoint.DAILY_SUMMARIES -> R.string.endpoint_daily_summaries
        GarminEndpoint.SPO2 -> R.string.endpoint_spo2
        GarminEndpoint.VO2_MAX -> R.string.endpoint_vo2max
        GarminEndpoint.STEPS -> R.string.endpoint_steps
        GarminEndpoint.WELLNESS_ACTIVITY -> R.string.endpoint_wellness_activity
        GarminEndpoint.WEIGHT -> R.string.endpoint_weight
        GarminEndpoint.FITNESS_AGE -> R.string.endpoint_fitness_age
        GarminEndpoint.HEART_RATE_ZONES -> R.string.endpoint_hr_zones
        GarminEndpoint.PROFILE_SETTINGS -> R.string.endpoint_profile
        GarminEndpoint.ACTIVITIES -> R.string.endpoint_activities
    },
)
