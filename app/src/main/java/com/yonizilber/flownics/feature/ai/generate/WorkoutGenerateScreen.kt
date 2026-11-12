package com.yonizilber.flownics.feature.ai.generate

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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.ai.AiResult
import com.yonizilber.flownics.domain.ai.GeneratedWorkout
import com.yonizilber.flownics.domain.model.BlockStructure
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.workout.WorkoutCompiler
import com.yonizilber.flownics.feature.ai.AiActionButton
import com.yonizilber.flownics.feature.ai.AiAnswerFooter
import com.yonizilber.flownics.feature.ai.AiCall
import com.yonizilber.flownics.feature.ai.AiErrorCard
import com.yonizilber.flownics.feature.ai.AiNotReadyCard
import com.yonizilber.flownics.feature.ai.AiRunningCard
import com.yonizilber.flownics.feature.ai.aiLanguage
import com.yonizilber.flownics.feature.ai.review.WorkoutLoad
import com.yonizilber.flownics.ui.component.NumberStepper
import com.yonizilber.flownics.ui.component.blockLabel
import com.yonizilber.flownics.ui.component.formatDuration
import com.yonizilber.flownics.ui.component.formatTarget

private val VARIATION_PRESETS = listOf(
    R.string.ai_preset_shorter,
    R.string.ai_preset_harder,
    R.string.ai_preset_easier,
    R.string.ai_preset_no_equipment,
    R.string.ai_preset_other_emphasis,
)

private val DRAFT_PRESETS = listOf(
    R.string.ai_preset_full_body,
    R.string.ai_preset_upper,
    R.string.ai_preset_lower,
    R.string.ai_preset_core,
    R.string.ai_preset_mobility,
    R.string.ai_preset_strength,
    R.string.ai_preset_cardio,
    R.string.ai_preset_no_equipment,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorkoutGenerateScreen(
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    onOpenAiSettings: () -> Unit,
    viewModel: WorkoutGenerateViewModel = hiltViewModel(),
) {
    val source by viewModel.source.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val call by viewModel.call.collectAsStateWithLifecycle()
    val aiReady by viewModel.aiReady.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val res = LocalResources.current

    var presets by rememberSaveable { mutableStateOf(listOf<Int>()) }
    var details by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableIntStateOf(45) }
    val presetIds = if (viewModel.isVariation) VARIATION_PRESETS else DRAFT_PRESETS
    val requestText = (presets.map { res.getString(it) } + details.trim()).filter { it.isNotBlank() }.joinToString(". ")
    val generate = { viewModel.generate(requestText, minutes, res.aiLanguage()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (viewModel.isVariation) R.string.ai_generate_variation_title else R.string.ai_generate_draft_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        val all = library
        if (all == null || (viewModel.isVariation && source == WorkoutLoad.Loading)) {
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
            val sourceName = (source as? WorkoutLoad.Loaded)?.context?.template?.name
            when {
                viewModel.isVariation && sourceName == null -> {
                    Text(stringResource(R.string.ai_workout_gone))
                    return@Column
                }
                all.isEmpty() -> {
                    Text(stringResource(R.string.ai_generate_needs_library))
                    return@Column
                }
            }
            Text(
                if (sourceName != null) stringResource(R.string.ai_generate_variation_intro, sourceName) else stringResource(R.string.ai_generate_draft_intro),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!aiReady) {
                AiNotReadyCard(onOpenAiSettings)
                return@Column
            }

            when (val c = call) {
                AiCall.Idle, is AiCall.Failed -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        presetIds.forEach { id ->
                            FilterChip(
                                selected = id in presets,
                                onClick = { presets = if (id in presets) presets - id else presets + id },
                                label = { Text(stringResource(id)) },
                            )
                        }
                    }
                    if (!viewModel.isVariation) {
                        NumberStepper(
                            label = stringResource(R.string.ai_generate_minutes),
                            value = minutes,
                            onValueChange = { minutes = it },
                            range = 10..120,
                            step = 5,
                        )
                    }
                    OutlinedTextField(
                        value = details,
                        onValueChange = { details = it },
                        label = { Text(stringResource(R.string.ai_generate_details)) },
                        placeholder = { Text(stringResource(if (viewModel.isVariation) R.string.ai_generate_variation_hint else R.string.ai_generate_draft_hint)) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (c is AiCall.Failed) AiErrorCard(c.error, onRetry = generate, onOpenSettings = onOpenAiSettings)
                    AiActionButton(
                        stringResource(R.string.ai_generate_ask),
                        // A variation needs to know what to change; a draft can go from the length alone.
                        enabled = !viewModel.isVariation || requestText.isNotBlank(),
                        onClick = generate,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.ai_generate_library_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AiCall.Running -> AiRunningCard(viewModel.model, webSearch = false, onCancel = viewModel::reset)
                is AiCall.Done -> {
                    val fallbackName = stringResource(R.string.ai_generate_fallback_name)
                    Preview(c.value, all.associateBy { it.id })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.save(fallbackName, onSaved) },
                            enabled = c.value.value.itemCount > 0 && !saving,
                        ) { Text(stringResource(R.string.ai_generate_save)) }
                        OutlinedButton(onClick = viewModel::reset) { Text(stringResource(R.string.ai_generate_change)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Preview(result: AiResult<GeneratedWorkout>, library: Map<Long, Exercise>) {
    val generated = result.value
    val template = generated.template
    val estimate = WorkoutCompiler.compile(template, library).estimatedSeconds
    Text(template.name, style = MaterialTheme.typography.headlineSmall)
    if (template.description.isNotBlank()) Text(template.description, style = MaterialTheme.typography.bodyMedium)
    Text(
        stringResource(R.string.ai_generate_estimate, formatDuration(estimate)),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    if (generated.skippedItems > 0) {
        Text(
            LocalResources.current.getQuantityString(R.plurals.ai_generate_skipped, generated.skippedItems, generated.skippedItems),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (generated.itemCount == 0) {
        Text(stringResource(R.string.ai_generate_empty), color = MaterialTheme.colorScheme.error)
    }
    template.blocks.forEachIndexed { index, block ->
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(blockLabel(block, index), style = MaterialTheme.typography.titleMedium)
                if (block.structure == BlockStructure.CIRCUIT) {
                    Text(
                        stringResource(R.string.ai_generate_circuit, block.rounds, block.roundRestSeconds),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                block.items.forEach { item ->
                    val exercise = library[item.exerciseId]
                    val sets = if (block.structure == BlockStructure.STRAIGHT_SETS) "${item.sets} × " else ""
                    Text(
                        stringResource(R.string.ai_generate_item, exercise?.name.orEmpty(), sets + formatTarget(item.target, exercise), item.restSeconds),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
    if (generated.explanation.isNotBlank()) {
        Text(stringResource(R.string.ai_generate_why), style = MaterialTheme.typography.titleSmall)
        Text(generated.explanation, style = MaterialTheme.typography.bodyMedium)
    }
    if (generated.missingExercises.isNotEmpty()) {
        Text(stringResource(R.string.ai_generate_missing), style = MaterialTheme.typography.titleSmall)
        generated.missingExercises.forEach { Text("• ${it.name}: ${it.why}", style = MaterialTheme.typography.bodyMedium) }
    }
    AiAnswerFooter(result.model, result.costUsd, result.sources)
}
