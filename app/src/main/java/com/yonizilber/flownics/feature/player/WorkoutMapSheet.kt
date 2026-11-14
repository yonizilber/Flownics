package com.yonizilber.flownics.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.player.LaneEntry
import com.yonizilber.flownics.domain.player.LaneStatus
import com.yonizilber.flownics.domain.player.RestLane
import com.yonizilber.flownics.domain.workout.RestStep
import com.yonizilber.flownics.domain.workout.Side
import com.yonizilber.flownics.domain.workout.WorkStep
import com.yonizilber.flownics.player.ActiveWorkout
import com.yonizilber.flownics.ui.component.formatTarget
import com.yonizilber.flownics.ui.component.progressText

enum class MapTab { SETS, RESTS }

/**
 * The whole workout at a glance, in two lanes: the sets, and the exercises of the rests. Moving in
 * one leaves the other where it is: go back to redo a set and the stretches go on from where they
 * were; go back to a stretch and the sets don't move. Changes to the workout sit at the top.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorkoutMapSheet(
    workout: ActiveWorkout,
    library: Map<Long, Exercise>,
    initialTab: MapTab,
    onJump: (Int) -> Unit,
    onPick: (LaneEntry) -> Unit,
    /** Adds a set; gives its step index (shown and marked in the list), or null when nothing was added. */
    onAddSet: () -> Int?,
    onAddExercise: () -> Unit,
    onHoldAll: (Boolean) -> Unit,
    onEnd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hasLane = RestLane.exists(workout.session.steps)
    var tab by rememberSaveable { mutableStateOf(if (hasLane) initialTab else MapTab.SETS) }
    var added by rememberSaveable { mutableStateOf<Int?>(null) }
    var nothingToAdd by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.9f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.player_map), style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {
                        val at = onAddSet()
                        added = at
                        nothingToAdd = at == null
                        tab = MapTab.SETS
                    },
                    label = { Text(stringResource(R.string.player_add_set)) },
                    leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)) },
                )
                AssistChip(onClick = onAddExercise, label = { Text(stringResource(R.string.player_add_exercise)) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null, Modifier.size(18.dp)) })
                AssistChip(onClick = onEnd, label = { Text(stringResource(R.string.player_end_workout)) }, leadingIcon = { Icon(Icons.Outlined.Flag, null, Modifier.size(18.dp)) })
            }
            // What it does is said right there: the name alone wasn't clear.
            Row(
                Modifier.fillMaxWidth().toggleable(value = workout.holdAll, role = Role.Switch, onValueChange = onHoldAll),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.player_hold_all_short), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.player_hold_all_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = workout.holdAll, onCheckedChange = null)
            }
            if (nothingToAdd) Text(stringResource(R.string.player_add_set_none), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            if (hasLane) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    MapTab.entries.forEachIndexed { i, t ->
                        SegmentedButton(
                            selected = tab == t,
                            onClick = { tab = t },
                            shape = SegmentedButtonDefaults.itemShape(i, MapTab.entries.size),
                        ) { Text(stringResource(if (t == MapTab.SETS) R.string.player_map_sets else R.string.player_map_rests)) }
                    }
                }
            }
            when (tab) {
                MapTab.SETS -> SetsLane(workout, library, onJump, added, Modifier.weight(1f))
                MapTab.RESTS -> RestsLane(workout, library, onPick, Modifier.weight(1f))
            }
        }
    }
}

