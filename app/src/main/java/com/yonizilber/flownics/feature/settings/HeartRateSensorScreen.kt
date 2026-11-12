package com.yonizilber.flownics.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.player.PlayerController
import com.yonizilber.flownics.sensors.FoundSensor
import com.yonizilber.flownics.sensors.HeartRateMonitor
import com.yonizilber.flownics.sensors.SensorState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class HeartRateSensorViewModel @Inject constructor(val monitor: HeartRateMonitor, private val player: PlayerController) : ViewModel() {
    /** Testing here connects; leaving disconnects, unless a workout is using the sensor. */
    fun leave() {
        if (player.state.value == null) monitor.disconnect()
    }
}

/**
 * Choosing a heart-rate sensor: a chest strap, or a Garmin watch broadcasting its heart rate. Once
 * chosen, every workout in the app connects to it, shows the live rate and keeps it with the workout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeartRateSensorScreen(onBack: () -> Unit, viewModel: HeartRateSensorViewModel = hiltViewModel()) {
    val monitor = viewModel.monitor
    val saved by monitor.saved.collectAsStateWithLifecycle()
    val state by monitor.state.collectAsStateWithLifecycle()
    var scanning by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    val found = remember { mutableStateListOf<FoundSensor>() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        denied = r.values.any { !it }
        if (!denied) scanning = true
    }
    LaunchedEffect(scanning) {
        if (!scanning) return@LaunchedEffect
        found.clear()
        withTimeoutOrNull(SCAN_MS) { monitor.scan().collect { s -> if (found.none { it.address == s.address }) found += s } }
        scanning = false
    }
    DisposableEffect(Unit) { onDispose { viewModel.leave() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sensor_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier.padding(innerPadding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.sensor_intro), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.sensor_garmin_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            saved?.let { s ->
                Text(stringResource(R.string.sensor_chosen, s.name), style = MaterialTheme.typography.titleMedium)
                Text(
                    when (val st = state) {
                        is SensorState.Live -> st.bpm?.let { stringResource(R.string.sensor_live, it) } ?: stringResource(R.string.sensor_connected)
                        SensorState.Connecting -> stringResource(R.string.sensor_connecting)
                        SensorState.Off -> stringResource(R.string.sensor_off)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { monitor.connect() }, enabled = state == SensorState.Off) { Text(stringResource(R.string.sensor_test)) }
                    OutlinedButton(onClick = { monitor.forget() }) { Text(stringResource(R.string.sensor_forget)) }
                }
            }
            if (!monitor.bluetoothOn()) Text(stringResource(R.string.sensor_bluetooth_off), color = MaterialTheme.colorScheme.error)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { if (monitor.hasPermission()) scanning = true else permission.launch(monitor.permissions()) },
                    enabled = !scanning && monitor.bluetoothOn(),
                ) { Text(stringResource(R.string.sensor_scan)) }
                if (scanning) CircularProgressIndicator()
            }
            if (denied) Text(stringResource(R.string.sensor_denied), color = MaterialTheme.colorScheme.error)
            found.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().clickable { monitor.choose(s); monitor.connect() }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.bodyLarge)
                        Text(s.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (saved?.address == s.address) Text(stringResource(R.string.sensor_selected), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private const val SCAN_MS = 20_000L
