package com.yonizilber.flownics.feature.player

import com.yonizilber.flownics.domain.player.RestLane
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.yonizilber.flownics.sensors.SensorState
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.history.volumeKg
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.MeasureMode
import com.yonizilber.flownics.domain.model.MediaKind
import com.yonizilber.flownics.domain.model.SetTarget
import com.yonizilber.flownics.domain.player.Phase
import com.yonizilber.flownics.domain.player.SetDefaults
import com.yonizilber.flownics.domain.player.StageTimeline
import com.yonizilber.flownics.domain.player.WorkoutPlayer
import com.yonizilber.flownics.domain.workout.RestStep
import com.yonizilber.flownics.domain.workout.Side
import com.yonizilber.flownics.domain.workout.WorkStep
import com.yonizilber.flownics.player.ActiveWorkout
import com.yonizilber.flownics.player.CueProblem
import com.yonizilber.flownics.ui.component.CategoryAvatar
import com.yonizilber.flownics.ui.component.MediaThumbnail
import com.yonizilber.flownics.ui.component.NumberStepper
import com.yonizilber.flownics.ui.component.formatClock
import com.yonizilber.flownics.ui.component.formatDuration
import com.yonizilber.flownics.ui.component.formatTarget
import com.yonizilber.flownics.ui.component.formatVolume
import com.yonizilber.flownics.ui.component.formatWeight
import com.yonizilber.flownics.ui.component.progressText
import kotlinx.coroutines.delay
import java.io.File

