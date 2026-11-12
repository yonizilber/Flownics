package com.yonizilber.flownics.feature.library.edit

import com.yonizilber.flownics.feature.workouts.edit.ExercisePickerList
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ModalBottomSheet
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.ExerciseLevel
import com.yonizilber.flownics.domain.model.MeasureMode
import com.yonizilber.flownics.domain.model.MediaKind
import com.yonizilber.flownics.domain.model.Muscle
import com.yonizilber.flownics.feature.ai.AiActionButton
import com.yonizilber.flownics.ui.component.MediaThumbnail
import com.yonizilber.flownics.ui.component.icon
import com.yonizilber.flownics.ui.component.labelRes
import com.yonizilber.flownics.ui.component.levelNameRes

private const val MAX_PHOTOS_PER_PICK = 6

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseEditScreen(
    onClose: () -> Unit,
    onSaved: (Long) -> Unit,
    onOpenAiSettings: () -> Unit,
    viewModel: ExerciseEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickFamily by rememberSaveable { mutableStateOf(false) }
    val fill by viewModel.fill.collectAsStateWithLifecycle()
    val aiReady by viewModel.aiReady.collectAsStateWithLifecycle()
    var showFill by rememberSaveable { mutableStateOf(false) }
    var showAiSetup by rememberSaveable { mutableStateOf(false) }
    var showVideoFinder by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val res = LocalResources.current
    var confirmDiscard by remember { mutableStateOf(false) }
    var showLinkDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is EditorEvent.Saved -> onSaved(event.id)
                EditorEvent.MediaFailed -> snackbarHostState.showSnackbar(res.getString(R.string.edit_media_failed))
                is EditorEvent.VideoTooLarge ->
                    snackbarHostState.showSnackbar(res.getString(R.string.edit_video_too_large, event.limitMb))
            }
        }
    }

    val requestClose = { if (state.dirty) confirmDiscard = true else onClose() }
    BackHandler(onBack = requestClose)

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS_PER_PICK),
        viewModel::onImagesPicked,
    )
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), viewModel::onVideoPicked)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.edit_title_new else R.string.edit_title_existing)) },
                navigationIcon = {
                    IconButton(onClick = requestClose) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    Button(
                        onClick = viewModel::onSave,
                        enabled = !state.saving && !state.importing && !state.loading,
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text(stringResource(R.string.edit_save)) }
                },
            )
        },
    ) { innerPadding ->
        if (state.loading) {
            Box(Modifier.padding(innerPadding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = { Text(stringResource(R.string.edit_name)) },
                isError = state.showNameError,
                supportingText = if (state.showNameError) {
                    { Text(stringResource(R.string.edit_name_required)) }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            AiActionButton(
                text = stringResource(R.string.ai_fill_button),
                enabled = state.name.isNotBlank(),
                outlined = true,
                onClick = { if (aiReady) showFill = true else showAiSetup = true },
            )

            CategoryPicker(state.categories, viewModel::onCategoryToggle, viewModel::onCategoryMain)
            MeasurePicker(state.measureMode, viewModel::onMeasureModeChange)
            LevelPicker(state.level, viewModel::onLevelChange)
            state.variationOfName?.let { base ->
                InputChip(
                    selected = true,
                    onClick = viewModel::onClearVariationOf,
                    label = { Text(stringResource(R.string.edit_variation_of, base)) },
                    trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.edit_variation_of_remove), modifier = Modifier.size(18.dp)) },
                )
            } ?: OutlinedButton(onClick = { pickFamily = true }) { Text(stringResource(R.string.edit_style_of_pick)) }
            if (pickFamily) {
                val others by viewModel.otherExercises.collectAsStateWithLifecycle()
                ModalBottomSheet(onDismissRequest = { pickFamily = false }) {
                    Text(
                        stringResource(R.string.edit_style_of_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Text(
                        stringResource(R.string.edit_style_of_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    ExercisePickerList(
                        library = others,
                        selected = emptyList(),
                        onToggle = { id ->
                            others.firstOrNull { it.id == id }?.let(viewModel::onSetVariationOf)
                            pickFamily = false
                        },
                        multiSelect = false,
                        modifier = Modifier.fillMaxHeight(0.8f),
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.edit_unilateral), modifier = Modifier.weight(1f))
                Switch(checked = state.unilateral, onCheckedChange = viewModel::onUnilateralChange)
            }

            MusclePicker(state, viewModel::onMuscleTap)

            OutlinedTextField(
                value = state.equipment,
                onValueChange = viewModel::onEquipmentChange,
                label = { Text(stringResource(R.string.edit_equipment)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            InstructionsEditor(state, viewModel)
            CuesEditor(state, viewModel)
            StagesEditor(state, viewModel)

            MediaEditor(
                state = state,
                viewModel = viewModel,
                onAddPhoto = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onAddVideo = { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                onAddLink = { showLinkDialog = true },
                onFindVideo = { showVideoFinder = true },
            )

            OutlinedTextField(
                value = state.notes,
                onValueChange = viewModel::onNotesChange,
                label = { Text(stringResource(R.string.edit_notes)) },
                minLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
            )
        }
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
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.edit_keep_editing)) }
            },
        )
    }

    if (showFill) {
        AiFillSheet(
            state = state,
            fill = fill,
            model = viewModel.aiModel,
            onAsk = viewModel::requestFill,
            onApply = { fields ->
                viewModel.applyFill(fields)
                showFill = false
            },
            onCancel = viewModel::dismissFill,
            onDismiss = {
                showFill = false
                viewModel.dismissFill()
            },
            onOpenAiSettings = {
                showFill = false
                onOpenAiSettings()
            },
        )
    }

    if (showAiSetup) {
        AlertDialog(
            onDismissRequest = { showAiSetup = false },
            title = { Text(stringResource(R.string.ai_not_ready_title)) },
            text = { Text(stringResource(R.string.ai_not_ready_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showAiSetup = false
                    onOpenAiSettings()
                }) { Text(stringResource(R.string.ai_open_settings)) }
            },
            dismissButton = { TextButton(onClick = { showAiSetup = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showVideoFinder) {
        VideoFinderSheet(
            exercise = viewModel.draftExercise(),
            alreadyAdded = state.media.filter { it.kind == MediaKind.LINK }.map { it.location }.toSet(),
            onAdd = { url -> viewModel.onAddLink(url) },
            onDismiss = { showVideoFinder = false },
            onOpenAiSettings = {
                showVideoFinder = false
                onOpenAiSettings()
            },
        )
    }

    if (showLinkDialog) {
        LinkDialog(
            onDismiss = { showLinkDialog = false },
            onSubmit = { url -> viewModel.onAddLink(url).also { ok -> if (ok) showLinkDialog = false } },
        )
    }
}

@Composable
private fun SectionTitle(text: String, hint: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPicker(selected: List<ExerciseCategory>, onToggle: (ExerciseCategory) -> Unit, onMain: (ExerciseCategory) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.edit_category), stringResource(R.string.edit_category_hint))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExerciseCategory.entries.forEach { category ->
                FilterChip(
                    selected = category in selected,
                    onClick = { onToggle(category) },
                    label = { Text(stringResource(category.labelRes)) },
                    leadingIcon = { Icon(category.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
        // With more than one kind, which is the main one (its icon in lists) can be chosen.
        if (selected.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.edit_category_main), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 14.dp))
                selected.forEach { category ->
                    FilterChip(
                        selected = category == selected.first(),
                        onClick = { onMain(category) },
                        label = { Text(stringResource(category.labelRes)) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeasurePicker(selected: MeasureMode, onSelect: (MeasureMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.edit_measure))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MeasureMode.entries.forEach { mode ->
                FilterChip(
                    selected = mode == selected,
                    onClick = { onSelect(mode) },
                    label = { Text(stringResource(mode.labelRes)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LevelPicker(selected: Int?, onSelect: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.edit_level), stringResource(R.string.edit_level_hint))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(stringResource(R.string.edit_level_unset)) })
            ExerciseLevel.RANGE.forEach { level ->
                FilterChip(
                    selected = selected == level,
                    onClick = { onSelect(level) },
                    label = { Text("$level · ${stringResource(levelNameRes(level))}") },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MusclePicker(state: ExerciseEditorState, onTap: (Muscle) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.edit_muscles), stringResource(R.string.edit_muscles_hint))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Muscle.entries.forEach { muscle ->
                val isPrimary = muscle in state.muscles.primary
                val isSecondary = muscle in state.muscles.secondary
                FilterChip(
                    selected = isPrimary || isSecondary,
                    onClick = { onTap(muscle) },
                    label = { Text(stringResource(muscle.labelRes)) },
                    leadingIcon = when {
                        isPrimary -> { { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp)) } }
                        isSecondary -> { { Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp)) } }
                        else -> null
                    },
                    colors = if (isPrimary) {
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = scheme.primary,
                            selectedLabelColor = scheme.onPrimary,
                            selectedLeadingIconColor = scheme.onPrimary,
                        )
                    } else {
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = scheme.primaryContainer,
                            selectedLabelColor = scheme.onPrimaryContainer,
                            selectedLeadingIconColor = scheme.onPrimaryContainer,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ReorderButtons(index: Int, lastIndex: Int, onMove: (Int, Int) -> Unit, onRemove: () -> Unit) {
    Row {
        IconButton(onClick = { onMove(index, index - 1) }, enabled = index > 0) {
            Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = stringResource(R.string.edit_move_up))
        }
        IconButton(onClick = { onMove(index, index + 1) }, enabled = index < lastIndex) {
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.edit_move_down))
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.edit_remove))
        }
    }
}

@Composable
private fun InstructionsEditor(state: ExerciseEditorState, viewModel: ExerciseEditViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.edit_instructions), stringResource(R.string.edit_instructions_hint))
        state.instructions.forEachIndexed { index, step ->
            key(step.key) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = step.text,
                        onValueChange = { viewModel.onStepChange(step.key, it) },
                        label = { Text(stringResource(R.string.edit_instruction_step, index + 1)) },
                        modifier = Modifier.weight(1f),
                    )
                    ReorderButtons(index, state.instructions.lastIndex, viewModel::onMoveStep) { viewModel.onRemoveStep(step.key) }
                }
            }
        }
        TextButton(onClick = viewModel::onAddStep) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Text(stringResource(R.string.edit_add_step))
        }
    }
}

