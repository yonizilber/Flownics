package com.yonizilber.flownics.feature.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yonizilber.flownics.R
import com.yonizilber.flownics.data.coach.CoachStore
import com.yonizilber.flownics.domain.coach.CoachNote
import com.yonizilber.flownics.domain.coach.CoachProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CoachMemoryViewModel @Inject constructor(private val store: CoachStore) : ViewModel() {
    val profile: StateFlow<CoachProfile> = store.profile
    val notes: StateFlow<List<CoachNote>> = store.observeNotes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveProfile(profile: CoachProfile) = store.saveProfile(profile)

    fun updateNote(id: Long, text: String) = viewModelScope.launch { if (text.isNotBlank()) store.updateNote(id, text) }

    fun deleteNote(id: Long) = viewModelScope.launch { store.deleteNote(id) }
}

/** What the coach knows about the user: the profile they write, and the notes the coach keeps. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachMemoryScreen(onBack: () -> Unit, viewModel: CoachMemoryViewModel = hiltViewModel()) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    var goals by rememberSaveable(profile) { mutableStateOf(profile.goals) }
    var limitations by rememberSaveable(profile) { mutableStateOf(profile.limitations) }
    var equipment by rememberSaveable(profile) { mutableStateOf(profile.equipment) }
    var schedule by rememberSaveable(profile) { mutableStateOf(profile.schedule) }
    var maxHr by rememberSaveable(profile) { mutableStateOf(profile.maxHeartRate?.toString().orEmpty()) }
    var restHr by rememberSaveable(profile) { mutableStateOf(profile.restingHeartRate?.toString().orEmpty()) }
    val edited = CoachProfile(
        goals, limitations, equipment, schedule,
        maxHeartRate = maxHr.trim().toIntOrNull()?.takeIf { it in 120..230 },
        restingHeartRate = restHr.trim().toIntOrNull()?.takeIf { it in 30..110 },
    )
    var editing by remember { mutableStateOf<CoachNote?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.coach_memory)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.coach_profile_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.coach_profile_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { ProfileField(R.string.coach_profile_goals, R.string.coach_profile_goals_hint, goals) { goals = it } }
            item { ProfileField(R.string.coach_profile_limitations, R.string.coach_profile_limitations_hint, limitations) { limitations = it } }
            item { ProfileField(R.string.coach_profile_equipment, R.string.coach_profile_equipment_hint, equipment) { equipment = it } }
            item { ProfileField(R.string.coach_profile_schedule, R.string.coach_profile_schedule_hint, schedule) { schedule = it } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = maxHr,
                        onValueChange = { maxHr = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.coach_profile_max_hr)) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = restHr,
                        onValueChange = { restHr = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.coach_profile_rest_hr)) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(stringResource(R.string.coach_profile_hr_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Button(onClick = { viewModel.saveProfile(edited) }, enabled = edited != profile) { Text(stringResource(R.string.coach_profile_save)) }
            }
            item {
                Text(stringResource(R.string.coach_notes_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                Text(stringResource(R.string.coach_notes_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (notes.isEmpty()) item { Text(stringResource(R.string.coach_notes_none), style = MaterialTheme.typography.bodyMedium) }
            items(notes, key = { it.id }) { note ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(note.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                        IconButton(onClick = { editing = note }) { Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.coach_note_edit)) }
                        IconButton(onClick = { viewModel.deleteNote(note.id) }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.coach_note_delete)) }
                    }
                }
            }
        }
    }

    editing?.let { note ->
        var text by rememberSaveable(note.id) { mutableStateOf(note.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.coach_note_edit)) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateNote(note.id, text)
                    editing = null
                }, enabled = text.isNotBlank()) { Text(stringResource(R.string.coach_profile_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ProfileField(label: Int, hint: Int, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(stringResource(label)) },
            placeholder = { Text(stringResource(hint)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