private const val WEIGHT_STEP_KG = 2.5
private const val ADJUST_SECONDS = 15

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    onClose: () -> Unit,
    onOpenExercise: (Long) -> Unit,
    onSaved: (sessionId: Long) -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val workout by viewModel.workout.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val problems by viewModel.cueProblems.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val sensor by viewModel.sensor.collectAsStateWithLifecycle()
    val defaults by viewModel.defaults.collectAsStateWithLifecycle()
    var mapTab by remember { mutableStateOf<MapTab?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }

    KeepScreenOn()

    val w = workout
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(w?.session?.workoutName.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    // Leaves the screen only; the workout keeps running in the notification.
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.player_close))
                    }
                },
                actions = {
                    // Live heart rate from the chosen sensor; "…" while it connects.
                    when (val hr = sensor) {
                        is SensorState.Live -> Text(
                            "❤ " + (hr.bpm?.toString() ?: "…"),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .semantics { contentDescription = hr.bpm?.let { "heart rate $it" } ?: "heart rate connecting" },
                        )
                        SensorState.Connecting -> Text("❤ …", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                        SensorState.Off -> Unit
                    }
                    if (w != null && w.player.phase !is Phase.Finished) {
                        // The workout map: both lanes (sets and rests) and every change to the workout.
                        TextButton(onClick = { mapTab = MapTab.SETS }) {
                            Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(stringResource(R.string.player_map_short), modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (w == null) {
            Box(Modifier.padding(innerPadding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.player_no_workout))
            }
            return@Scaffold
        }
        if (w.player.phase is Phase.Finished) {
            SummaryContent(
                workout = w,
                onSave = { notes -> viewModel.finish(notes) { id -> if (id != null) onSaved(id) else onClose() } },
                onDiscard = { viewModel.discard(onClose) },
                onBackToWorkout = viewModel::previous,
                onAddExercise = { adding = true },
                modifier = Modifier.padding(innerPadding),
            )
            if (adding) {
                AddExerciseSheet(library, defaults.sets, defaults.restBetweenSets, stringResource(R.string.player_add_and_go), onDismiss = { adding = false }) {
                    adding = false
                    viewModel.addExercise(it)
                }
            }
            return@Scaffold
        }

        val step = w.step
        val entry = (step as? WorkStep)?.let {
            entries[w.player.index] ?: SetDefaults.forStep(it, w.player.index, w.logs, w.lastTime[it.exerciseId])
        }
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            LinearProgressIndicator(
                progress = { w.player.index.toFloat() / w.session.steps.size },
                modifier = Modifier.fillMaxWidth(),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Notices(w, problems, onDismissRestored = viewModel::dismissRestoredNotice)
                if (step != null) {
                    val title = w.session.blockTitles.getOrNull(step.blockIndex)?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.wedit_block_untitled, step.blockIndex + 1)
                    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
                when (step) {
                    is WorkStep -> WorkContent(
                        workout = w,
                        step = step,
                        exercise = library[step.exerciseId],
                        entry = entry ?: SetTarget(),
                        onEntryChange = { viewModel.setEntry(w.player.index, it) },
                        resolveFile = viewModel::mediaFile,
                        onOpenExercise = onOpenExercise,
                    )
                    is RestStep -> RestContent(
                        phase = w.player.phase,
                        step = step,
                        library = library,
                        onAdjust = viewModel::adjust,
                        onOpenExercise = onOpenExercise,
                        lane = if (step.slot && RestLane.exists(w.session.steps)) {
                            LaneControls(
                                previous = RestLane.previousEntry(w.session.steps, w.player.index)?.let { e -> { viewModel.restPick(e) } },
                                onSkip = viewModel::restSkip,
                                onAgain = viewModel::restAgain,
                                onNotNow = viewModel::restNotNow,
                                onChoose = { mapTab = MapTab.RESTS },
                            )
                        } else {
                            null
                        },
                    )
                    null -> Unit
                }
                w.nextWork()?.let { NextUpCard(it, library[it.exerciseId]) }
            }
            Controls(
                workout = w,
                onPrevious = viewModel::previous,
                onPause = viewModel::pause,
                onResume = viewModel::resume,
                onSkip = viewModel::skip,
                // Timed sets log the time actually done; the controller fills it in.
                onDone = { viewModel.done(if (step is WorkStep && step.durationSeconds == null) entry else null) },
            )
        }
    }

    if (adding && workout?.player?.phase !is Phase.Finished) {
        AddExerciseSheet(library, defaults.sets, defaults.restBetweenSets, stringResource(R.string.player_add_confirm), onDismiss = { adding = false }) {
            adding = false
            viewModel.addExercise(it)
        }
    }
    val map = mapTab
    if (map != null && w != null && w.player.phase !is Phase.Finished) {
        WorkoutMapSheet(
            workout = w,
            library = library,
            initialTab = map,
            onJump = { i ->
                mapTab = null
                viewModel.jumpTo(i)
            },
            onPick = { e ->
                mapTab = null
                viewModel.restPick(e)
            },
            // The map stays open, showing the new set, so it's clear it was added.
            onAddSet = viewModel::addSet,
            onAddExercise = {
                mapTab = null
                adding = true
            },
            onHoldAll = viewModel::setHoldAll,
            onEnd = {
                mapTab = null
                confirmEnd = true
            },
            onDismiss = { mapTab = null },
        )
    }
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text(stringResource(R.string.player_end_title)) },
            text = { Text(stringResource(R.string.player_end_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    viewModel.end()
                }) { Text(stringResource(R.string.player_end_workout)) }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** The phone stays awake while the player is on screen; the service covers the screen-off case. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** Current monotonic time, refreshed several times a second, only where a clock is shown. */
@Composable
private fun rememberNow(phase: Phase): Long {
    val now by produceState(SystemClock.elapsedRealtime(), phase) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(100)
        }
    }
    return now
}