@Composable
private fun CuesEditor(state: ExerciseEditorState, viewModel: ExerciseEditViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.edit_cues), stringResource(R.string.edit_cues_hint))
        state.cues.forEachIndexed { index, cue ->
            key(cue.key) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = cue.text,
                        onValueChange = { viewModel.onCueChange(cue.key, it) },
                        placeholder = { Text(stringResource(R.string.edit_cue_hint)) },
                        modifier = Modifier.weight(1f),
                    )
                    ReorderButtons(index, state.cues.lastIndex, viewModel::onMoveCue) { viewModel.onRemoveCue(cue.key) }
                }
            }
        }
        TextButton(onClick = viewModel::onAddCue) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Text(stringResource(R.string.edit_add_cue))
        }
    }
}

@Composable
private fun StagesEditor(state: ExerciseEditorState, viewModel: ExerciseEditViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.edit_stages), stringResource(R.string.edit_stages_hint))
        state.stages.forEachIndexed { index, stage ->
            key(stage.key) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = stage.name,
                                onValueChange = { viewModel.onStageChange(stage.copy(name = it)) },
                                label = { Text(stringResource(R.string.edit_stage_name, index + 1)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            ReorderButtons(index, state.stages.lastIndex, viewModel::onMoveStage) {
                                viewModel.onRemoveStage(stage.key)
                            }
                        }
                        OutlinedTextField(
                            value = stage.description,
                            onValueChange = { viewModel.onStageChange(stage.copy(description = it)) },
                            label = { Text(stringResource(R.string.edit_stage_description)) },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = stage.seconds,
                            onValueChange = { viewModel.onStageChange(stage.copy(seconds = it)) },
                            label = { Text(stringResource(R.string.edit_stage_seconds)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(0.5f),
                        )
                    }
                }
            }
        }
        TextButton(onClick = viewModel::onAddStage) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Text(stringResource(R.string.edit_add_stage))
        }
    }
}

