package com.yonizilber.flownics.feature.library.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.ai.AiModel
import com.yonizilber.flownics.domain.ai.AiResult
import com.yonizilber.flownics.domain.ai.Confidence
import com.yonizilber.flownics.domain.ai.ExerciseSuggestion
import com.yonizilber.flownics.domain.model.Muscle
import com.yonizilber.flownics.feature.ai.AiActionButton
import com.yonizilber.flownics.feature.ai.AiAnswerFooter
import com.yonizilber.flownics.feature.ai.AiCall
import com.yonizilber.flownics.feature.ai.AiErrorCard
import com.yonizilber.flownics.feature.ai.AiRunningCard
import com.yonizilber.flownics.feature.ai.aiLanguage
import com.yonizilber.flownics.ui.component.labelRes
import com.yonizilber.flownics.ui.component.levelText

/**
 * Asks the AI about the exercise being edited, then lets the user tick which suggested parts go
 * into the form. Unchanged parts are not offered; muscles replace, cues and stages are added.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiFillSheet(
    state: ExerciseEditorState,
    fill: AiCall<AiResult<ExerciseSuggestion>>,
    model: AiModel,
    onAsk: (hint: String, webSearch: Boolean, language: String) -> Unit,
    onApply: (Set<FillField>) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    val res = LocalResources.current
    var hint by rememberSaveable { mutableStateOf("") }
    var webSearch by rememberSaveable { mutableStateOf(true) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.ai_fill_title, state.name), style = MaterialTheme.typography.titleLarge)
            when (fill) {
                AiCall.Idle -> {
                    Text(stringResource(R.string.ai_fill_intro), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = hint,
                        onValueChange = { hint = it },
                        label = { Text(stringResource(R.string.ai_fill_hint)) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = webSearch, role = Role.Switch, onValueChange = { webSearch = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.ai_fill_search), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.ai_fill_search_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = webSearch, onCheckedChange = null)
                    }
                    AiActionButton(
                        text = stringResource(R.string.ai_fill_ask),
                        enabled = true,
                        onClick = { onAsk(hint, webSearch, res.aiLanguage()) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AiCall.Running -> AiRunningCard(model, webSearch, onCancel = onCancel)
                is AiCall.Failed -> AiErrorCard(fill.error, onRetry = { onAsk(hint, webSearch, res.aiLanguage()) }, onOpenSettings = onOpenAiSettings)
                is AiCall.Done -> Review(state, fill.value, onApply)
            }
        }
    }
}

@Composable
private fun Review(state: ExerciseEditorState, result: AiResult<ExerciseSuggestion>, onApply: (Set<FillField>) -> Unit) {
    val s = result.value
    val offers = remember(result) { offers(state, s) }
    var chosen by remember(result) { mutableStateOf(offers.filter { it.preselected }.map { it.field }.toSet()) }

    if (s.summary.isNotBlank()) Text(s.summary, style = MaterialTheme.typography.bodyMedium)
    s.confidence?.let {
        Text(
            stringResource(R.string.ai_fill_confidence, stringResource(confidenceRes(it))),
            style = MaterialTheme.typography.labelLarge,
            color = if (it == Confidence.LOW) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (s.caveats.isNotBlank()) Text(stringResource(R.string.ai_fill_caveats, s.caveats), style = MaterialTheme.typography.bodySmall)
    if (s.unknownValues.isNotEmpty()) {
        Text(
            stringResource(R.string.ai_fill_unknown, s.unknownValues.joinToString()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    HorizontalDivider()
    if (offers.isEmpty()) {
        Text(stringResource(R.string.ai_fill_nothing_new), style = MaterialTheme.typography.bodyMedium)
    }
    offers.forEach { offer ->
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = offer.field in chosen,
                    role = Role.Checkbox,
                    onValueChange = { on -> chosen = if (on) chosen + offer.field else chosen - offer.field },
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(checked = offer.field in chosen, onCheckedChange = null)
            Column(Modifier.padding(start = 8.dp)) {
                Text(offer.title(), style = MaterialTheme.typography.bodyLarge)
                offer.detail()?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    AiActionButton(
        text = stringResource(R.string.ai_fill_apply),
        enabled = chosen.isNotEmpty(),
        onClick = { onApply(chosen) },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(stringResource(R.string.ai_fill_apply_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    AiAnswerFooter(result.model, result.costUsd, result.sources)
}

/** One tickable part of the suggestion, with how it reads in the list. */
private class Offer(
    val field: FillField,
    val preselected: Boolean,
    val title: @Composable () -> String,
    val detail: @Composable () -> String? = { null },
)

