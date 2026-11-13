package com.yonizilber.flownics.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.data.local.prefs.AppPreferences
import com.yonizilber.flownics.data.local.prefs.WorkoutDefaults
import com.yonizilber.flownics.ui.component.formatClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class WorkoutDefaultsViewModel @Inject constructor(private val prefs: AppPreferences) : ViewModel() {
    val defaults: StateFlow<WorkoutDefaults> = prefs.observeWorkoutDefaults()
    fun set(d: WorkoutDefaults) = prefs.setWorkoutDefaults(d)
}

/** What new exercises and blocks start with in the workout builder, set once here. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorkoutDefaultsScreen(onBack: () -> Unit, viewModel: WorkoutDefaultsViewModel = hiltViewModel()) {
    val d by viewModel.defaults.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.defaults_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier.padding(innerPadding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.defaults_intro), style = MaterialTheme.typography.bodyMedium)
            Choice(stringResource(R.string.defaults_sets), (1..6).toList(), d.sets, { "$it" }) { viewModel.set(d.copy(sets = it)) }
            Choice(stringResource(R.string.defaults_rest_sets), listOf(30, 45, 60, 90, 120, 180), d.restBetweenSets, ::formatClock) { viewModel.set(d.copy(restBetweenSets = it)) }
            Choice(stringResource(R.string.defaults_rest_in_round), listOf(0, 10, 15, 20, 30, 45), d.restInRound, ::formatClock) { viewModel.set(d.copy(restInRound = it)) }
            Choice(stringResource(R.string.defaults_rest_rounds), listOf(60, 90, 120, 150, 180, 240), d.restBetweenRounds, ::formatClock) { viewModel.set(d.copy(restBetweenRounds = it)) }
            Toggle(stringResource(R.string.wedit_confirm_active_rest), stringResource(R.string.wedit_confirm_active_rest_hint), d.confirmActiveRest) { viewModel.set(d.copy(confirmActiveRest = it)) }
            Toggle(stringResource(R.string.wedit_confirm_end), stringResource(R.string.defaults_confirm_timed_hint), d.confirmTimed) { viewModel.set(d.copy(confirmTimed = it)) }
            Text(stringResource(R.string.defaults_existing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choice(title: String, options: List<Int>, selected: Int, label: (Int) -> String, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (options + selected).distinct().sorted().forEach { v -> FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(label(v)) }) }
        }
    }
}

@Composable
private fun Toggle(title: String, hint: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}
