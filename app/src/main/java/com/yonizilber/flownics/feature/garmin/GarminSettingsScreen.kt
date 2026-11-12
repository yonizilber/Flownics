package com.yonizilber.flownics.feature.garmin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.ui.component.rememberDateTexts

/**
 * Connecting Garmin with the user's own personal API key. Everything about it is said here: what
 * the key gives (read-only, their own account), where it's kept, and what happens on each sync.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GarminSettingsScreen(onBack: () -> Unit, viewModel: GarminViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var key by rememberSaveable { mutableStateOf("") }
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val dates = rememberDateTexts()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.garmin_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier.padding(innerPadding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.garmin_intro), style = MaterialTheme.typography.bodyMedium)
            message?.let { m ->
                Card(colors = CardDefaults.cardColors(containerColor = if (m.error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m.text, modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.close)) }
                    }
                }
            }
            if (settings.hasKey) {
                Text(stringResource(R.string.garmin_connected), style = MaterialTheme.typography.titleMedium)
                settings.lastSyncAtMillis?.let { Text(stringResource(R.string.garmin_last_sync, dates.date(it) + " " + dates.time(it)), style = MaterialTheme.typography.bodySmall) }
                settings.lastError?.let { Text(GarminViewModel.errorText(context, it, settings.lastErrorDetail), color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { viewModel.syncNow() }, enabled = !busy) { Text(stringResource(R.string.garmin_sync_now)) }
                    OutlinedButton(onClick = viewModel::removeKey, enabled = !busy) { Text(stringResource(R.string.garmin_remove_key)) }
                    if (busy) CircularProgressIndicator()
                }
                Text(stringResource(R.string.garmin_how_sync), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.garmin_steps_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.garmin_steps), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { runCatching { uri.openUri(KEY_PAGE) } }) { Text(stringResource(R.string.garmin_open_key_page)) }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(stringResource(R.string.garmin_key)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { viewModel.saveKey(key); key = "" }, enabled = key.isNotBlank() && !busy) { Text(stringResource(R.string.garmin_save_key)) }
                    if (busy) CircularProgressIndicator()
                }
            }
            Text(stringResource(R.string.garmin_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.garmin_file_alternative), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val KEY_PAGE = "https://connect.garmin.com/app/settings/personalApiToken"
