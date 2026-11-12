package com.yonizilber.flownics.ui.component

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.history.ProgressMetric
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.MeasureMode
import com.yonizilber.flownics.domain.model.SetTarget
import com.yonizilber.flownics.domain.model.WorkoutBlock
import com.yonizilber.flownics.domain.workout.CompileWarning
import com.yonizilber.flownics.domain.workout.WorkStep
import java.text.DecimalFormat
import java.util.Locale

/** "0:45", "1:30", "12:05" — digits stay left-to-right in Hebrew text too. */
fun formatClock(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

@Composable
fun formatDuration(totalSeconds: Int): String = durationText(LocalResources.current, totalSeconds)

/** "45 sec", "12 min", "1 h 05 min". */
fun durationText(res: Resources, totalSeconds: Int): String {
    val minutes = (totalSeconds + 30) / 60
    return when {
        totalSeconds < 60 -> res.getString(R.string.duration_seconds, totalSeconds)
        minutes < 60 -> res.getString(R.string.duration_minutes, minutes)
        else -> res.getString(R.string.duration_hours, minutes / 60, String.format(Locale.ROOT, "%02d", minutes % 60))
    }
}

fun formatWeight(kg: Double): String = DecimalFormat("0.##").format(kg)

/** Volume totals get large: "12,450" (no decimals, grouped). */
fun formatVolume(kg: Double): String = DecimalFormat("#,##0").format(kg)

/** "10 reps · 40 kg", "0:30", "400 m"; empty pieces are skipped. */
@Composable
fun formatTarget(target: SetTarget, exercise: Exercise?): String = targetText(LocalResources.current, target, exercise)

/** [formatTarget] for code outside composition (the player notification). */
fun targetText(res: Resources, target: SetTarget, exercise: Exercise?): String {
    val mode = exercise?.measureMode ?: MeasureMode.REPS
    val parts = buildList {
        when (mode) {
            MeasureMode.REPS, MeasureMode.REPS_WEIGHT -> target.reps?.let {
                add(
                    if (exercise?.unilateral == true) {
                        res.getString(R.string.target_reps_each_side, it)
                    } else {
                        res.getString(R.string.target_reps, it)
                    },
                )
            }
            MeasureMode.TIME -> target.seconds?.let { add(res.getString(R.string.target_seconds, formatClock(it))) }
            MeasureMode.DISTANCE -> target.distanceMeters?.let { add(res.getString(R.string.target_distance, it)) }
        }
        if (mode == MeasureMode.REPS_WEIGHT) target.weightKg?.let { add(res.getString(R.string.target_weight, formatWeight(it))) }
    }
    return parts.joinToString(" · ").ifEmpty { res.getString(R.string.target_open) }
}

/**
 * What was actually logged: every recorded measure, whatever the exercise's mode is now (it may
 * have changed, or the exercise may be gone). "10 reps · 40 kg", "0:28", "Skipped".
 */
fun loggedText(res: Resources, actual: SetTarget, skipped: Boolean): String {
    if (skipped) return res.getString(R.string.logged_skipped)
    val parts = buildList {
        actual.reps?.let { add(res.getString(R.string.target_reps, it)) }
        actual.seconds?.let { add(res.getString(R.string.target_seconds, formatClock(it))) }
        actual.weightKg?.let { add(res.getString(R.string.target_weight, formatWeight(it))) }
        actual.distanceMeters?.let { add(res.getString(R.string.target_distance, it)) }
    }
    return parts.joinToString(" · ").ifEmpty { res.getString(R.string.logged_nothing) }
}

/** A metric's value with its unit: "62.5 kg", "2,400 kg", "12 reps", "1:30", "400 m". */
fun metricText(res: Resources, metric: ProgressMetric, value: Double): String = when (metric) {
    ProgressMetric.WEIGHT -> res.getString(R.string.target_weight, formatWeight(value))
    ProgressMetric.VOLUME -> res.getString(R.string.target_weight, formatVolume(value))
    ProgressMetric.REPS -> res.getString(R.string.target_reps, value.toInt())
    ProgressMetric.SECONDS -> formatClock(value.toInt())
    ProgressMetric.DISTANCE -> res.getString(R.string.target_distance, value.toInt())
}

/** "Set 2/3", or "Round 1/4" inside a circuit. */
fun progressText(res: Resources, step: WorkStep): String =
    if (step.totalRounds > 1) {
        res.getString(R.string.step_round, step.round, step.totalRounds)
    } else {
        res.getString(R.string.step_set, step.set, step.totalSets)
    }

@Composable
fun blockLabel(block: WorkoutBlock, index: Int): String =
    block.title.ifBlank { stringResource(R.string.wedit_block_untitled, index + 1) }

@Composable
fun warningText(warning: CompileWarning, blocks: List<WorkoutBlock>, library: Map<Long, Exercise>): String {
    val block = blocks.getOrNull(warning.blockIndex)?.let { blockLabel(it, warning.blockIndex) }.orEmpty()
    return when (warning) {
        is CompileWarning.EmptyBlock -> stringResource(R.string.warn_empty_block, block)
        is CompileWarning.MissingExercise -> stringResource(R.string.warn_missing_exercise, block)
        is CompileWarning.MissingActiveRestExercise -> stringResource(R.string.warn_missing_active_rest, block)
        is CompileWarning.EmptyActiveRestPool -> stringResource(R.string.warn_empty_pool, block)
        CompileWarning.MissingRestFiller -> stringResource(R.string.warning_missing_filler)
        is CompileWarning.ActiveRestInsideRound -> stringResource(R.string.warn_active_rest_inside_round, block)
        is CompileWarning.NoRestTimeForActiveRest -> stringResource(R.string.warn_no_rest_time, block)
        is CompileWarning.ActiveRestTooLong -> stringResource(
            R.string.warn_active_rest_too_long,
            library[warning.exerciseId]?.name.orEmpty(),
            formatClock(warning.neededSeconds),
            block,
            formatClock(warning.availableSeconds),
        )
    }
}
