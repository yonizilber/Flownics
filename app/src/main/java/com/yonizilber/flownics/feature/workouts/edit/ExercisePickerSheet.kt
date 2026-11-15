package com.yonizilber.flownics.feature.workouts.edit

import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.pluralStringResource
import com.yonizilber.flownics.domain.library.Families
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.library.ExerciseFilter
import com.yonizilber.flownics.domain.library.applyFilter
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.ui.component.CategoryAvatar
import com.yonizilber.flownics.ui.component.icon
import com.yonizilber.flownics.ui.component.labelRes

/** Searchable exercise list. Selection order is kept, so exercises are added in the order tapped. */
@Composable
fun ExercisePickerList(
    library: Collection<Exercise>,
    selected: List<Long>,
    onToggle: (Long) -> Unit,
    multiSelect: Boolean,
    modifier: Modifier = Modifier,
    initialCategories: Set<ExerciseCategory> = emptySet(),
) {
    val res = LocalResources.current
    var query by remember { mutableStateOf("") }
    var categories by remember { mutableStateOf(initialCategories) }
    val shown = library.toList().applyFilter(ExerciseFilter(query, categories)) { res.getString(it.labelRes) }
    // Unfiltered, each family shows as its default; its styles open under it.
    val families = if (query.isBlank() && categories.isEmpty()) Families.group(library) else null
    var open by remember { mutableStateOf(setOf<Long>()) }

    Column(modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.library_search_hint)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ExerciseCategory.entries) { category ->
                FilterChip(
                    selected = category in categories,
                    onClick = { categories = if (category in categories) categories - category else categories + category },
                    label = { Text(stringResource(category.labelRes)) },
                    leadingIcon = { Icon(category.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            if (families != null) {
                families.forEach { family ->
                    item(key = family.base.id) {
                        PickerRow(
                            family.base, family.base.id in selected, multiSelect, { onToggle(family.base.id) },
                            styles = family.styles.size,
                            stylesOpen = family.base.id in open,
                            onToggleStyles = { open = if (family.base.id in open) open - family.base.id else open + family.base.id },
                            stylesChosen = family.styles.count { it.id in selected },
                        )
                    }
                    if (family.base.id in open) {
                        items(family.styles, key = { it.id }) { style ->
                            PickerRow(style, style.id in selected, multiSelect, { onToggle(style.id) }, indent = true)
                        }
                    }
                }
            } else {
                items(shown, key = { it.id }) { exercise ->
                    PickerRow(exercise, exercise.id in selected, multiSelect, { onToggle(exercise.id) })
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    exercise: Exercise,
    isSelected: Boolean,
    multiSelect: Boolean,
    onToggle: () -> Unit,
    indent: Boolean = false,
    styles: Int = 0,
    stylesOpen: Boolean = false,
    onToggleStyles: () -> Unit = {},
    stylesChosen: Int = 0,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = if (indent) 48.dp else 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CategoryAvatar(exercise.category, size = if (indent) 28.dp else 36.dp)
        Column(Modifier.weight(1f)) {
            Text(exercise.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (styles > 0) {
                Text(
                    pluralStringResource(R.plurals.library_styles, styles, styles) +
                        if (stylesChosen > 0) " · " + stringResource(R.string.picker_styles_chosen, stylesChosen) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onToggleStyles),
                )
            }
        }
        if (styles > 0) {
            IconButton(onClick = onToggleStyles) {
                Icon(
                    if (stylesOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(R.string.picker_show_styles),
                )
            }
        }
        if (multiSelect) {
            Checkbox(checked = isSelected, onCheckedChange = { onToggle() })
        } else {
            RadioButton(selected = isSelected, onClick = onToggle)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisePickerSheet(
    library: Collection<Exercise>,
    onDismiss: () -> Unit,
    onConfirm: (List<Long>) -> Unit,
    /** Adds a general step (name, minutes or none for "until next", description) instead of an exercise. */
    onNewGeneral: ((String, Int?, String) -> Unit)? = null,
) {
    val selected = remember { mutableStateListOf<Long>() }
    var generalOpen by remember { mutableStateOf(false) }
    if (generalOpen && onNewGeneral != null) {
        GeneralStepDialog(onDismiss = { generalOpen = false }, onAdd = { n, m, d -> generalOpen = false; onNewGeneral(n, m, d) })
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.9f)) {
            Text(
                stringResource(R.string.picker_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
            if (onNewGeneral != null) {
                OutlinedButton(onClick = { generalOpen = true }, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.picker_new_general))
                }
            }
            ExercisePickerList(
                library = library,
                selected = selected,
                onToggle = { id -> if (id in selected) selected.remove(id) else selected.add(id) },
                multiSelect = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { onConfirm(selected.toList()) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text(stringResource(R.string.picker_add, selected.size)) }
        }
    }
}

/** A general step: what it is, how long (empty: until "next"), and what to do. */
@Composable
private fun GeneralStepDialog(onDismiss: () -> Unit, onAdd: (String, Int?, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.picker_new_general)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.edit_general_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { Text(stringResource(R.string.picker_general_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(R.string.player_add_minutes_open)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text(stringResource(R.string.picker_general_what)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name, minutes.toIntOrNull(), description) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.player_add_new_go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