@Composable
private fun Notices(workout: ActiveWorkout, problems: Set<CueProblem>, onDismissRestored: () -> Unit) {
    val context = LocalContext.current
    val messages = buildList {
        if (workout.restored) add(stringResource(R.string.player_restored))
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) add(stringResource(R.string.player_notifications_off))
        if (CueProblem.VOICE_UNAVAILABLE in problems) add(stringResource(R.string.player_voice_unavailable))
        if (CueProblem.SOUND_UNAVAILABLE in problems) add(stringResource(R.string.player_sound_unavailable))
    }
    messages.forEach { message ->
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(12.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
    if (workout.restored) {
        TextButton(onClick = onDismissRestored) { Text(stringResource(R.string.player_restored_ok)) }
    }
}

@Composable
private fun WorkContent(
    workout: ActiveWorkout,
    step: WorkStep,
    exercise: Exercise?,
    entry: SetTarget,
    onEntryChange: (SetTarget) -> Unit,
    resolveFile: (String) -> File,
    onOpenExercise: (Long) -> Unit,
) {
    val phase = workout.player.phase
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (exercise != null) CategoryAvatar(exercise.category, size = 48.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    (exercise?.name ?: stringResource(R.string.wedit_deleted_exercise)) + sideSuffix(step.side),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    "${progressText(LocalResources.current, step)} · ${formatTarget(step.target, exercise)}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        exercise?.media?.firstOrNull { it.kind == MediaKind.IMAGE }?.let { image ->
            MediaThumbnail(
                media = image,
                resolveFile = resolveFile,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
        }
        if (step.durationSeconds != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimerRing(
                    phase = phase,
                    label = when (phase) {
                        is Phase.LeadIn -> stringResource(R.string.player_get_ready)
                        is Phase.Paused -> stringResource(R.string.player_paused)
                        else -> null
                    },
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (exercise != null) StageProgress(exercise, phase, bothSides = false)
        } else {
            EntryFields(exercise, entry, onEntryChange)
            Elapsed(phase)
            if (exercise != null && exercise.stages.isNotEmpty()) StageProgress(exercise, phase, bothSides = false)
        }
        workout.lastTime[step.exerciseId]?.let { last ->
            Text(
                stringResource(R.string.player_last_time, formatTarget(last.actual, exercise)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (exercise != null) CueList(exercise.cues)
        TextButton(onClick = { onOpenExercise(step.exerciseId) }) {
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.player_details), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun RestContent(
    phase: Phase,
    step: RestStep,
    library: Map<Long, Exercise>,
    onAdjust: (Int) -> Unit,
    onOpenExercise: (Long) -> Unit,
    lane: LaneControls?,
) {
    val active = step.activeExerciseId?.let { library[it] }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TimerRing(
            phase = phase,
            label = when {
                phase is Phase.Paused -> stringResource(R.string.player_paused)
                step.activeExerciseId != null -> stringResource(R.string.player_active_rest)
                else -> stringResource(R.string.player_rest)
            },
            color = MaterialTheme.colorScheme.tertiary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onAdjust(-ADJUST_SECONDS) }) { Text(stringResource(R.string.player_minus_seconds, ADJUST_SECONDS)) }
            OutlinedButton(onClick = { onAdjust(ADJUST_SECONDS) }) { Text(stringResource(R.string.player_plus_seconds, ADJUST_SECONDS)) }
        }
        if (step.activeExerciseId != null && active == null) {
            Text(stringResource(R.string.player_active_rest_missing), color = MaterialTheme.colorScheme.error)
        }
        if (step.active.size > 1) {
            RoundInRest(step, library, onOpenExercise)
        } else if (active != null) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CategoryAvatar(active.category, size = 40.dp)
                        Text(active.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    }
                    StageProgress(active, phase, bothSides = true)
                    CueList(active.cues)
                    TextButton(onClick = { onOpenExercise(active.id) }) {
                        Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.player_details), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        lane?.let { LaneRow(it, hasExercise = active != null || step.activeExerciseId != null) }
    }
}

/** A round of the combined workout's superset in this rest: its exercises one after the other. */
@Composable
private fun RoundInRest(step: RestStep, library: Map<Long, Exercise>, onOpenExercise: (Long) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.player_round_in_rest, step.active.size), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            step.active.forEachIndexed { k, part ->
                val exercise = library[part.exerciseId]
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${k + 1}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (exercise != null) CategoryAvatar(exercise.category, size = 32.dp)
                    Column(Modifier.weight(1f)) {
                        Text(exercise?.name ?: stringResource(R.string.player_active_rest_missing), style = MaterialTheme.typography.titleMedium, color = if (exercise == null) MaterialTheme.colorScheme.error else Color.Unspecified)
                        part.target?.let { t -> Text(formatTarget(t, exercise), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (exercise != null) {
                        IconButton(onClick = { onOpenExercise(exercise.id) }) {
                            Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.player_details))
                        }
                    }
                }
            }
        }
    }
}

/** What can be done in the rest lane from here: each one only moves the rest exercises, never the sets. */
class LaneControls(
    val previous: (() -> Unit)?,
    val onSkip: () -> Unit,
    val onAgain: () -> Unit,
    val onNotNow: () -> Unit,
    val onChoose: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LaneRow(lane: LaneControls, hasExercise: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
        lane.previous?.let { AssistChip(onClick = it, label = { Text(stringResource(R.string.player_rest_previous)) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Undo, null, Modifier.size(18.dp)) }) }
        if (hasExercise) {
            AssistChip(onClick = lane.onSkip, label = { Text(stringResource(R.string.player_rest_skip)) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp)) })
            AssistChip(onClick = lane.onAgain, label = { Text(stringResource(R.string.player_rest_again)) }, leadingIcon = { Icon(Icons.Outlined.Repeat, null, Modifier.size(18.dp)) })
            AssistChip(onClick = lane.onNotNow, label = { Text(stringResource(R.string.player_rest_not_now)) }, leadingIcon = { Icon(Icons.Outlined.PauseCircleOutline, null, Modifier.size(18.dp)) })
        }
        AssistChip(
            onClick = lane.onChoose,
            label = { Text(stringResource(if (hasExercise) R.string.player_rest_choose else R.string.player_rest_add)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.List, null, Modifier.size(18.dp)) },
        )
    }
}

