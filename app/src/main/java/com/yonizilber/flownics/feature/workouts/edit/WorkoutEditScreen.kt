package com.yonizilber.flownics.feature.workouts.edit

import com.yonizilber.flownics.domain.library.Families
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.model.ActiveRest
import com.yonizilber.flownics.domain.model.BlockStructure
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.MeasureMode
import com.yonizilber.flownics.domain.model.WorkoutItem
import com.yonizilber.flownics.ui.component.CategoryAvatar
import com.yonizilber.flownics.ui.component.NumberStepper
import com.yonizilber.flownics.ui.component.WarningList
import com.yonizilber.flownics.ui.component.blockLabel
import com.yonizilber.flownics.ui.component.formatClock
import com.yonizilber.flownics.ui.component.formatDuration
import com.yonizilber.flownics.ui.component.formatTarget
import com.yonizilber.flownics.ui.component.formatWeight

private val RestPresets = listOf(0, 15, 30, 45, 60, 90, 120, 180)

private sealed interface ActiveRestTarget {
    data class Item(val itemKey: Long, val current: ActiveRest) : ActiveRestTarget
    data class Rounds(val blockKey: Long, val current: ActiveRest) : ActiveRestTarget
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutEditScreen(
    onClose: () -> Unit,
    onSaved: (Long) -> Unit,
    viewModel: WorkoutEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val draft = state.draft
    val defaultTitle = stringResource(R.string.wedit_block_main)
    LaunchedEffect(viewModel) { viewModel.start(defaultTitle) }
    LaunchedEffect(viewModel) { viewModel.saved.collect(onSaved) }

    var confirmDiscard by remember { mutableStateOf(false) }
    var pickerForBlock by remember { mutableStateOf<Long?>(null) }
    var activeRestTarget by remember { mutableStateOf<ActiveRestTarget?>(null) }
    val requestClose = { if (draft.dirty) confirmDiscard = true else onClose() }
    BackHandler(onBack = requestClose)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (draft.isNew) R.string.wedit_title_new else R.string.wedit_title_existing)) },
                navigationIcon = {
                    IconButton(onClick = requestClose) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel)) }
                },
                actions = {
                    Button(
                        onClick = viewModel::onSave,
                        enabled = !draft.loading && !draft.saving,
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text(stringResource(R.string.edit_save)) }
                },
            )
        },
    ) { innerPadding ->
        if (draft.loading) {
            Box(Modifier.padding(innerPadding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(innerPadding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = viewModel::onNameChange,
                        label = { Text(stringResource(R.string.wedit_name)) },
                        isError = draft.showNameError,
                        supportingText = if (draft.showNameError) {
                            { Text(stringResource(R.string.wedit_name_required)) }
                        } else {
                            null
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.description,
                        onValueChange = viewModel::onDescriptionChange,
                        label = { Text(stringResource(R.string.wedit_description)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.compiled?.let { compiled ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Outlined.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                "${stringResource(R.string.overview_estimated)}: ${formatDuration(compiled.estimatedSeconds)}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        WarningList(compiled.warnings, state.blocks, state.library)
                    }
                    if (draft.blocks.isEmpty()) {
                        Text(stringResource(R.string.wedit_no_blocks), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            itemsIndexed(draft.blocks, key = { _, block -> block.key }) { index, block ->
                BlockCard(
                    block = block,
                    index = index,
                    lastIndex = draft.blocks.lastIndex,
                    library = state.library,
                    expandedItemKey = draft.expandedItemKey,
                    viewModel = viewModel,
                    onAddExercises = { pickerForBlock = block.key },
                    onEditItemActiveRest = { itemKey, current -> activeRestTarget = ActiveRestTarget.Item(itemKey, current) },
                    onEditRoundActiveRest = { activeRestTarget = ActiveRestTarget.Rounds(block.key, block.block.roundActiveRest) },
                    modifier = Modifier.animateItem(),
                )
            }
            item(key = "footer") { AddBlockRow(onAdd = viewModel::onAddBlock) }
        }
    }

    pickerForBlock?.let { blockKey ->
        ExercisePickerSheet(
            library = state.library.values,
            onDismiss = { pickerForBlock = null },
            onConfirm = { ids ->
                viewModel.onAddExercises(blockKey, ids)
                pickerForBlock = null
            },
        )
    }

    activeRestTarget?.let { target ->
        ActiveRestDialog(
            current = when (target) {
                is ActiveRestTarget.Item -> target.current
                is ActiveRestTarget.Rounds -> target.current
            },
            library = state.library,
            onDismiss = { activeRestTarget = null },
            onApply = { chosen ->
                when (target) {
                    is ActiveRestTarget.Item -> viewModel.onUpdateItem(target.itemKey) { it.copy(activeRest = chosen) }
                    is ActiveRestTarget.Rounds -> viewModel.onUpdateBlock(target.blockKey) { it.copy(roundActiveRest = chosen) }
                }
                activeRestTarget = null
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.edit_discard_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onClose()
                }) { Text(stringResource(R.string.edit_discard_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.edit_keep_editing)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockCard(
    block: BlockDraft,
    index: Int,
    lastIndex: Int,
    library: Map<Long, Exercise>,
    expandedItemKey: Long?,
    viewModel: WorkoutEditViewModel,
    onAddExercises: () -> Unit,
    onEditItemActiveRest: (Long, ActiveRest) -> Unit,
    onEditRoundActiveRest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = block.block
    val isCircuit = settings.structure == BlockStructure.CIRCUIT
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = settings.title,
                    onValueChange = { title -> viewModel.onUpdateBlock(block.key) { it.copy(title = title) } },
                    label = { Text(stringResource(R.string.wedit_block_title)) },
                    placeholder = { Text(blockLabel(settings, index)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { viewModel.onMoveBlock(index, index - 1) }, enabled = index > 0) {
                    Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = stringResource(R.string.edit_move_up))
                }
                IconButton(onClick = { viewModel.onMoveBlock(index, index + 1) }, enabled = index < lastIndex) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.edit_move_down))
                }
                IconButton(onClick = { viewModel.onRemoveBlock(block.key) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.wedit_remove_block))
                }
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(BlockStructure.STRAIGHT_SETS, BlockStructure.CIRCUIT).forEachIndexed { i, structure ->
                    SegmentedButton(
                        selected = settings.structure == structure,
                        onClick = { viewModel.onUpdateBlock(block.key) { it.copy(structure = structure) } },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) {
                        Text(
                            stringResource(
                                if (structure == BlockStructure.CIRCUIT) R.string.wedit_structure_circuit else R.string.wedit_structure_sets,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (isCircuit) {
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    NumberStepper(
                        label = stringResource(R.string.wedit_rounds),
                        value = settings.rounds,
                        onValueChange = { v -> viewModel.onUpdateBlock(block.key) { it.copy(rounds = v) } },
                        range = 1..20,
                    )
                    NumberStepper(
                        label = stringResource(R.string.wedit_round_rest),
                        value = settings.roundRestSeconds,
                        onValueChange = { v -> viewModel.onUpdateBlock(block.key) { it.copy(roundRestSeconds = v) } },
                        range = 0..600,
                        step = 15,
                        format = ::formatClock,
                    )
                }
                ActiveRestButton(settings.roundActiveRest, library, onEditRoundActiveRest)
            }

            block.items.forEachIndexed { itemIndex, item ->
                if (itemIndex > 0) HorizontalDivider()
                ItemRow(
                    item = item,
                    exercise = library[item.item.exerciseId],
                    isCircuit = isCircuit,
                    expanded = item.key == expandedItemKey,
                    index = itemIndex,
                    lastIndex = block.items.lastIndex,
                    library = library,
                    onToggle = { viewModel.onToggleExpanded(item.key) },
                    onUpdate = { transform -> viewModel.onUpdateItem(item.key, transform) },
                    onMove = { from, to -> viewModel.onMoveItem(block.key, from, to) },
                    onRemove = { viewModel.onRemoveItem(block.key, item.key) },
                    onEditActiveRest = { onEditItemActiveRest(item.key, item.item.activeRest) },
                )
            }

            OutlinedButton(onClick = onAddExercises, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text(stringResource(R.string.wedit_add_exercises), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun ItemRow(
    item: ItemDraft,
    exercise: Exercise?,
    isCircuit: Boolean,
    expanded: Boolean,
    index: Int,
    lastIndex: Int,
    library: Map<Long, Exercise>,
    onToggle: () -> Unit,
    onUpdate: ((WorkoutItem) -> WorkoutItem) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: () -> Unit,
    onEditActiveRest: () -> Unit,
) {
    val value = item.item
    Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (exercise != null) CategoryAvatar(exercise.category, size = 36.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    exercise?.name ?: stringResource(R.string.wedit_deleted_exercise),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (exercise == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val target = formatTarget(value.target, exercise)
                val volume = if (isCircuit) target else stringResource(R.string.wedit_item_sets_summary, value.sets, target)
                val rest = if (value.restSeconds > 0) " · " + stringResource(R.string.wedit_rest_summary, formatClock(value.restSeconds)) else ""
                Text(volume + rest, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (value.activeRest != ActiveRest.None) {
                    Text(
                        activeRestSummary(value.activeRest, library),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = stringResource(if (expanded) R.string.wedit_collapse else R.string.wedit_expand),
            )
        }

        if (expanded) {
            ItemSettings(value, exercise, isCircuit, library, onUpdate, onEditActiveRest)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = { onMove(index, index - 1) }, enabled = index > 0) {
                    Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = stringResource(R.string.edit_move_up))
                }
                IconButton(onClick = { onMove(index, index + 1) }, enabled = index < lastIndex) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.edit_move_down))
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.wedit_remove_item))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemSettings(
    value: WorkoutItem,
    exercise: Exercise?,
    isCircuit: Boolean,
    library: Map<Long, Exercise>,
    onUpdate: ((WorkoutItem) -> WorkoutItem) -> Unit,
    onEditActiveRest: () -> Unit,
) {
    val mode = exercise?.measureMode ?: MeasureMode.REPS
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Another style of the same exercise is one tap away; the sets and targets stay.
        exercise?.let { e -> Families.familyOf(e, library.values) }?.takeIf { it.styles.isNotEmpty() }?.let { family ->
            Text(stringResource(R.string.wedit_style), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                family.members.forEach { member ->
                    FilterChip(
                        selected = member.id == exercise.id,
                        onClick = { onUpdate { it.copy(exerciseId = member.id) } },
                        label = { Text(member.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (!isCircuit) {
                NumberStepper(stringResource(R.string.wedit_sets), value.sets, { v -> onUpdate { it.copy(sets = v) } }, range = 1..20)
            }
            when (mode) {
                MeasureMode.REPS, MeasureMode.REPS_WEIGHT -> NumberStepper(
                    stringResource(R.string.wedit_reps),
                    value.target.reps ?: 0,
                    { v -> onUpdate { it.copy(target = it.target.copy(reps = v.takeIf { r -> r > 0 })) } },
                    range = 0..200,
                )
                MeasureMode.TIME -> NumberStepper(
                    stringResource(R.string.wedit_seconds),
                    value.target.seconds ?: 0,
                    { v -> onUpdate { it.copy(target = it.target.copy(seconds = v.takeIf { s -> s > 0 })) } },
                    range = 0..3600,
                    step = 5,
                    format = ::formatClock,
                )
                MeasureMode.DISTANCE -> NumberStepper(
                    stringResource(R.string.wedit_distance),
                    value.target.distanceMeters ?: 0,
                    { v -> onUpdate { it.copy(target = it.target.copy(distanceMeters = v.takeIf { d -> d > 0 })) } },
                    range = 0..50_000,
                    step = 50,
                )
            }
        }
        if (mode == MeasureMode.REPS_WEIGHT) {
            WeightField(value.target.weightKg) { kg -> onUpdate { it.copy(target = it.target.copy(weightKg = kg)) } }
        }

        Text(
            stringResource(if (isCircuit) R.string.wedit_rest_after_exercise else R.string.wedit_rest_after_set),
            style = MaterialTheme.typography.labelLarge,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val presets = (RestPresets + value.restSeconds).distinct().sorted()
            presets.forEach { seconds ->
                FilterChip(
                    selected = value.restSeconds == seconds,
                    onClick = { onUpdate { it.copy(restSeconds = seconds) } },
                    label = { Text(formatClock(seconds)) },
                )
            }
        }
        ActiveRestButton(value.activeRest, library, onEditActiveRest)
    }
}

@Composable
private fun ActiveRestButton(activeRest: ActiveRest, library: Map<Long, Exercise>, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.wedit_active_rest), style = MaterialTheme.typography.labelLarge)
        AssistChip(
            onClick = onClick,
            label = { Text(activeRestSummary(activeRest, library), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Outlined.SelfImprovement, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
    }
}

@Composable
private fun WeightField(weightKg: Double?, onChange: (Double?) -> Unit) {
    // Local text keeps partial input like "62." while typing; the model only gets parsed numbers.
    var text by remember { mutableStateOf(weightKg?.let(::formatWeight).orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val normalized = input.replace(',', '.')
            if (normalized.isEmpty() || normalized.matches(Regex("""\d{0,4}(\.\d{0,2})?"""))) {
                text = normalized
                onChange(normalized.toDoubleOrNull()?.takeIf { it > 0 })
            }
        },
        label = { Text(stringResource(R.string.wedit_weight)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(0.5f),
    )
}

@Composable
private fun AddBlockRow(onAdd: (String) -> Unit) {
    val warmup = stringResource(R.string.wedit_block_warmup)
    val main = stringResource(R.string.wedit_block_main)
    val cooldown = stringResource(R.string.wedit_block_cooldown)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.wedit_add_block), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(warmup, main, cooldown).forEach { title ->
                AssistChip(
                    onClick = { onAdd(title) },
                    label = { Text(title) },
                    leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
    }
}