/** Every set, by block, with what was done; the current step marked. Tapping one goes there. */
@Composable
private fun SetsLane(workout: ActiveWorkout, library: Map<Long, Exercise>, onJump: (Int) -> Unit, added: Int?, modifier: Modifier) {
    val steps = workout.session.steps
    val index = workout.player.index
    val res = LocalResources.current
    // The rows: sets, plus the rest being done now (so "you are here" shows between two sets).
    val rows = steps.indices.filter { steps[it] is WorkStep || it == index }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (rows.indexOfFirst { it >= index }.takeIf { it >= 0 } ?: rows.size).minus(2).coerceAtLeast(0))
    // A set just added: bring it into view.
    LaunchedEffect(added) {
        val row = added?.let(rows::indexOf)?.takeIf { it >= 0 } ?: return@LaunchedEffect
        state.animateScrollToItem((row - 2).coerceAtLeast(0))
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.player_map_sets_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(state = state) {
            items(rows, key = { it }) { i ->
                val step = steps[i]
                val newBlock = rows.indexOf(i).let { k -> k == 0 || steps[rows[k - 1]].blockIndex != step.blockIndex }
                if (newBlock) {
                    val title = workout.session.blockTitles.getOrNull(step.blockIndex)?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.wedit_block_untitled, step.blockIndex + 1)
                    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                }
                when (step) {
                    is WorkStep -> {
                        val exercise = library[step.exerciseId]
                        val log = workout.logs[i]
                        val icon = when {
                            i == index -> Icons.Outlined.PlayCircle
                            log?.skipped == true -> Icons.Outlined.RemoveCircleOutline
                            log != null -> Icons.Outlined.CheckCircle
                            else -> Icons.Outlined.RadioButtonUnchecked
                        }
                        val isNew = added != null && (i == added || (i == added + 1 && step.side == Side.RIGHT))
                        MapRow(
                            icon = if (isNew) Icons.Outlined.Add else icon,
                            title = (exercise?.name ?: stringResource(R.string.wedit_deleted_exercise)) + side(step.side),
                            detail = (if (isNew) stringResource(R.string.player_map_added) + " · " else "") +
                                progressText(res, step) + " · " + if (log != null && !log.skipped) formatTarget(log.actual, exercise) else formatTarget(step.target, exercise),
                            current = i == index || isNew,
                            dim = log != null,
                            onClick = if (i == index) null else ({ onJump(i) }),
                            status = when {
                                i == index -> stringResource(R.string.player_lane_now)
                                log?.skipped == true -> stringResource(R.string.player_map_skipped)
                                log != null -> stringResource(R.string.player_lane_done)
                                else -> null
                            },
                        )
                    }
                    is RestStep -> MapRow(
                        icon = Icons.Outlined.PlayCircle,
                        title = stringResource(R.string.player_map_rest_now),
                        detail = step.activeExerciseId?.let { library[it]?.name }.orEmpty(),
                        current = true,
                        dim = false,
                        onClick = null,
                    )
                }
            }
        }
    }
}

/** The exercises of the rests, done and to come. Tapping one does it in this rest (or the next). */
@Composable
private fun RestsLane(workout: ActiveWorkout, library: Map<Long, Exercise>, onPick: (LaneEntry) -> Unit, modifier: Modifier) {
    val steps = workout.session.steps
    val index = workout.player.index
    val entries = RestLane.entries(steps, index)
    val inRest = (steps.getOrNull(index) as? RestStep)?.slot == true
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (entries.indexOfFirst { it.status != LaneStatus.DONE }.takeIf { it >= 0 } ?: entries.size).minus(2).coerceAtLeast(0))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(if (inRest) R.string.player_map_rests_hint_now else R.string.player_map_rests_hint_next),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(state = state) {
            items(entries, key = { it.stepIndex }) { entry ->
                val exercise = library[entry.exerciseId]
                MapRow(
                    icon = when (entry.status) {
                        LaneStatus.DONE -> Icons.Outlined.CheckCircle
                        LaneStatus.NOW -> Icons.Outlined.PlayCircle
                        LaneStatus.NEXT, LaneStatus.AT_END -> Icons.Outlined.RadioButtonUnchecked
                    },
                    title = exercise?.name ?: stringResource(R.string.wedit_deleted_exercise),
                    detail = stringResource(
                        when (entry.status) {
                            LaneStatus.DONE -> R.string.player_lane_done
                            LaneStatus.NOW -> R.string.player_lane_now
                            LaneStatus.NEXT -> R.string.player_lane_next
                            LaneStatus.AT_END -> R.string.player_lane_at_end
                        },
                    ),
                    current = entry.status == LaneStatus.NOW,
                    dim = entry.status == LaneStatus.DONE,
                    onClick = if (entry.status == LaneStatus.NOW) null else ({ onPick(entry) }),
                )
            }
        }
    }
}

@Composable
private fun MapRow(icon: ImageVector, title: String, detail: String, current: Boolean, dim: Boolean, onClick: (() -> Unit)?, status: String? = null) {
    val color = when {
        current -> MaterialTheme.colorScheme.primary
        dim -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = status, tint = color, modifier = Modifier.size(22.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = color, fontWeight = if (current) FontWeight.Bold else null)
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun side(side: Side?): String = when (side) {
    Side.LEFT -> " (${stringResource(R.string.side_left)})"
    Side.RIGHT -> " (${stringResource(R.string.side_right)})"
    null -> ""
}
