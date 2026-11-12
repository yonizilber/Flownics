package com.yonizilber.flownics.feature.ai.variations

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.ai.VariationKind
import com.yonizilber.flownics.domain.repository.SavedVariation
import com.yonizilber.flownics.feature.ai.AiActionButton
import com.yonizilber.flownics.feature.ai.AiAnswerFooter
import com.yonizilber.flownics.feature.ai.AiCall
import com.yonizilber.flownics.feature.ai.AiErrorCard
import com.yonizilber.flownics.feature.ai.AiNotReadyCard
import com.yonizilber.flownics.feature.ai.AiRunningCard
import com.yonizilber.flownics.feature.ai.aiLanguage
import com.yonizilber.flownics.ui.component.levelText

private val PRESETS = listOf(
    R.string.ai_variation_preset_easier,
    R.string.ai_variation_preset_harder,
    R.string.ai_variation_preset_alternative,
    R.string.ai_preset_no_equipment,
    R.string.ai_variation_preset_home,
)

/**
 * The variations kept under one exercise, and a way to ask for more in the user's own words. A
 * variation becomes a library exercise only when the user adds it ([onAddToLibrary] opens the
 * editor pre-filled); until then it lives here.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExerciseVariationsScreen(
    onBack: () -> Unit,
    onAddToLibrary: (SavedVariation) -> Unit,
    onOpenExercise: (Long) -> Unit,
    onOpenAiSettings: () -> Unit,
    viewModel: ExerciseVariationsViewModel = hiltViewModel(),
) {
    val exercise by viewModel.exercise.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val call by viewModel.call.collectAsStateWithLifecycle()
    val aiReady by viewModel.aiReady.collectAsStateWithLifecycle()
    val res = LocalResources.current
    var presets by rememberSaveable { mutableStateOf(listOf<Int>()) }
    var details by rememberSaveable { mutableStateOf("") }
    val request = (presets.map { res.getString(it) } + details.trim()).filter { it.isNotBlank() }.joinToString(". ")
    val ask = { viewModel.ask(request, res.aiLanguage()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_variations_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        val target = exercise
        val all = library
        val kept = saved
        if (all == null || kept == null) {
            Box(Modifier.padding(innerPadding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (target == null) {
                Text(stringResource(R.string.ai_exercise_gone), style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            Text(
                stringResource(R.string.ai_variations_intro, target.name) +
                    (target.level?.let { " " + stringResource(R.string.ai_variations_base_level, levelText(it)) } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )

            val inLibrary = all.map { it.id }.toSet()
            if (kept.isEmpty()) {
                Text(stringResource(R.string.ai_variations_none_saved), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Easiest first, so the list reads as a progression.
            kept.sortedWith(compareBy<SavedVariation> { it.variation.level ?: Int.MAX_VALUE }.thenBy { it.atMillis })
                .forEach { v ->
                    VariationCard(
                        saved = v,
                        addedExists = v.addedExerciseId != null && v.addedExerciseId in inLibrary,
                        onAdd = { onAddToLibrary(v) },
                        onOpen = { v.addedExerciseId?.let(onOpenExercise) },
                        onDelete = { viewModel.delete(v) },
                    )
                }

            Text(stringResource(R.string.ai_variations_ask_more), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            if (!aiReady) {
                AiNotReadyCard(onOpenAiSettings)
                return@Column
            }
            when (val c = call) {
                AiCall.Running -> AiRunningCard(viewModel.model, webSearch = false, onCancel = viewModel::cancel)
                else -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PRESETS.forEach { id ->
                            FilterChip(
                                selected = id in presets,
                                onClick = { presets = if (id in presets) presets - id else presets + id },
                                label = { Text(stringResource(id)) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = details,
                        onValueChange = { details = it },
                        label = { Text(stringResource(R.string.ai_generate_details)) },
                        placeholder = { Text(stringResource(R.string.ai_variations_request_hint)) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (c is AiCall.Failed) AiErrorCard(c.error, onRetry = ask, onOpenSettings = onOpenAiSettings)
                    if (c is AiCall.Done) AnswerSummary(c.value, target.level, onSetLevel = viewModel::setLevel)
                    AiActionButton(
                        stringResource(if (kept.isEmpty()) R.string.ai_variations_ask else R.string.ai_variations_ask_again),
                        enabled = true,
                        onClick = ask,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun AnswerSummary(answer: VariationsAnswer, currentLevel: Int?, onSetLevel: (Int) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (answer.newCount == 0) stringResource(R.string.ai_variations_nothing_new)
                else pluralStringResource(R.plurals.ai_variations_saved, answer.newCount, answer.newCount),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (answer.notes.isNotBlank()) Text(answer.notes, style = MaterialTheme.typography.bodySmall)
            answer.baseLevel?.takeIf { it != currentLevel }?.let { level ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.ai_variations_base_estimate, levelText(level)),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onSetLevel(level) }) { Text(stringResource(R.string.ai_variations_set_level)) }
                }
            }
            AiAnswerFooter(answer.model, answer.costUsd, sources = emptyList())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VariationCard(saved: SavedVariation, addedExists: Boolean, onAdd: () -> Unit, onOpen: () -> Unit, onDelete: () -> Unit) {
    val variation = saved.variation
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SuggestionChip(onClick = {}, label = { Text(stringResource(kindRes(variation.kind))) })
                    variation.level?.let { SuggestionChip(onClick = {}, label = { Text(levelText(it)) }) }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.ai_variation_delete))
                }
            }
            Text(variation.name, style = MaterialTheme.typography.titleMedium)
            if (variation.difference.isNotBlank()) Text(variation.difference, style = MaterialTheme.typography.bodyMedium)
            if (variation.why.isNotBlank()) {
                Text(variation.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (variation.equipment.isNotBlank()) {
                Text(
                    stringResource(R.string.ai_variation_equipment, variation.equipment),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (addedExists) {
                AssistChip(
                    onClick = onOpen,
                    label = { Text(stringResource(R.string.ai_variation_open)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                )
            } else {
                AssistChip(
                    onClick = onAdd,
                    label = { Text(stringResource(R.string.ai_variation_add)) },
                    leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                )
            }
        }
    }
}

private fun kindRes(kind: VariationKind) = when (kind) {
    VariationKind.EASIER -> R.string.ai_variation_easier
    VariationKind.HARDER -> R.string.ai_variation_harder
    VariationKind.ALTERNATIVE -> R.string.ai_variation_alternative
}