@Composable
private fun TimerRing(phase: Phase, label: String?, color: Color) {
    val now = rememberNow(phase)
    val remaining = WorkoutPlayer.remainingMs(phase, now) ?: return
    // Past zero on a step that waits for the user: the time over, counting up, until they move on.
    val over = WorkoutPlayer.overtimeMs(phase, now)
    if (remaining == 0L && over > 0) {
        Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { 1f },
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.error,
                strokeWidth = 12.dp,
                strokeCap = StrokeCap.Round,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.player_overtime), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text("−" + formatClock((over / 1000).toInt()), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
            }
        }
        return
    }
    val total = (WorkoutPlayer.totalMs(phase) ?: 1L).coerceAtLeast(1L)
    Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { (remaining.toFloat() / total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxSize(),
            color = color,
            strokeWidth = 12.dp,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (label != null) Text(label, style = MaterialTheme.typography.titleMedium, color = color)
            // Rounded up: the display reaches 0 exactly when the step ends.
            Text(
                formatClock(((remaining + 999) / 1000).toInt()),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun Elapsed(phase: Phase) {
    val now = rememberNow(phase)
    val seconds = (WorkoutPlayer.elapsedMs(phase, now) / 1000).toInt()
    val text = if (phase is Phase.Paused) {
        stringResource(R.string.player_paused)
    } else {
        stringResource(R.string.player_elapsed, formatClock(seconds))
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The exercise's stages, with the one running now highlighted when the stages are timed. */
@Composable
private fun StageProgress(exercise: Exercise, phase: Phase, bothSides: Boolean) {
    if (exercise.stages.isEmpty()) return
    val timeline = remember(exercise, bothSides) { StageTimeline.of(exercise, bothSides) }
    val now = rememberNow(phase)
    val elapsed = (WorkoutPlayer.elapsedMs(phase, now) / 1000).toInt()
    val current = if (phase is Phase.LeadIn) null else StageTimeline.at(timeline, elapsed)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        current?.side?.let { side ->
            Text(
                stringResource(if (side == Side.LEFT) R.string.player_side_left else R.string.player_side_right),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        exercise.stages.forEachIndexed { index, stage ->
            val isCurrent = current?.stageIndex == index
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${index + 1}. ${stage.name}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    val clock = if (isCurrent) {
                        current.startSeconds + current.durationSeconds - elapsed
                    } else {
                        stage.durationSeconds
                    }
                    clock?.let { Text(formatClock(it), style = MaterialTheme.typography.labelLarge) }
                }
                if (isCurrent && stage.description.isNotBlank()) {
                    Text(stage.description, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun CueList(cues: List<String>) {
    if (cues.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        cues.forEach { cue ->
            Text("• $cue", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EntryFields(exercise: Exercise?, entry: SetTarget, onChange: (SetTarget) -> Unit) {
    val mode = exercise?.measureMode ?: MeasureMode.REPS
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (mode) {
            MeasureMode.REPS, MeasureMode.REPS_WEIGHT -> NumberStepper(
                label = stringResource(R.string.wedit_reps),
                value = entry.reps ?: 0,
                onValueChange = { onChange(entry.copy(reps = it)) },
            )
            MeasureMode.DISTANCE -> NumberStepper(
                label = stringResource(R.string.wedit_distance),
                value = entry.distanceMeters ?: 0,
                onValueChange = { onChange(entry.copy(distanceMeters = it)) },
                range = 0..100_000,
                step = 50,
            )
            // Open-ended timed set: the controller logs the time it took.
            MeasureMode.TIME -> Unit
        }
        if (mode == MeasureMode.REPS_WEIGHT) {
            WeightStepper(entry.weightKg ?: 0.0) { kg -> onChange(entry.copy(weightKg = kg.takeIf { it > 0 })) }
        }
    }
}

@Composable
private fun WeightStepper(value: Double, onValueChange: (Double) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.wedit_weight),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalIconButton(
                onClick = { onValueChange((value - WEIGHT_STEP_KG).coerceAtLeast(0.0)) },
                enabled = value > 0,
            ) { Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.wedit_decrease)) }
            Text(formatWeight(value), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            FilledTonalIconButton(onClick = { onValueChange(value + WEIGHT_STEP_KG) }) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.wedit_increase))
            }
        }
    }
}

@Composable
private fun NextUpCard(next: WorkStep, exercise: Exercise?) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.player_next_up), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (exercise != null) CategoryAvatar(exercise.category, size = 32.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    (exercise?.name ?: stringResource(R.string.wedit_deleted_exercise)) + sideSuffix(next.side),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${progressText(LocalResources.current, next)} · ${formatTarget(next.target, exercise)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Controls(
    workout: ActiveWorkout,
    onPrevious: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSkip: () -> Unit,
    onDone: () -> Unit,
) {
    val phase = workout.player.phase
    val isWork = workout.step is WorkStep
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onPrevious, enabled = workout.player.index > 0) {
                Icon(Icons.Outlined.SkipPrevious, contentDescription = stringResource(R.string.player_previous))
            }
            if (phase is Phase.Paused) {
                FilledTonalIconButton(onClick = onResume, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(R.string.player_resume))
                }
            } else {
                FilledTonalIconButton(onClick = onPause, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Outlined.Pause, contentDescription = stringResource(R.string.player_pause))
                }
            }
            if (isWork) {
                IconButton(onClick = onSkip) {
                    Icon(Icons.Outlined.SkipNext, contentDescription = stringResource(R.string.player_skip))
                }
            }
            Button(onClick = onDone, modifier = Modifier.weight(1f).height(56.dp)) {
                Icon(if (isWork) Icons.Outlined.Check else Icons.Outlined.SkipNext, contentDescription = null)
                Text(
                    stringResource(if (isWork) R.string.player_done else R.string.player_skip_rest),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SummaryContent(
    workout: ActiveWorkout,
    onSave: (String) -> Unit,
    onDiscard: () -> Unit,
    onBackToWorkout: () -> Unit,
    onAddExercise: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var notes by rememberSaveable { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }
    val completed = workout.player.index >= workout.session.steps.size
    val logs = workout.logs.values
    val done = logs.filterNot { it.skipped }
    val volume = logs.volumeKg()
    val seconds = ((System.currentTimeMillis() - workout.session.startedAtMillis) / 1000).toInt()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            if (completed) Icons.Outlined.EmojiEvents else Icons.Outlined.Flag,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Text(
            stringResource(if (completed) R.string.player_summary_complete else R.string.player_summary_ended),
            style = MaterialTheme.typography.headlineMedium,
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat(stringResource(R.string.player_summary_duration), formatDuration(seconds))
                Stat(stringResource(R.string.player_summary_sets), done.size.toString())
                val skipped = logs.size - done.size
                if (skipped > 0) Stat(stringResource(R.string.player_summary_skipped), skipped.toString())
                if (volume > 0) Stat(stringResource(R.string.player_summary_volume), formatVolume(volume))
            }
        }
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text(stringResource(R.string.player_notes)) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onSave(notes) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.player_save)) }
        OutlinedButton(onClick = onAddExercise, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.player_keep_going))
        }
        OutlinedButton(onClick = onBackToWorkout, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.player_back_to_workout))
        }
        TextButton(onClick = { confirmDiscard = true }) {
            Text(stringResource(R.string.player_discard), color = MaterialTheme.colorScheme.error)
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.player_discard_title)) },
            text = { Text(stringResource(R.string.player_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDiscard()
                }) { Text(stringResource(R.string.player_discard)) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun sideSuffix(side: Side?): String = when (side) {
    Side.LEFT -> " (${stringResource(R.string.side_left)})"
    Side.RIGHT -> " (${stringResource(R.string.side_right)})"
    null -> ""
}
