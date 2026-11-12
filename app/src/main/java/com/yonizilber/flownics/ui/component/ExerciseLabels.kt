package com.yonizilber.flownics.ui.component

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.history.ProgressMetric
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.MeasureMode
import com.yonizilber.flownics.domain.model.Muscle

@get:StringRes
val ExerciseCategory.labelRes: Int
    get() = when (this) {
        ExerciseCategory.STRENGTH -> R.string.category_strength
        ExerciseCategory.FLEXIBILITY -> R.string.category_flexibility
        ExerciseCategory.MOBILITY -> R.string.category_mobility
        ExerciseCategory.CARDIO -> R.string.category_cardio
        ExerciseCategory.CORE -> R.string.category_core
        ExerciseCategory.BALANCE -> R.string.category_balance
        ExerciseCategory.BREATHING -> R.string.category_breathing
        ExerciseCategory.WARMUP -> R.string.category_warmup
    }

val ExerciseCategory.icon: ImageVector
    get() = when (this) {
        ExerciseCategory.STRENGTH -> Icons.Outlined.FitnessCenter
        ExerciseCategory.FLEXIBILITY -> Icons.Outlined.SelfImprovement
        ExerciseCategory.MOBILITY -> Icons.Outlined.Sync
        ExerciseCategory.CARDIO -> Icons.AutoMirrored.Outlined.DirectionsRun
        ExerciseCategory.CORE -> Icons.Outlined.TrackChanges
        ExerciseCategory.BALANCE -> Icons.Outlined.Balance
        ExerciseCategory.BREATHING -> Icons.Outlined.Air
        ExerciseCategory.WARMUP -> Icons.Outlined.LocalFireDepartment
    }

data class CategoryColors(val container: Color, val content: Color)

// Work (strength) uses the warm accent, recovery (flexibility/mobility/breathing) the calm primary,
// so a workout plan reads at a glance as alternating effort and recovery.
@Composable
fun ExerciseCategory.colors(): CategoryColors {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        ExerciseCategory.STRENGTH, ExerciseCategory.CARDIO, ExerciseCategory.CORE ->
            CategoryColors(scheme.tertiaryContainer, scheme.onTertiaryContainer)
        ExerciseCategory.FLEXIBILITY, ExerciseCategory.MOBILITY, ExerciseCategory.BREATHING ->
            CategoryColors(scheme.primaryContainer, scheme.onPrimaryContainer)
        ExerciseCategory.BALANCE, ExerciseCategory.WARMUP ->
            CategoryColors(scheme.secondaryContainer, scheme.onSecondaryContainer)
    }
}

@get:StringRes
val MeasureMode.labelRes: Int
    get() = when (this) {
        MeasureMode.REPS -> R.string.measure_reps
        MeasureMode.TIME -> R.string.measure_time
        MeasureMode.REPS_WEIGHT -> R.string.measure_reps_weight
        MeasureMode.DISTANCE -> R.string.measure_distance
    }

@get:StringRes
val Muscle.labelRes: Int
    get() = when (this) {
        Muscle.NECK -> R.string.muscle_neck
        Muscle.TRAPS -> R.string.muscle_traps
        Muscle.SHOULDERS -> R.string.muscle_shoulders
        Muscle.CHEST -> R.string.muscle_chest
        Muscle.UPPER_BACK -> R.string.muscle_upper_back
        Muscle.LATS -> R.string.muscle_lats
        Muscle.LOWER_BACK -> R.string.muscle_lower_back
        Muscle.BICEPS -> R.string.muscle_biceps
        Muscle.TRICEPS -> R.string.muscle_triceps
        Muscle.FOREARMS -> R.string.muscle_forearms
        Muscle.ABS -> R.string.muscle_abs
        Muscle.OBLIQUES -> R.string.muscle_obliques
        Muscle.HIP_FLEXORS -> R.string.muscle_hip_flexors
        Muscle.GLUTES -> R.string.muscle_glutes
        Muscle.ADDUCTORS -> R.string.muscle_adductors
        Muscle.ABDUCTORS -> R.string.muscle_abductors
        Muscle.QUADS -> R.string.muscle_quads
        Muscle.HAMSTRINGS -> R.string.muscle_hamstrings
        Muscle.CALVES -> R.string.muscle_calves
    }

@get:StringRes
val ProgressMetric.labelRes: Int
    get() = when (this) {
        ProgressMetric.WEIGHT -> R.string.metric_weight
        ProgressMetric.VOLUME -> R.string.metric_volume
        ProgressMetric.REPS -> R.string.metric_reps
        ProgressMetric.SECONDS -> R.string.metric_seconds
        ProgressMetric.DISTANCE -> R.string.metric_distance
    }

@StringRes
fun levelNameRes(level: Int): Int = when (level) {
    1 -> R.string.level_1
    2 -> R.string.level_2
    3 -> R.string.level_3
    4 -> R.string.level_4
    else -> R.string.level_5
}

/** "Level 3 · Intermediate". */
@Composable
fun levelText(level: Int): String = stringResource(R.string.level_n, level, stringResource(levelNameRes(level)))
