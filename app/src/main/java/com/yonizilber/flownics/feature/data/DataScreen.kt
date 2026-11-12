package com.yonizilber.flownics.feature.data

import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.data.transfer.AutoBackup
import com.yonizilber.flownics.data.transfer.AutoBackupState
import com.yonizilber.flownics.data.transfer.BackupProblem
import com.yonizilber.flownics.data.transfer.BundleException
import com.yonizilber.flownics.data.transfer.BundleKind
import com.yonizilber.flownics.data.transfer.ExportResult
import com.yonizilber.flownics.data.transfer.ImportPreview
import com.yonizilber.flownics.data.transfer.WorkoutRunningException
import com.yonizilber.flownics.ui.component.rememberDateTexts
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Files from chat apps and file managers don't always say they are zips.
private val OPENABLE = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataScreen(
    onBack: () -> Unit,
    onOpenShare: () -> Unit,
    viewModel: DataViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val auto by viewModel.auto.collectAsStateWithLifecycle()

    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(AutoBackup.MIME_ZIP)) { uri ->
        uri?.let(viewModel::saveBackup)
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::open) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(viewModel::chooseFolder) }
    val backupName = { "flownics-backup-${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}.zip" }
    val idle = state.busy == null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.data_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.busy?.let { BusyCard(it) }
            state.message?.let { MessageCard(it, onDismiss = viewModel::dismissMessage) }

            Section(stringResource(R.string.data_backup_title), stringResource(R.string.data_backup_body)) {
                Button(onClick = { saveBackup.launch(backupName()) }, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.data_backup_save))
                }
                OutlinedButton(onClick = { openFile.launch(OPENABLE) }, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.data_backup_restore))
                }
            }

            AutoBackupSection(
                auto = auto,
                onChooseFolder = { pickFolder.launch(null) },
                onEnabled = viewModel::setAutoEnabled,
                onBackUpNow = viewModel::backUpNow,
            )

            Section(stringResource(R.string.data_share_title), stringResource(R.string.data_share_body)) {
                Button(onClick = onOpenShare, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.data_share_choose))
                }
                OutlinedButton(onClick = { openFile.launch(OPENABLE) }, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.data_share_import))
                }
            }
        }
    }

    state.preview?.let { preview ->
        if (preview.kind == BundleKind.SHARE) {
            SharePreviewDialog(preview, onImport = viewModel::importShare, onCancel = viewModel::cancelPreview)
        } else {
            RestorePreviewDialog(
                preview,
                onSaveFirst = { saveBackup.launch(backupName()) },
                onRestore = viewModel::restore,
                onCancel = viewModel::cancelPreview,
            )
        }
    }
}

@Composable
private fun Section(title: String, body: String, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun AutoBackupSection(auto: AutoBackupState, onChooseFolder: () -> Unit, onEnabled: (Boolean) -> Unit, onBackUpNow: () -> Unit) {
    val dates = rememberDateTexts()
    Section(stringResource(R.string.data_auto_title), stringResource(R.string.data_auto_body, AutoBackup.KEEP)) {
        if (auto.folderUri == null) {
            Button(onClick = onChooseFolder, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.data_auto_choose_folder)) }
            return@Section
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.data_auto_enabled), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = auto.enabled, onCheckedChange = onEnabled)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.data_auto_folder, auto.folderName ?: stringResource(R.string.data_auto_folder_unknown)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onChooseFolder) { Text(stringResource(R.string.data_auto_change_folder)) }
        }
        when {
            auto.running -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.data_auto_running), style = MaterialTheme.typography.bodyMedium)
            }
            auto.problem != null -> Text(
                stringResource(
                    if (auto.problem == BackupProblem.NO_FOLDER_ACCESS) R.string.data_auto_problem_access else R.string.data_auto_problem_write,
                    auto.problemAtMillis?.let { dates.fullDate(it) + " " + dates.time(it) }.orEmpty(),
                ) + if (auto.problemDetail.isNotBlank()) "\n" + auto.problemDetail else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            auto.lastSuccessMillis != null -> Text(
                stringResource(R.string.data_auto_last, dates.fullDate(auto.lastSuccessMillis) + " " + dates.time(auto.lastSuccessMillis)),
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> Text(stringResource(R.string.data_auto_never), style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(onClick = onBackUpNow, enabled = !auto.running, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.data_auto_now))
        }
    }
}

