package com.yonizilber.flownics.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.workout.RestStep
import com.yonizilber.flownics.domain.workout.Side
import com.yonizilber.flownics.domain.workout.WorkStep
import com.yonizilber.flownics.domain.workout.WorkoutStep

/** One line of the workout timeline: a set to do, or a (possibly active) rest. */
@Composable
fun StepRow(step: WorkoutStep, library: Map<Long, Exercise>, modifier: Modifier = Modifier) {
    when (step) {
        is WorkStep -> {
            val exercise = library[step.exerciseId]
            Row(
                modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (exercise != null) CategoryAvatar(exercise.category, size = 36.dp)
                Column(Modifier.weight(1f)) {
                    val side = when (step.side) {
                        Side.LEFT -> " (${stringResource(R.string.side_left)})"
                        Side.RIGHT -> " (${stringResource(R.string.side_right)})"
                        null -> ""
                    }
                    Text(
                        (exercise?.name ?: stringResource(R.string.wedit_deleted_exercise)) + side,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val progress = progressText(LocalResources.current, step)
                    Text(
                        "$progress · ${formatTarget(step.target, exercise)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        is RestStep -> {
            val active = step.activeExerciseId?.let { library[it] }
            Row(
                modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    if (active != null) {
                        CategoryAvatar(active.category, size = 28.dp)
                    } else {
                        Icon(
                            Icons.Outlined.HourglassEmpty,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                val clock = formatClock(step.durationSeconds)
                Text(
                    if (active != null) {
                        "${stringResource(R.string.step_active_rest, clock)} · ${step.active.joinToString(" + ") { library[it.exerciseId]?.name ?: "?" }}"
                    } else {
                        stringResource(R.string.step_rest, clock)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (active != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
