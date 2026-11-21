package com.yonizilber.flownics.feature.library.detail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.data.ai.ExerciseAiNote
import com.yonizilber.flownics.domain.history.ExerciseSession
import com.yonizilber.flownics.feature.ai.SourceList
import com.yonizilber.flownics.feature.ai.labelRes
import com.yonizilber.flownics.ui.component.BodyMap
import com.yonizilber.flownics.ui.component.BodyMapKey
import com.yonizilber.flownics.ui.component.bodyMapDescription
import com.yonizilber.flownics.ui.component.levelText
import com.yonizilber.flownics.ui.component.rememberDateTexts
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseMedia
import com.yonizilber.flownics.domain.model.MediaKind
import com.yonizilber.flownics.domain.model.Muscle
import com.yonizilber.flownics.ui.component.CategoryBadge
import com.yonizilber.flownics.ui.component.MediaThumbnail
import com.yonizilber.flownics.ui.component.labelRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseDetailScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onRefine: (Long) -> Unit,
    onOpenExercise: (Long) -> Unit,
    onOpenSession: (Long) -> Unit,
    onAskAi: (Long) -> Unit,
    onVariations: (Long) -> Unit,
    onShare: (Long) -> Unit,
    onOpenMuscle: (Muscle) -> Unit,
    onOpenTag: (String) -> Unit = {},
    viewModel: ExerciseDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val family by viewModel.family.collectAsStateWithLifecycle()
    val familyBaseId by viewModel.familyBaseId.collectAsStateWithLifecycle()
    val savedVariations by viewModel.savedVariationCount.collectAsStateWithLifecycle()
    val aiNotes by viewModel.aiNotes.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val usedInWorkouts by viewModel.usedInWorkouts.collectAsStateWithLifecycle()
    val exercise = (state as? ExerciseDetailUiState.Loaded)?.exercise
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val copyName = exercise?.let { stringResource(R.string.duplicate_name, it.name) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(exercise?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (exercise != null) {
                        IconButton(onClick = { onEdit(exercise.id) }) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.action_edit))
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.ai_refine_button)) },
                                    leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onRefine(exercise.id)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.ai_menu_ask_exercise)) },
                                    leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onAskAi(exercise.id)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.ai_menu_variations)) },
                                    leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onVariations(exercise.id)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.share_menu)) },
                                    leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onShare(exercise.id)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_duplicate)) },
                                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.duplicate(copyName!!, onOpenExercise)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_delete)) },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        confirmDelete = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            when (val s = state) {
                ExerciseDetailUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                ExerciseDetailUiState.NotFound -> Text(
                    stringResource(R.string.detail_not_found),
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
                is ExerciseDetailUiState.Loaded -> ExerciseDetailContent(
                    exercise = s.exercise,
                    history = history,
                    aiNotes = aiNotes,
                    family = family,
                    familyBaseId = familyBaseId,
                    onMakeDefault = viewModel::makeFamilyDefault,
                    savedVariations = savedVariations,
                    viewModel = viewModel,
                    onOpenSession = onOpenSession,
                    onOpenExercise = onOpenExercise,
                    onOpenMuscle = onOpenMuscle,
                    onOpenTag = onOpenTag,
                    onVariations = { onVariations(s.exercise.id) },
                )
            }
        }
    }

    if (confirmDelete && exercise != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = {
                val body = stringResource(R.string.delete_confirm_body, exercise.name)
                Text(if (usedInWorkouts > 0) body + "\n\n" + stringResource(R.string.delete_used_in_workouts, usedInWorkouts) else body)
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(onBack)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ExerciseDetailContent(
    exercise: Exercise,
    history: List<ExerciseSession>?,
    aiNotes: List<ExerciseAiNote>,
    family: List<Exercise>,
    familyBaseId: Long?,
    onMakeDefault: () -> Unit,
    savedVariations: Int,
    viewModel: ExerciseDetailViewModel,
    onOpenSession: (Long) -> Unit,
    onOpenExercise: (Long) -> Unit,
    onOpenMuscle: (Muscle) -> Unit,
    onOpenTag: (String) -> Unit,
    onVariations: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (exercise.media.isNotEmpty()) {
            val pagerState = rememberPagerState { exercise.media.size }
            HorizontalPager(
                state = pagerState,
                pageSpacing = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
            ) { page ->
                val media = exercise.media[page]
                MediaThumbnail(
                    media = media,
                    resolveFile = viewModel::mediaFile,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { openMedia(context, media, viewModel) },
                )
            }
        }

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                exercise.categories.forEach { CategoryBadge(it) }
                exercise.level?.let { SuggestionChip(onClick = {}, label = { Text(levelText(it)) }) }
                SuggestionChip(onClick = {}, label = { Text(stringResource(exercise.measureMode.labelRes)) })
                if (exercise.unilateral) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.detail_unilateral)) },
                        icon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
            if (exercise.general) {
                Text(stringResource(R.string.detail_general), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (exercise.quickAdded) {
                Text(stringResource(R.string.detail_quick_added), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            }
            TagSection(exercise.tags, onOpenTag)
            MuscleSection(R.string.detail_muscles_primary, exercise.primaryMuscles, onOpenMuscle)
            MuscleSection(R.string.detail_muscles_secondary, exercise.secondaryMuscles, onOpenMuscle)
            if (exercise.primaryMuscles.isNotEmpty() || exercise.secondaryMuscles.isNotEmpty()) {
                BodyMap(
                    intensity = exercise.secondaryMuscles.associateWith { SECONDARY_SHADE } + exercise.primaryMuscles.associateWith { 1f },
                    description = bodyMapDescription(exercise.primaryMuscles + exercise.secondaryMuscles),
                    legend = listOfNotNull(
                        BodyMapKey(stringResource(R.string.body_legend_primary), 1f).takeIf { exercise.primaryMuscles.isNotEmpty() },
                        BodyMapKey(stringResource(R.string.body_legend_secondary), SECONDARY_SHADE).takeIf { exercise.secondaryMuscles.isNotEmpty() },
                    ),
                    maxWidth = 260.dp,
                    onMuscleClick = onOpenMuscle,
                )
            }
            if (exercise.equipment.isNotBlank()) {
                LabeledText(stringResource(R.string.detail_equipment), exercise.equipment)
            }
        }

        if (exercise.instructions.isNotEmpty()) {
            Section(stringResource(R.string.detail_instructions)) {
                exercise.instructions.forEachIndexed { index, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(26.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("${index + 1}", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        Text(step, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        if (exercise.cues.isNotEmpty()) {
            Section(stringResource(R.string.detail_cues)) {
                exercise.cues.forEach { cue ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp).size(6.dp),
                        ) {}
                        Text(cue, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        if (exercise.stages.isNotEmpty()) {
            Section(stringResource(R.string.detail_stages)) {
                exercise.stages.forEachIndexed { index, stage ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(28.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("${index + 1}", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stage.name, style = MaterialTheme.typography.titleSmall)
                                if (stage.description.isNotBlank()) {
                                    Text(stage.description, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            stage.durationSeconds?.let { seconds ->
                                AssistChip(
                                    onClick = {},
                                    label = { Text(stringResource(R.string.detail_stage_seconds, seconds)) },
                                    leadingIcon = { Icon(Icons.Outlined.Timer, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (exercise.notes.isNotBlank()) {
            Section(stringResource(R.string.detail_notes)) {
                Text(exercise.notes, style = MaterialTheme.typography.bodyLarge)
            }
        }

        if (family.isNotEmpty() || savedVariations > 0) {
            Section(stringResource(R.string.detail_family)) {
                if (family.isNotEmpty()) {
                    Text(
                        stringResource(R.string.detail_family_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    family.forEach { member ->
                        val isThis = member.id == exercise.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isThis) { onOpenExercise(member.id) }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                member.name + if (isThis) " (${stringResource(R.string.detail_family_this)})" else "",
                                style = if (isThis) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                                color = if (isThis) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                listOfNotNull(
                                    stringResource(R.string.detail_family_default).takeIf { member.id == familyBaseId },
                                    member.level?.let { levelText(it) } ?: stringResource(R.string.detail_family_level_unknown),
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (familyBaseId != null && familyBaseId != exercise.id) {
                        TextButton(onClick = onMakeDefault) { Text(stringResource(R.string.detail_family_make_default)) }
                    }
                }
                if (savedVariations > 0) {
                    TextButton(onClick = onVariations) {
                        Text(
                            stringResource(R.string.detail_open_variations) + " · " +
                                pluralStringResource(R.plurals.detail_saved_variations, savedVariations, savedVariations),
                        )
                    }
                }
            }
        }

        aiNotes.firstOrNull()?.let { note ->
            Section(stringResource(R.string.ai_note_title)) { AiNoteCard(note) }
        }

        if (history != null) {
            Section(stringResource(R.string.progress_title)) {
                ExerciseProgressSection(history, exercise.measureMode, onOpenSession)
            }
        }
    }
}

/** Says which details came from an AI, which model, when, and the pages it found. */
@Composable
private fun AiNoteCard(note: ExerciseAiNote) {
    val dates = rememberDateTexts()
    val fields = note.applied.mapNotNull { appliedLabelRes(it) }.map { stringResource(it) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(
                    R.string.ai_note_body,
                    fields.joinToString().ifEmpty { "–" },
                    note.model?.let { stringResource(it.labelRes) } ?: note.modelId,
                    dates.date(note.atMillis),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (note.sources.isEmpty()) {
                Text(
                    stringResource(R.string.ai_note_no_sources),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SourceList(note.sources)
            }
        }
    }
}

private fun appliedLabelRes(field: String): Int? = when (field) {
    "category" -> R.string.edit_category
    "measure" -> R.string.edit_measure
    "unilateral" -> R.string.edit_unilateral
    "equipment" -> R.string.edit_equipment
    "muscles" -> R.string.edit_muscles
    "instructions" -> R.string.edit_instructions
    "cues" -> R.string.edit_cues
    "stages" -> R.string.edit_stages
    "summary" -> R.string.edit_notes
    else -> null
}

/** How a helping muscle is shaded on the body map, against 1 for a main one. */
private const val SECONDARY_SHADE = 0.35f

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MuscleSection(titleRes: Int, muscles: Set<Muscle>, onOpenMuscle: (Muscle) -> Unit) {
    if (muscles.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(titleRes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            muscles.sortedBy { it.ordinal }.forEach { muscle ->
                // Opens every exercise that works this muscle.
                AssistChip(onClick = { onOpenMuscle(muscle) }, label = { Text(stringResource(muscle.labelRes)) })
            }
        }
    }
}

/** The exercise's tags, each with what it does for it; a tag opens all its exercises. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagSection(tags: List<com.yonizilber.flownics.domain.model.ExerciseTag>, onOpenTag: (String) -> Unit) {
    if (tags.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.edit_tags), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        tags.forEach { tag ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { onOpenTag(tag.name) }, label = { Text("# " + tag.name) })
                if (tag.note.isNotBlank()) Text(tag.note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LabeledText(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

private fun openMedia(context: Context, media: ExerciseMedia, viewModel: ExerciseDetailViewModel) {
    val intent = when (media.kind) {
        MediaKind.IMAGE -> return
        MediaKind.VIDEO -> Intent(Intent.ACTION_VIEW)
            .setDataAndType(viewModel.shareableUri(media.location), "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        MediaKind.LINK -> Intent(Intent.ACTION_VIEW, Uri.parse(media.location))
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.media_no_app, Toast.LENGTH_SHORT).show()
    }
}