private fun offers(state: ExerciseEditorState, s: ExerciseSuggestion): List<Offer> = buildList {
    s.category?.let { main -> (listOf(main) + s.extraCategories).distinct() }?.takeIf { it != state.categories }?.let { proposed ->
        add(Offer(FillField.CATEGORY, true, {
            stringResource(
                R.string.ai_fill_change,
                stringResource(R.string.edit_category),
                state.categories.map { stringResource(it.labelRes) }.joinToString(" + "),
                proposed.map { stringResource(it.labelRes) }.joinToString(" + "),
            )
        }))
    }
    s.measureMode?.takeIf { it != state.measureMode }?.let { proposed ->
        add(Offer(FillField.MEASURE, true, {
            stringResource(R.string.ai_fill_change, stringResource(R.string.edit_measure), stringResource(state.measureMode.labelRes), stringResource(proposed.labelRes))
        }))
    }
    if (s.unilateral != state.unilateral) {
        add(Offer(FillField.UNILATERAL, true, {
            stringResource(if (s.unilateral) R.string.ai_fill_unilateral_on else R.string.ai_fill_unilateral_off)
        }))
    }
    s.level?.takeIf { it != state.level }?.let { proposed ->
        add(Offer(FillField.LEVEL, state.level == null, { stringResource(R.string.ai_fill_level, levelText(proposed)) }))
    }
    if (s.equipment.isNotBlank() && s.equipment != state.equipment.trim()) {
        add(Offer(FillField.EQUIPMENT, state.equipment.isBlank(), { stringResource(R.string.ai_fill_equipment, s.equipment) }))
    }
    val musclesDiffer = s.primaryMuscles != state.muscles.primary || s.secondaryMuscles != state.muscles.secondary
    if (musclesDiffer && (s.primaryMuscles.isNotEmpty() || s.secondaryMuscles.isNotEmpty())) {
        add(
            Offer(
                FillField.MUSCLES,
                true,
                { stringResource(R.string.ai_fill_muscles) },
                {
                    val primary = muscleList(s.primaryMuscles)
                    val secondary = muscleList(s.secondaryMuscles)
                    stringResource(R.string.ai_fill_muscles_detail, primary.ifEmpty { "–" }, secondary.ifEmpty { "–" })
                },
            ),
        )
    }
    val currentSteps = state.instructions.map { it.text.trim() }.filter { it.isNotEmpty() }
    if (s.instructions.isNotEmpty() && s.instructions != currentSteps) {
        add(
            Offer(
                FillField.INSTRUCTIONS,
                currentSteps.isEmpty(),
                {
                    if (currentSteps.isEmpty()) {
                        pluralStringResource(R.plurals.ai_fill_instructions, s.instructions.size, s.instructions.size)
                    } else {
                        pluralStringResource(R.plurals.ai_fill_instructions_replace, s.instructions.size, s.instructions.size)
                    }
                },
                { s.instructions.mapIndexed { i, step -> "${i + 1}. $step" }.joinToString("\n") },
            ),
        )
    }
    val existingCues = state.cues.map { it.text.trim().lowercase() }.toSet()
    val newCues = s.cues.filter { it.lowercase() !in existingCues }
    if (newCues.isNotEmpty()) {
        add(Offer(FillField.CUES, true, { pluralStringResource(R.plurals.ai_fill_cues, newCues.size, newCues.size) }, { newCues.joinToString("\n") { "• $it" } }))
    }
    if (s.stages.isNotEmpty()) {
        add(Offer(FillField.STAGES, state.stages.isEmpty(), { pluralStringResource(R.plurals.ai_fill_stages, s.stages.size, s.stages.size) }, {
            s.stages.joinToString("\n") { "• " + it.name }
        }))
    }
    if (s.summary.isNotBlank()) {
        add(Offer(FillField.SUMMARY, state.notes.isBlank(), { stringResource(R.string.ai_fill_summary) }))
    }
}

@Composable
private fun muscleList(muscles: Set<Muscle>): String =
    muscles.sortedBy { it.ordinal }.map { stringResource(it.labelRes) }.joinToString()

private fun confidenceRes(c: Confidence) = when (c) {
    Confidence.HIGH -> R.string.ai_confidence_high
    Confidence.MEDIUM -> R.string.ai_confidence_medium
    Confidence.LOW -> R.string.ai_confidence_low
}
