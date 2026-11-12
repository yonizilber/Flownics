package com.yonizilber.flownics.feature.workouts.overview

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.calendar.CalendarEvents
import com.yonizilber.flownics.domain.workout.WorkStep
import com.yonizilber.flownics.player.StartResult
import com.yonizilber.flownics.ui.component.StepRow
import com.yonizilber.flownics.ui.component.WarningList
import com.yonizilber.flownics.ui.component.blockLabel
import com.yonizilber.flownics.ui.component.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutOverviewScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenWorkout: (Long) -> Unit,
    onOpenPlayer: () -> Unit,
    onAiReview: (Long) -> Unit,
    onAiVariation: (Long) -> Unit,
    onAskAi: (Long) -> Unit,
    onShare: (Long) -> Unit,
    viewModel: WorkoutOverviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val startProblem by viewModel.startProblem.collectAsStateWithLifecycle()
    // Asked once, right when it matters: the timer notification. Starting goes ahead either way;
    // the player tells the user if notifications are off.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.start(onOpenPlayer)
    }
    val context = LocalContext.current
    val startWorkout = {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else viewModel.start(onOpenPlayer)
    }
    val loaded = state as? WorkoutOverviewUiState.Loaded
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val copyName = loaded?.let { stringResource(R.string.duplicate_name, it.template.name) }
    val calendarFooter = stringResource(R.string.calendar_footer)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(loaded?.template?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (loaded != null) {
                        IconButton(onClick = { onEdit(loaded.template.id) }) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.action_edit))
                        }
                        IconButton(onClick = { onAiReview(loaded.template.id) }) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = stringResource(R.string.ai_menu_review))
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.ai_menu_review)) },
                                    leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onAiReview(loaded.template.id)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.ai_menu_workout_variation)) },
                                    leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onAiVariation(loaded.template.id)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.ai_menu_ask_workout)) },
                                    leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onAskAi(loaded.template.id)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.share_menu)) },
                                    leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onShare(loaded.template.id)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.calendar_add)) },
                                    leadingIcon = { Icon(Icons.Outlined.Event, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        CalendarEvents.planned(
                                            context,
                                            title = loaded.template.name,
                                            description = listOf(loaded.template.description.trim(), calendarFooter)
                                                .filter { it.isNotEmpty() }
                                                .joinToString("\n\n"),
                                            estimatedSeconds = loaded.compiled.estimatedSeconds,
                                        )
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_duplicate)) },
                                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.duplicate(copyName!!, onOpenWorkout)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_delete)) },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        confirmDelete = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (loaded != null) {
                Surface(tonalElevation = 3.dp) {
                    Button(
                        onClick = startWorkout,
                        enabled = loaded.compiled.steps.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(16.dp),
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Text(stringResource(R.string.overview_start), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
    ) { innerPadding ->
        when (val s = state) {
            WorkoutOverviewUiState.Loading -> Box(Modifier.padding(innerPadding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            WorkoutOverviewUiState.NotFound -> Box(Modifier.padding(innerPadding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.overview_not_found))
            }
            is WorkoutOverviewUiState.Loaded -> LazyColumn(
                modifier = Modifier.padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        if (s.template.description.isNotBlank()) {
                            Text(s.template.description, style = MaterialTheme.typography.bodyLarge)
                        }
                        SummaryCard(
                            estimatedSeconds = s.compiled.estimatedSeconds,
                            exerciseCount = s.compiled.steps.filterIsInstance<WorkStep>().map { it.exerciseId }.distinct().size,
                            stepCount = s.compiled.steps.size,
                        )
                        WarningList(s.compiled.warnings, s.template.blocks, s.library)
                    }
                }
                itemsIndexed(s.compiled.steps) { index, step ->
                    val previous = s.compiled.steps.getOrNull(index - 1)
                    if (previous == null || previous.blockIndex != step.blockIndex) {
                        if (previous != null) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text(
                            blockLabel(s.template.blocks[step.blockIndex], step.blockIndex),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    StepRow(step, s.library)
                }
            }
        }
    }

    startProblem?.let { problem ->
        if (problem == StartResult.AnotherInProgress) {
            AlertDialog(
                onDismissRequest = viewModel::dismissStartProblem,
                title = { Text(stringResource(R.string.start_conflict_title)) },
                text = { Text(stringResource(R.string.start_conflict_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.dismissStartProblem()
                        onOpenPlayer()
                    }) { Text(stringResource(R.string.start_conflict_resume)) }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.replaceAndStart(onOpenPlayer) }) {
                        Text(stringResource(R.string.start_conflict_replace), color = MaterialTheme.colorScheme.error)
                    }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = viewModel::dismissStartProblem,
                text = {
                    Text(
                        stringResource(
                            if (problem == StartResult.NotFound) R.string.overview_not_found else R.string.start_nothing_to_run,
                        ),
                    )
                },
                confirmButton = { TextButton(onClick = viewModel::dismissStartProblem) { Text(stringResource(R.string.close)) } },
            )
        }
    }

    if (confirmDelete && loaded != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.overview_delete_title)) },
            text = { Text(stringResource(R.string.overview_delete_body, loaded.template.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(onBack)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SummaryCard(estimatedSeconds: Int, exerciseCount: Int, stepCount: Int) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat(stringResource(R.string.overview_estimated), formatDuration(estimatedSeconds))
            Stat(stringResource(R.string.overview_exercises), exerciseCount.toString())
            Stat(stringResource(R.string.overview_steps), stepCount.toString())
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