@Composable
private fun MediaEditor(
    state: ExerciseEditorState,
    viewModel: ExerciseEditViewModel,
    onAddPhoto: () -> Unit,
    onAddVideo: () -> Unit,
    onAddLink: () -> Unit,
    onFindVideo: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.edit_media))
        if (state.media.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(state.media) { index, media ->
                    Box {
                        MediaThumbnail(
                            media = media,
                            resolveFile = viewModel::mediaFile,
                            modifier = Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                        IconButton(
                            onClick = { viewModel.onRemoveMedia(index) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(28.dp)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape),
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.edit_remove), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        if (state.importing) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = onAddPhoto,
                label = { Text(stringResource(R.string.edit_add_photo)) },
                leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            AssistChip(
                onClick = onAddVideo,
                label = { Text(stringResource(R.string.edit_add_video)) },
                leadingIcon = { Icon(Icons.Outlined.Videocam, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            AssistChip(
                onClick = onAddLink,
                label = { Text(stringResource(R.string.edit_add_link)) },
                leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        AssistChip(
            onClick = onFindVideo,
            enabled = state.name.isNotBlank(),
            label = { Text(stringResource(R.string.video_find)) },
            leadingIcon = { Icon(Icons.Outlined.SmartDisplay, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
    }
}

@Composable
private fun LinkDialog(onDismiss: () -> Unit, onSubmit: (String) -> Boolean) {
    var url by rememberSaveable { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_link_title)) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; invalid = false },
                placeholder = { Text(stringResource(R.string.edit_link_hint)) },
                isError = invalid,
                supportingText = if (invalid) {
                    { Text(stringResource(R.string.edit_link_invalid)) }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        },
        confirmButton = {
            TextButton(onClick = { invalid = !onSubmit(url) }) { Text(stringResource(R.string.edit_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