@Composable
private fun BusyCard(busy: DataBusy) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text(
                stringResource(
                    when (busy) {
                        DataBusy.SAVING -> R.string.data_busy_saving
                        DataBusy.READING -> R.string.data_busy_reading
                        DataBusy.IMPORTING -> R.string.data_busy_importing
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun MessageCard(message: DataMessage, onDismiss: () -> Unit) {
    val failed = message is DataMessage.Failed
    val res = LocalResources.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(if (failed) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (message) {
                    is DataMessage.Saved -> {
                        Text(stringResource(R.string.data_saved), style = MaterialTheme.typography.bodyLarge)
                        Text(exportSummary(message.result), style = MaterialTheme.typography.bodySmall)
                    }
                    is DataMessage.Imported -> {
                        Text(
                            pluralStringResource(R.plurals.data_imported_exercises, message.result.exercisesAdded, message.result.exercisesAdded) +
                                " " + pluralStringResource(R.plurals.data_imported_workouts, message.result.workoutsAdded, message.result.workoutsAdded),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (message.result.exercisesReused > 0) {
                            Text(
                                pluralStringResource(R.plurals.data_imported_reused, message.result.exercisesReused, message.result.exercisesReused),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    is DataMessage.Restored -> {
                        Text(stringResource(R.string.data_restored), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.data_restored_keys), style = MaterialTheme.typography.bodySmall)
                    }
                    is DataMessage.Failed -> Text(transferErrorText(res, message.error), style = MaterialTheme.typography.bodyLarge)
                }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    }
}

@Composable
internal fun exportSummary(result: ExportResult): String {
    val parts = mutableListOf(
        pluralStringResource(R.plurals.data_count_exercises, result.exercises, result.exercises),
        pluralStringResource(R.plurals.data_count_workouts, result.workouts, result.workouts),
    )
    if (result.sessions > 0) parts += pluralStringResource(R.plurals.data_count_sessions, result.sessions, result.sessions)
    if (result.mediaFiles > 0) parts += pluralStringResource(R.plurals.data_count_media, result.mediaFiles, result.mediaFiles)
    var text = parts.joinToString(" · ")
    // Fail visibly: a photo the app lost is named, not silently skipped.
    if (result.missingMedia > 0) text += "\n" + pluralStringResource(R.plurals.data_missing_media, result.missingMedia, result.missingMedia)
    return text
}

@Composable
private fun previewCounts(preview: ImportPreview): String {
    val parts = mutableListOf(
        pluralStringResource(R.plurals.data_count_exercises, preview.exercises, preview.exercises),
        pluralStringResource(R.plurals.data_count_workouts, preview.workouts, preview.workouts),
    )
    if (preview.sessions > 0) parts += pluralStringResource(R.plurals.data_count_sessions, preview.sessions, preview.sessions)
    if (preview.mediaFiles > 0) parts += pluralStringResource(R.plurals.data_count_media, preview.mediaFiles, preview.mediaFiles)
    return parts.joinToString(" · ")
}

@Composable
private fun SharePreviewDialog(preview: ImportPreview, onImport: (Boolean) -> Unit, onCancel: () -> Unit) {
    var reuse by rememberSaveable { mutableStateOf(true) }
    val dates = rememberDateTexts()
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.data_preview_share_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.data_preview_made, dates.fullDate(preview.createdAtMillis)), style = MaterialTheme.typography.bodySmall)
                Text(previewCounts(preview), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.data_preview_share_adds), style = MaterialTheme.typography.bodyMedium)
                if (preview.clashes.isNotEmpty()) {
                    Text(
                        pluralStringResource(R.plurals.data_preview_clashes, preview.clashes.size, preview.clashes.size) +
                            "\n" + preview.clashes.take(MAX_LISTED).joinToString(", ") + if (preview.clashes.size > MAX_LISTED) "…" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    listOf(true to R.string.data_preview_use_mine, false to R.string.data_preview_add_copies).forEach { (value, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = reuse == value, onClick = { reuse = value }, role = Role.RadioButton),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = reuse == value, onClick = null)
                            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onImport(reuse) }) { Text(stringResource(R.string.data_preview_import)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RestorePreviewDialog(preview: ImportPreview, onSaveFirst: () -> Unit, onRestore: () -> Unit, onCancel: () -> Unit) {
    val dates = rememberDateTexts()
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.data_preview_restore_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.data_preview_made, dates.fullDate(preview.createdAtMillis) + " " + dates.time(preview.createdAtMillis)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(previewCounts(preview), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(
                        R.string.data_preview_restore_warning,
                        pluralStringResource(R.plurals.data_count_exercises, preview.current.exercises, preview.current.exercises),
                        pluralStringResource(R.plurals.data_count_workouts, preview.current.workouts, preview.current.workouts),
                        pluralStringResource(R.plurals.data_count_sessions, preview.current.sessions, preview.current.sessions),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onSaveFirst) { Text(stringResource(R.string.data_preview_save_first)) }
            }
        },
        confirmButton = {
            Button(
                onClick = onRestore,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text(stringResource(R.string.data_preview_replace)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

private const val MAX_LISTED = 8

fun transferErrorText(res: Resources, e: Throwable): String = when (e) {
    is BundleException.NotABundle -> res.getString(R.string.data_error_not_bundle)
    is BundleException.NewerVersion -> res.getString(R.string.data_error_newer)
    is BundleException.TooLarge -> res.getString(R.string.data_error_too_large)
    is BundleException.BadMedia -> res.getString(R.string.data_error_bad_media, e.detail)
    is BundleException.Invalid -> res.getString(R.string.data_error_invalid, e.detail)
    is WorkoutRunningException -> res.getString(R.string.data_error_workout_running)
    else -> res.getString(R.string.data_error_io, e.message ?: e.javaClass.simpleName)
}
