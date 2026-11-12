package com.yonizilber.flownics.feature.workouts.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.model.ActiveRest
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.PoolFocus
import com.yonizilber.flownics.ui.component.icon
import com.yonizilber.flownics.ui.component.labelRes

private enum class Mode { NONE, POOL, SPECIFIC }

private val RecoveryCategories = setOf(ExerciseCategory.FLEXIBILITY, ExerciseCategory.MOBILITY, ExerciseCategory.BREATHING)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActiveRestDialog(
    current: ActiveRest,
    library: Map<Long, Exercise>,
    onDismiss: () -> Unit,
    onApply: (ActiveRest) -> Unit,
) {
    var mode by remember {
        mutableStateOf(
            when (current) {
                ActiveRest.None -> Mode.NONE
                is ActiveRest.Pool -> Mode.POOL
                is ActiveRest.Specific -> Mode.SPECIFIC
            },
        )
    }
    var categories by remember { mutableStateOf((current as? ActiveRest.Pool)?.categories ?: ActiveRest.Pool().categories) }
    var focusWorked by remember { mutableStateOf((current as? ActiveRest.Pool)?.focus != PoolFocus.ANY) }
    var specificId by remember { mutableStateOf((current as? ActiveRest.Specific)?.exerciseId) }

    val canApply = when (mode) {
        Mode.NONE -> true
        Mode.POOL -> categories.isNotEmpty()
        Mode.SPECIFIC -> specificId?.let(library::containsKey) == true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.active_rest_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeOption(Mode.NONE, mode, R.string.active_rest_none) { mode = it }
                ModeOption(Mode.POOL, mode, R.string.active_rest_pool) { mode = it }
                ModeOption(Mode.SPECIFIC, mode, R.string.active_rest_specific) { mode = it }

                when (mode) {
                    Mode.NONE -> Unit
                    Mode.POOL -> Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    ) {
                        Text(stringResource(R.string.active_rest_pick_types), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ExerciseCategory.entries.forEach { category ->
                                FilterChip(
                                    selected = category in categories,
                                    onClick = { categories = if (category in categories) categories - category else categories + category },
                                    label = { Text(stringResource(category.labelRes)) },
                                    leadingIcon = { Icon(category.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                )
                            }
                        }
                        if (categories.isEmpty()) {
                            Text(
                                stringResource(R.string.active_rest_needs_categories),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.active_rest_pool_focus), modifier = Modifier.weight(1f))
                            Switch(checked = focusWorked, onCheckedChange = { focusWorked = it })
                        }
                    }
                    Mode.SPECIFIC -> {
                        // Recovery exercises first: that is what active rest is usually for.
                        val ordered = library.values.sortedByDescending { e -> e.categories.any { it in RecoveryCategories } }
                        ExercisePickerList(
                            library = ordered,
                            selected = listOfNotNull(specificId),
                            onToggle = { specificId = it },
                            multiSelect = false,
                            initialCategories = RecoveryCategories.filter { c -> library.values.any { c in it.categories } }.toSet(),
                            modifier = Modifier.heightIn(max = 360.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canApply,
                onClick = {
                    onApply(
                        when (mode) {
                            Mode.NONE -> ActiveRest.None
                            Mode.POOL -> ActiveRest.Pool(categories, if (focusWorked) PoolFocus.WORKED_MUSCLES else PoolFocus.ANY)
                            Mode.SPECIFIC -> ActiveRest.Specific(specificId!!)
                        },
                    )
                },
            ) { Text(stringResource(R.string.apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ModeOption(option: Mode, selected: Mode, labelRes: Int, onSelect: (Mode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = option == selected, onClick = { onSelect(option) }, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = option == selected, onClick = null)
        Text(stringResource(labelRes), modifier = Modifier.weight(1f))
    }
}

@Composable
fun activeRestSummary(activeRest: ActiveRest, library: Map<Long, Exercise>): String = when (activeRest) {
    ActiveRest.None -> stringResource(R.string.active_rest_none)
    is ActiveRest.Specific -> library[activeRest.exerciseId]?.name ?: stringResource(R.string.wedit_deleted_exercise)
    is ActiveRest.Pool -> stringResource(
        R.string.active_rest_pool_summary,
        activeRest.categories.sortedBy { it.ordinal }.map { stringResource(it.labelRes) }.joinToString(", "),
    )
}
