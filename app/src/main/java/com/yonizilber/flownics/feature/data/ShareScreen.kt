package com.yonizilber.flownics.feature.data

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.data.transfer.AutoBackup
import java.util.Locale

/**
 * Choose workouts and exercises to give a friend: as a file through the share sheet (WhatsApp,
 * email…) or saved somewhere. History, notes on sessions and the AI log never go in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(onBack: () -> Unit, viewModel: ShareViewModel = hiltViewModel()) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val withWorkouts by viewModel.withWorkouts.collectAsStateWithLifecycle()
    val size by viewModel.size.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val res = LocalResources.current

    val lib = library
    val title = when {
        lib == null -> ""
        selection.workoutIds.size == 1 && selection.exerciseIds.isEmpty() ->
            lib.workouts.firstOrNull { it.id in selection.workoutIds }?.name.orEmpty()
        selection.workoutIds.isEmpty() && selection.exerciseIds.size == 1 ->
            lib.exercises.firstOrNull { it.id in selection.exerciseIds }?.name.orEmpty()
        else -> stringResource(R.string.share_file_title)
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(AutoBackup.MIME_ZIP)) { uri ->
        uri?.let(viewModel::saveTo)
    }

    // A prepared file goes straight to the share sheet; the app the user picks may only read it.
    LaunchedEffect(outcome) {
        val ready = (outcome as? ShareOutcome.Ready)?.takeIf { !it.launched } ?: return@LaunchedEffect
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.media", ready.file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = AutoBackup.MIME_ZIP
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(ready.file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(send, res.getString(R.string.share_sheet_title))) }
            .onSuccess { viewModel.markLaunched() }
            .onFailure { viewModel.clearOutcome() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.share_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        bottomBar = {
            val empty = selection.workoutIds.isEmpty() && selection.exerciseIds.isEmpty()
            Surface(tonalElevation = 3.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    size?.let { (exercises, bytes) ->
                        if (!empty) {
                            Text(
                                pluralStringResource(R.plurals.data_count_exercises, exercises, exercises) +
                                    " · " + pluralStringResource(R.plurals.data_count_workouts, selection.workoutIds.size, selection.workoutIds.size) +
                                    if (selection.includeMedia && bytes > 0) " · " + megabytes(bytes) else "",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.prepareShare(title) }, enabled = !empty && !busy, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.share_send))
                        }
                        OutlinedButton(
                            onClick = { saveFile.launch("Flownics - $title.zip") },
                            enabled = !empty && !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.share_save)) }
                    }
                }
            }
        },
    ) { innerPadding ->
        if (lib == null) {
            Box(Modifier.padding(innerPadding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(innerPadding).fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.share_intro), style = MaterialTheme.typography.bodyMedium)
                    if (busy) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(4.dp))
                        Text(stringResource(R.string.data_busy_saving))
                    }
                    outcome?.let { o -> if (o !is ShareOutcome.Ready || o.result.missingMedia > 0) OutcomeCard(o, viewModel::clearOutcome) }
                }
            }
            item {
                CheckRow(
                    text = stringResource(R.string.share_include_media),
                    hint = stringResource(R.string.share_include_media_hint),
                    checked = selection.includeMedia,
                    enabled = true,
                    onToggle = { viewModel.setIncludeMedia(!selection.includeMedia) },
                    switch = true,
                )
                HorizontalDivider()
            }
            if (lib.workouts.isNotEmpty()) {
                item { Heading(stringResource(R.string.share_workouts)) }
                items(lib.workouts, key = { "w${it.id}" }) { workout ->
                    CheckRow(workout.name, null, workout.id in selection.workoutIds, enabled = true, onToggle = { viewModel.toggleWorkout(workout.id) })
                }
            }
            if (lib.exercises.isNotEmpty()) {
                item { Heading(stringResource(R.string.share_exercises)) }
                items(lib.exercises, key = { "e${it.id}" }) { exercise ->
                    val brought = exercise.id in withWorkouts
                    CheckRow(
                        text = exercise.name,
                        hint = if (brought) stringResource(R.string.share_with_workout) else null,
                        checked = brought || exercise.id in selection.exerciseIds,
                        enabled = !brought,
                        onToggle = { viewModel.toggleExercise(exercise.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OutcomeCard(outcome: ShareOutcome, onDismiss: () -> Unit) {
    val failed = outcome is ShareOutcome.Failed
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (outcome) {
                    is ShareOutcome.Failed -> transferErrorText(LocalResources.current, outcome.error)
                    is ShareOutcome.Saved -> stringResource(R.string.data_saved) + "\n" + exportSummary(outcome.result)
                    is ShareOutcome.Ready -> exportSummary(outcome.result)
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun CheckRow(text: String, hint: String?, checked: Boolean, enabled: Boolean, onToggle: () -> Unit, switch: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = if (switch) Role.Switch else Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!switch) Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (switch) Switch(checked = checked, onCheckedChange = null)
    }
}

private fun megabytes(bytes: Long) = String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
