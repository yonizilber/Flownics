package com.yonizilber.flownics.feature.settings

import com.yonizilber.flownics.calendar.ReminderSettings
import com.yonizilber.flownics.calendar.PlanReminders
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.calendar.CalendarSync
import com.yonizilber.flownics.calendar.CalendarSyncSettings
import com.yonizilber.flownics.calendar.WritableCalendar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CalendarSettingsViewModel @Inject constructor(private val sync: CalendarSync, private val reminders: PlanReminders) : ViewModel() {
    val settings: StateFlow<CalendarSyncSettings> = sync.settings
    val reminderSettings: StateFlow<ReminderSettings> = reminders.settings
    fun setReminder(minutes: Int?) = reminders.set(minutes)
    fun hasPermission() = sync.hasPermission()
    suspend fun calendars() = sync.writableCalendars()
    fun enable(calendar: WritableCalendar) = sync.enable(calendar)
    fun disable(removeEvents: Boolean) = sync.disable(removeEvents)
    fun setIncludeDone(include: Boolean) = sync.setIncludeDone(include)
}

/**
 * Where the plan goes in the user's calendar. The calendar permission is asked only here, when
 * the user turns syncing on; nothing is read or written in the calendar before that.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CalendarSettingsScreen(onBack: () -> Unit, viewModel: CalendarSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var calendars by remember { mutableStateOf<List<WritableCalendar>?>(null) }
    var denied by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }
    val loadCalendars: () -> Unit = { scope.launch { calendars = viewModel.calendars() } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        denied = result.values.any { !it }
        if (!denied) loadCalendars()
    }
    LaunchedEffect(Unit) { if (viewModel.hasPermission()) loadCalendars() }
    val reminder by viewModel.reminderSettings.collectAsStateWithLifecycle()
    var pendingMinutes by remember { mutableStateOf<Int?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingMinutes?.let(viewModel::setReminder)
        denied = !granted
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val chooseReminder: (Int?) -> Unit = { minutes ->
        val needs = minutes != null && android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needs) {
            pendingMinutes = minutes
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.setReminder(minutes)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.calendar_settings_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier.padding(innerPadding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.reminders_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.reminders_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null, 15, 30, 60, 120).forEach { m ->
                    androidx.compose.material3.FilterChip(
                        selected = reminder.minutesBefore == m,
                        onClick = { chooseReminder(m) },
                        label = { Text(if (m == null) stringResource(R.string.reminders_off) else stringResource(R.string.reminders_minutes, m)) },
                    )
                }
            }
            androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.calendar_settings_intro), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.calendar_settings_tip), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            settings.lastError?.takeIf { settings.enabled }?.let { error ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(error, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            if (settings.enabled) {
                Text(stringResource(R.string.calendar_settings_on, settings.calendarName), style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.calendar_settings_include_done))
                        Text(stringResource(R.string.calendar_settings_include_done_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = settings.includeDone, onCheckedChange = viewModel::setIncludeDone)
                }
                OutlinedButton(onClick = { confirmOff = true }) { Text(stringResource(R.string.calendar_settings_turn_off)) }
            }

            val list = calendars
            if (list == null) {
                Button(onClick = {
                    permission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                }) { Text(stringResource(if (settings.enabled) R.string.calendar_settings_change else R.string.calendar_settings_turn_on)) }
                if (denied) Text(stringResource(R.string.calendar_settings_denied), color = MaterialTheme.colorScheme.error)
            } else {
                Text(stringResource(R.string.calendar_settings_choose), style = MaterialTheme.typography.titleMedium)
                if (list.isEmpty()) Text(stringResource(R.string.calendar_settings_none), color = MaterialTheme.colorScheme.error)
                list.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { viewModel.enable(c) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RadioButton(selected = settings.enabled && settings.calendarId == c.id, onClick = { viewModel.enable(c) })
                        Box(Modifier.size(14.dp).clip(CircleShape).background(Color(c.color)))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.bodyLarge)
                            Text(c.account, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                TextButton(onClick = loadCalendars) { Text(stringResource(R.string.calendar_settings_refresh)) }
            }
        }
    }

    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text(stringResource(R.string.calendar_settings_turn_off)) },
            text = { Text(stringResource(R.string.calendar_settings_off_question)) },
            confirmButton = { TextButton(onClick = { viewModel.disable(removeEvents = true); confirmOff = false }) { Text(stringResource(R.string.calendar_settings_off_remove)) } },
            dismissButton = { TextButton(onClick = { viewModel.disable(removeEvents = false); confirmOff = false }) { Text(stringResource(R.string.calendar_settings_off_keep)) } },
        )
    }
}
