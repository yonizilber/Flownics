package com.yonizilber.flownics.feature.settings

import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material.icons.outlined.Event
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.BuildConfig
import com.yonizilber.flownics.R

@Composable
fun SettingsScreen(
    onOpenAi: () -> Unit,
    onAskAi: () -> Unit,
    onOpenData: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenGarmin: () -> Unit,
    onOpenSensor: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var showDisclaimer by rememberSaveable { mutableStateOf(false) }
    val cues by viewModel.cueSettings.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SectionTitle(stringResource(R.string.settings_ai_section))
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_ai)) },
            supportingContent = { Text(stringResource(R.string.settings_ai_hint)) },
            leadingContent = { Icon(Icons.Outlined.Key, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onOpenAi),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_ai_chat)) },
            supportingContent = { Text(stringResource(R.string.settings_ai_chat_hint)) },
            leadingContent = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onAskAi),
        )
        SectionTitle(stringResource(R.string.settings_data_section))
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_data)) },
            supportingContent = { Text(stringResource(R.string.settings_data_hint)) },
            leadingContent = { Icon(Icons.Outlined.Backup, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onOpenData),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.calendar_settings_title)) },
            supportingContent = { Text(stringResource(R.string.calendar_settings_hint)) },
            leadingContent = { Icon(Icons.Outlined.Event, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onOpenCalendar),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.garmin_title)) },
            supportingContent = { Text(stringResource(R.string.garmin_settings_hint)) },
            leadingContent = { Icon(Icons.Outlined.Watch, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onOpenGarmin),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.sensor_title)) },
            supportingContent = { Text(stringResource(R.string.sensor_settings_hint)) },
            leadingContent = { Icon(Icons.Outlined.MonitorHeart, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onOpenSensor),
        )
        SectionTitle(stringResource(R.string.settings_player_section))
        CueSwitch(
            title = stringResource(R.string.settings_cue_sound),
            hint = stringResource(R.string.settings_cue_sound_hint),
            icon = Icons.Outlined.MusicNote,
            checked = cues.sound,
            onCheckedChange = { on -> viewModel.updateCues { it.copy(sound = on) } },
        )
        CueSwitch(
            title = stringResource(R.string.settings_cue_voice),
            hint = stringResource(R.string.settings_cue_voice_hint),
            icon = Icons.Outlined.RecordVoiceOver,
            checked = cues.voice,
            onCheckedChange = { on -> viewModel.updateCues { it.copy(voice = on) } },
        )
        CueSwitch(
            title = stringResource(R.string.settings_cue_vibrate),
            hint = stringResource(R.string.settings_cue_vibrate_hint),
            icon = Icons.Outlined.Vibration,
            checked = cues.vibrate,
            onCheckedChange = { on -> viewModel.updateCues { it.copy(vibrate = on) } },
        )
        SectionTitle(stringResource(R.string.settings_about_section))
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_language)) },
            supportingContent = { Text(stringResource(R.string.settings_language_hint)) },
            leadingContent = { Icon(Icons.Outlined.Language, contentDescription = null) },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_show_disclaimer)) },
            leadingContent = { Icon(Icons.Outlined.HealthAndSafety, contentDescription = null) },
            modifier = Modifier.clickable { showDisclaimer = true },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)) },
            leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
        )
    }

    if (showDisclaimer) {
        AlertDialog(
            onDismissRequest = { showDisclaimer = false },
            title = { Text(stringResource(R.string.disclaimer_title)) },
            text = {
                Text(
                    text = stringResource(R.string.disclaimer_body),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { showDisclaimer = false }) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun CueSwitch(title: String, hint: String, icon: ImageVector, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(hint) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    )
}
