package com.yonizilber.flownics.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.MeasureMode
import com.yonizilber.flownics.domain.model.SetTarget
import com.yonizilber.flownics.feature.workouts.edit.ExercisePickerList

/** What to add: the exercise, how many sets, its target, and the rest after each set. */
data class AddedExercise(val exerciseId: Long, val sets: Int, val target: SetTarget, val restSeconds: Int)

/**
 * Adding an exercise that wasn't planned, during the workout (or to start a free one): pick it,
 * then sets, reps or time, and rest. Starts from the app's defaults.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExerciseSheet(
    library: Map<Long, Exercise>,
    defaultSets: Int,
    defaultRest: Int,
    confirmText: String,
    onDismiss: () -> Unit,
    onAdd: (AddedExercise) -> Unit,
) {
    var chosen by rememberSaveable { mutableStateOf<Long?>(null) }
    var sets by rememberSaveable { mutableStateOf(defaultSets.toString()) }
    var amount by rememberSaveable { mutableStateOf("") }
    var weight by rememberSaveable { mutableStateOf("") }
    var rest by rememberSaveable { mutableStateOf(defaultRest.toString()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val exercise = chosen?.let(library::get)
        if (exercise == null) {
            Column(Modifier.fillMaxHeight(0.9f)) {
                Text(stringResource(R.string.player_add_pick), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                ExercisePickerList(
                    library = library.values,
                    selected = emptyList(),
                    onToggle = { chosen = it },
                    multiSelect = false,
                    modifier = Modifier.weight(1f),
                )
            }
            return@ModalBottomSheet
        }
        val timed = exercise.measureMode == MeasureMode.TIME
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(exercise.name, style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Number(sets, { sets = it }, R.string.player_add_sets, Modifier.weight(1f))
                Number(amount, { amount = it }, if (timed) R.string.player_add_seconds else R.string.player_add_reps, Modifier.weight(1f))
                if (exercise.measureMode == MeasureMode.REPS_WEIGHT) Number(weight, { weight = it }, R.string.player_add_weight, Modifier.weight(1f), decimal = true)
                Number(rest, { rest = it }, R.string.player_add_rest, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { chosen = null }) { Text(stringResource(R.string.player_add_other)) }
                Button(
                    onClick = {
                        val n = amount.toIntOrNull()?.takeIf { it > 0 }
                        val target = when (exercise.measureMode) {
                            MeasureMode.TIME -> SetTarget(seconds = n ?: 30)
                            MeasureMode.REPS -> SetTarget(reps = n)
                            MeasureMode.REPS_WEIGHT -> SetTarget(reps = n, weightKg = weight.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 })
                            MeasureMode.DISTANCE -> SetTarget(distanceMeters = n)
                        }
                        onAdd(AddedExercise(exercise.id, sets.toIntOrNull()?.coerceIn(1, 20) ?: 1, target, rest.toIntOrNull()?.coerceIn(0, 900) ?: 0))
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(confirmText) }
            }
        }
    }
}

@Composable
private fun Number(value: String, onChange: (String) -> Unit, label: Int, modifier: Modifier, decimal: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || (decimal && (it == '.' || it == ',')) }.take(5)) },
        label = { Text(stringResource(label), maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}
