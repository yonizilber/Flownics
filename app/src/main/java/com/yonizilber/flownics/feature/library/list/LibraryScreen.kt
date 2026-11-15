package com.yonizilber.flownics.feature.library.list

import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.pluralStringResource
import com.yonizilber.flownics.domain.library.ExerciseFamily
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.Muscle
import com.yonizilber.flownics.ui.component.CategoryAvatar
import com.yonizilber.flownics.ui.component.icon
import com.yonizilber.flownics.ui.component.labelRes

@Composable
fun LibraryScreen(
    onExerciseClick: (Long) -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddClick,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.library_add)) },
            )
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            if (!state.loading && state.totalCount == 0) {
                EmptyLibrary(Modifier.fillMaxSize())
                return@Column
            }
            OutlinedTextField(
                value = state.filter.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text(stringResource(R.string.library_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.filter.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.library_clear_filters))
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            FilterRow(
                selectedCategories = state.filter.categories,
                selectedMuscle = state.filter.muscle,
                onToggleCategory = viewModel::onToggleCategory,
                onMuscleSelected = viewModel::onMuscleSelected,
                tags = state.tags,
                selectedTag = state.filter.tag,
                onTagSelected = viewModel::onTagSelected,
            )
            if (state.exercises.isEmpty() && !state.loading) {
                Column(
                    Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.library_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = viewModel::onClearFilters) { Text(stringResource(R.string.library_clear_filters)) }
                }
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val families = state.families
                if (families != null) {
                    items(families, key = { it.base.id }) { family ->
                        FamilyCard(family, onExerciseClick, modifier = Modifier.animateItem())
                    }
                } else {
                    items(state.exercises, key = { it.id }) { exercise ->
                        ExerciseCard(exercise, onClick = { onExerciseClick(exercise.id) }, styleOf = state.baseNames[exercise.id], modifier = Modifier.animateItem())
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(
    selectedCategories: Set<ExerciseCategory>,
    selectedMuscle: Muscle?,
    onToggleCategory: (ExerciseCategory) -> Unit,
    onMuscleSelected: (Muscle?) -> Unit,
    tags: List<String>,
    selectedTag: String?,
    onTagSelected: (String?) -> Unit,
) {
    var muscleMenuOpen by remember { mutableStateOf(false) }
    var tagMenuOpen by remember { mutableStateOf(false) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Box {
                FilterChip(
                    selected = selectedMuscle != null,
                    onClick = { muscleMenuOpen = true },
                    label = {
                        Text(
                            selectedMuscle?.let { stringResource(it.labelRes) }
                                ?: stringResource(R.string.library_filter_muscle),
                        )
                    },
                    trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
                )
                DropdownMenu(expanded = muscleMenuOpen, onDismissRequest = { muscleMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_filter_any_muscle)) },
                        onClick = { onMuscleSelected(null); muscleMenuOpen = false },
                    )
                    Muscle.entries.forEach { muscle ->
                        DropdownMenuItem(
                            text = { Text(stringResource(muscle.labelRes)) },
                            onClick = { onMuscleSelected(muscle); muscleMenuOpen = false },
                        )
                    }
                }
            }
        }
        if (tags.isNotEmpty()) {
            item {
                Box {
                    FilterChip(
                        selected = selectedTag != null,
                        onClick = { tagMenuOpen = true },
                        label = { Text(selectedTag?.let { "# $it" } ?: stringResource(R.string.library_tags)) },
                        trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
                    )
                    DropdownMenu(expanded = tagMenuOpen, onDismissRequest = { tagMenuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.library_tag_any)) }, onClick = { onTagSelected(null); tagMenuOpen = false })
                        tags.forEach { tag ->
                            DropdownMenuItem(text = { Text("# $tag") }, onClick = { onTagSelected(tag); tagMenuOpen = false })
                        }
                    }
                }
            }
        }
        items(ExerciseCategory.entries) { category ->
            FilterChip(
                selected = category in selectedCategories,
                onClick = { onToggleCategory(category) },
                label = { Text(stringResource(category.labelRes)) },
                leadingIcon = { Icon(category.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

/** A family's default, with its styles one tap away. */
@Composable
private fun FamilyCard(family: ExerciseFamily, onExerciseClick: (Long) -> Unit, modifier: Modifier = Modifier) {
    var open by rememberSaveable(family.base.id) { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ExerciseCard(
            family.base,
            onClick = { onExerciseClick(family.base.id) },
            styles = family.styles.size,
            stylesOpen = open,
            onToggleStyles = { open = !open },
        )
        if (open) {
            family.styles.forEach { style ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onExerciseClick(style.id) }
                        .padding(start = 40.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CategoryAvatar(style.category, size = 28.dp)
                    Text(style.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ExerciseCard(
    exercise: Exercise,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    styleOf: String? = null,
    styles: Int = 0,
    stylesOpen: Boolean = false,
    onToggleStyles: () -> Unit = {},
) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CategoryAvatar(exercise.category)
            Column(Modifier.weight(1f)) {
                Text(
                    exercise.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val muscles = exercise.primaryMuscles.sortedBy { it.ordinal }.map { stringResource(it.labelRes) }
                val subtitle = (listOfNotNull(
                    styleOf?.let { stringResource(R.string.library_style_of, it) },
                    stringResource(R.string.library_general).takeIf { exercise.general },
                    stringResource(R.string.library_quick_added).takeIf { exercise.quickAdded },
                ) + exercise.categories.map { stringResource(it.labelRes) } + muscles + exercise.tags.map { "#" + it.name }).joinToString(" · ")
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (styles > 0) {
                AssistChip(
                    onClick = onToggleStyles,
                    label = { Text(pluralStringResource(R.plurals.library_styles, styles, styles)) },
                    trailingIcon = { Icon(if (stylesOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.FitnessCenter,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.library_empty_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.library_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
