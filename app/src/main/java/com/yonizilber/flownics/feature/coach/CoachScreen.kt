package com.yonizilber.flownics.feature.coach

import com.yonizilber.flownics.ui.component.formatDuration
import com.yonizilber.flownics.feature.journal.planTitle
import com.yonizilber.flownics.calendar.CalendarSync
import com.yonizilber.flownics.domain.model.PlannedStep
import com.yonizilber.flownics.domain.model.PlannedWorkout
import com.yonizilber.flownics.domain.coach.PlanChange
import com.yonizilber.flownics.feature.activity.labelRes
import androidx.compose.runtime.remember
import com.yonizilber.flownics.ui.component.labelRes
import com.yonizilber.flownics.feature.activity.summary
import com.yonizilber.flownics.feature.activity.icon
import com.yonizilber.flownics.feature.activity.displayTitle
import com.yonizilber.flownics.domain.model.ActivityFormat
import com.yonizilber.flownics.domain.model.Activity
import com.yonizilber.flownics.domain.coach.ProposalKind
import com.yonizilber.flownics.domain.coach.ExerciseField
import com.yonizilber.flownics.domain.coach.ExerciseChange
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.data.coach.CoachToolNames
import com.yonizilber.flownics.domain.ai.AiModel
import com.yonizilber.flownics.domain.ai.GeneratedWorkout
import com.yonizilber.flownics.domain.coach.CoachEntry
import com.yonizilber.flownics.domain.coach.CoachFormat
import com.yonizilber.flownics.domain.coach.CoachTopic
import com.yonizilber.flownics.domain.coach.ProposalState
import com.yonizilber.flownics.domain.model.BlockStructure
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.workout.WorkoutCompiler
import com.yonizilber.flownics.feature.ai.AiAnswerFooter
import com.yonizilber.flownics.feature.ai.AiErrorCard
import com.yonizilber.flownics.feature.ai.AiNotReadyCard
import com.yonizilber.flownics.feature.ai.AiRunningCard
import com.yonizilber.flownics.feature.ai.aiLanguage
import com.yonizilber.flownics.ui.component.rememberDateTexts
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The coach: one conversation at a time, kept on the device. Each step it takes shows as a line,
 * each workout it proposes as a card the user saves or declines, each note it keeps as a chip
 * that can be undone.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CoachScreen(
    onOpenAiSettings: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenWorkout: (Long) -> Unit,
    onOpenExercise: (Long) -> Unit,
    onOpenActivity: (Long) -> Unit,
    viewModel: CoachViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val aiReady by viewModel.aiReady.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val workoutNames by viewModel.workoutNames.collectAsStateWithLifecycle()
    val currentPlans by viewModel.currentPlans.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var showConversations by rememberSaveable { mutableStateOf(false) }
    val res = LocalResources.current
    val listState = rememberLazyListState()
    val send = { text: String ->
        viewModel.send(text, res.aiLanguage())
        input = ""
    }

    LaunchedEffect(entries.size, state.sending, state.failure) {
        val count = 1 + entries.size + (if (state.sending || state.failure != null) 1 else 0)
        listState.animateScrollToItem(count - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.coach_title)) },
                actions = {
                    IconButton(onClick = viewModel::newConversation, enabled = !state.sending) {
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.coach_new))
                    }
                    IconButton(onClick = { showConversations = true }) {
                        Icon(Icons.Outlined.History, contentDescription = stringResource(R.string.coach_conversations))
                    }
                    IconButton(onClick = onOpenMemory) {
                        Icon(Icons.Outlined.Psychology, contentDescription = stringResource(R.string.coach_memory))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize().imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "intro") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!aiReady) AiNotReadyCard(onOpenAiSettings)
                        if (entries.isEmpty()) {
                            Text(stringResource(R.string.coach_intro), style = MaterialTheme.typography.bodyMedium)
                            if (aiReady) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    starters(state.topic).forEach { id ->
                                        val text = stringResource(id)
                                        SuggestionChip(onClick = { send(text) }, label = { Text(text) })
                                    }
                                }
                            }
                        }
                    }
                }
                items(entries, key = { it.id }) { entry ->
                    when (entry) {
                        is CoachEntry.User -> Bubble(entry.text, fromUser = true)
                        is CoachEntry.Assistant -> Bubble(entry.text, fromUser = false, model = AiModel.byId(entry.modelId), costUsd = entry.costUsd)
                        is CoachEntry.Step -> StepLine(entry, library, workoutNames)
                        is CoachEntry.Proposal -> when (entry.kind) {
                            ProposalKind.WORKOUT -> ProposalCard(
                                proposal = entry,
                                workout = viewModel.workoutOf(entry, library),
                                library = library,
                                replacing = entry.replacesWorkoutId?.let { workoutNames[it] },
                                failed = state.proposalError == entry.id,
                                onSave = { replace -> viewModel.saveProposal(entry, replace) },
                                onDecline = { viewModel.declineProposal(entry) },
                                onOpen = onOpenWorkout,
                            )
                            ProposalKind.EXERCISE -> ExerciseChangeCard(
                                proposal = entry,
                                change = if (entry.state == ProposalState.PENDING) viewModel.exerciseChangeOf(entry, library) else null,
                                library = library,
                                failed = state.proposalError == entry.id,
                                onApply = { chosen -> viewModel.applyExerciseChange(entry, chosen) },
                                onDecline = { viewModel.declineProposal(entry) },
                                onOpen = onOpenExercise,
                            )
                            ProposalKind.PLAN -> PlanProposalCard(
                                proposal = entry,
                                change = if (entry.state == ProposalState.PENDING) viewModel.planChangeOf(entry, currentPlans, workoutNames.keys) else null,
                                workoutNames = workoutNames,
                                failed = state.proposalError == entry.id,
                                onApply = { chosen -> viewModel.applyPlanChange(entry, chosen) },
                                onDecline = { viewModel.declineProposal(entry) },
                            )
                            ProposalKind.ACTIVITY -> ActivityProposalCard(
                                proposal = entry,
                                activity = viewModel.activityOf(entry),
                                failed = state.proposalError == entry.id,
                                onSave = { viewModel.saveActivity(entry) },
                                onDecline = { viewModel.declineProposal(entry) },
                                onOpen = onOpenActivity,
                            )
                        }
                        is CoachEntry.Note -> NoteChip(entry, onUndo = { viewModel.undoNote(entry) })
                    }
                }
                if (state.sending) {
                    item(key = "sending") { AiRunningCard(viewModel.model, webSearch = false, onCancel = { viewModel.cancel()?.let { input = it } }) }
                }
                state.failure?.let { failure ->
                    item(key = "failure") {
                        when (failure) {
                            is CoachFailure.Ai -> AiErrorCard(failure.error, onRetry = { viewModel.retry(res.aiLanguage()) }, onOpenSettings = onOpenAiSettings)
                            CoachFailure.OutOfSteps -> OutOfStepsCard(onRetry = { viewModel.retry(res.aiLanguage()) }, onDismiss = viewModel::dismissFailure)
                        }
                    }
                }
            }
            if (aiReady) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.topic?.let { topic ->
                        AssistChip(
                            onClick = viewModel::clearTopic,
                            label = { Text(topicLabel(topic), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.coach_topic_clear), modifier = Modifier.size(16.dp)) },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text(stringResource(R.string.coach_hint)) },
                            maxLines = 5,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { send(input) }, enabled = input.isNotBlank() && !state.sending) {
                            Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = stringResource(R.string.coach_send))
                        }
                    }
                }
            }
        }
    }

    if (showConversations) {
        val conversations by viewModel.conversations.collectAsStateWithLifecycle()
        val dates = rememberDateTexts()
        ModalBottomSheet(onDismissRequest = { showConversations = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    stringResource(R.string.coach_conversations),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (conversations.isEmpty()) {
                    Text(stringResource(R.string.coach_conversations_none), modifier = Modifier.padding(16.dp))
                }
                LazyColumn {
                    items(conversations, key = { it.id }) { c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.openConversation(c.id)
                                    showConversations = false
                                }
                                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(c.title.ifBlank { stringResource(R.string.coach_untitled) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${dates.date(c.updatedAtMillis)} ${dates.time(c.updatedAtMillis)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { viewModel.deleteConversation(c.id) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.coach_delete_conversation))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Bubble(text: String, fromUser: Boolean, model: AiModel? = null, costUsd: Double = 0.0) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            ),
            modifier = Modifier.widthIn(max = 360.dp),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
                model?.let { AiAnswerFooter(it, costUsd, emptyList()) }
            }
        }
    }
}

/** One thing the coach looked at, in a short line, so the user sees what its answer rests on. */
@Composable
private fun StepLine(step: CoachEntry.Step, library: Map<Long, Exercise>, workoutNames: Map<Long, String>) {
    val a = step.args
    val text = when (step.tool) {
        CoachToolNames.TRAINING_LOG -> {
            val exercise = a.long("exercise_id")?.takeIf { it > 0 }?.let { library[it]?.name ?: "#$it" }
            if (exercise != null) {
                stringResource(R.string.coach_step_log_exercise, exercise, a.str("from_date"), a.str("to_date"))
            } else {
                stringResource(R.string.coach_step_log, a.str("from_date"), a.str("to_date"))
            }
        }
        CoachToolNames.MUSCLE_LOAD -> stringResource(R.string.coach_step_load, a.str("from_date"), a.str("to_date"))
        CoachToolNames.EXERCISES_SEARCH -> stringResource(
            R.string.coach_step_search,
            listOf(a.str("query"), a.str("muscle"), a.str("category")).filter { it.isNotBlank() && it != "ANY" }.joinToString(" · ").ifEmpty { "*" },
        )
        CoachToolNames.EXERCISE_DETAILS -> stringResource(R.string.coach_step_exercise, a.long("exercise_id")?.let { library[it]?.name ?: "#$it" }.orEmpty())
        CoachToolNames.WORKOUT_DETAILS -> stringResource(R.string.coach_step_workout, a.long("workout_id")?.let { workoutNames[it] ?: "#$it" }.orEmpty())
        else -> step.tool
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (step.failed) Icons.Outlined.ErrorOutline else Icons.Outlined.Search,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (step.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (step.failed) stringResource(R.string.coach_step_failed, text) else text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProposalCard(
    proposal: CoachEntry.Proposal,
    workout: GeneratedWorkout?,
    library: Map<Long, Exercise>,
    replacing: String?,
    failed: Boolean,
    onSave: (replace: Boolean) -> Unit,
    onDecline: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.coach_proposal_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (workout == null || workout.template.blocks.isEmpty()) {
                Text(stringResource(R.string.coach_proposal_unreadable), color = MaterialTheme.colorScheme.error)
                return@Column
            }
            val t = workout.template
            val minutes = (WorkoutCompiler.compile(t, library).estimatedSeconds + 59) / 60
            Text(t.name, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.coach_proposal_minutes, minutes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (t.description.isNotBlank()) Text(t.description, style = MaterialTheme.typography.bodyMedium)
            t.blocks.forEach { block ->
                HorizontalDivider()
                val shape = if (block.structure == BlockStructure.CIRCUIT) stringResource(R.string.coach_proposal_circuit, block.rounds) else ""
                Text(listOf(block.title, shape).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.titleSmall)
                block.items.forEach { item ->
                    val name = library[item.exerciseId]?.name ?: "#${item.exerciseId}"
                    val sets = if (block.structure == BlockStructure.STRAIGHT_SETS) "${item.sets} × " else ""
                    Text(
                        "• $name: $sets${CoachFormat.describe(item.target)} · " + stringResource(R.string.coach_proposal_rest, item.restSeconds),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (workout.explanation.isNotBlank()) {
                HorizontalDivider()
                Text(workout.explanation, style = MaterialTheme.typography.bodyMedium)
            }
            if (workout.missingExercises.isNotEmpty()) {
                Text(
                    stringResource(R.string.coach_proposal_missing, workout.missingExercises.joinToString { it.name }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (workout.skippedItems > 0) {
                Text(stringResource(R.string.coach_proposal_skipped, workout.skippedItems), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (failed) Text(stringResource(R.string.coach_proposal_save_failed), color = MaterialTheme.colorScheme.error)
            when (proposal.state) {
                ProposalState.PENDING -> FlowRowButtons {
                    if (replacing != null) {
                        Button(onClick = { onSave(true) }) { Text(stringResource(R.string.coach_proposal_replace, replacing)) }
                        OutlinedButton(onClick = { onSave(false) }) { Text(stringResource(R.string.coach_proposal_save_new)) }
                    } else {
                        Button(onClick = { onSave(false) }) { Text(stringResource(R.string.coach_proposal_save)) }
                    }
                    TextButton(onClick = onDecline) { Text(stringResource(R.string.coach_proposal_decline)) }
                }
                ProposalState.SAVED -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.coach_proposal_saved), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    proposal.savedId?.let { id -> OutlinedButton(onClick = { onOpen(id) }) { Text(stringResource(R.string.coach_proposal_open)) } }
                }
                ProposalState.DECLINED -> Text(stringResource(R.string.coach_proposal_declined), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Each proposed change to an exercise, before and after, each one ticked to apply or not. */
@Composable
private fun ExerciseChangeCard(
    proposal: CoachEntry.Proposal,
    change: ExerciseChange?,
    library: Map<Long, Exercise>,
    failed: Boolean,
    onApply: (Set<ExerciseField>) -> Unit,
    onDecline: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (proposal.state) {
                ProposalState.SAVED -> {
                    Text(stringResource(R.string.coach_exercise_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    val name = proposal.savedId?.let { library[it]?.name }.orEmpty()
                    Text(
                        if (proposal.applied.isEmpty()) stringResource(R.string.coach_exercise_added, name)
                        else stringResource(R.string.coach_exercise_applied, name, proposal.applied.mapNotNull { n -> ExerciseField.entries.firstOrNull { it.name == n } }.map { stringResource(it.labelRes) }.joinToString()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    proposal.savedId?.let { id -> OutlinedButton(onClick = { onOpen(id) }) { Text(stringResource(R.string.coach_proposal_open)) } }
                    return@Column
                }
                ProposalState.DECLINED -> {
                    Text(stringResource(R.string.coach_exercise_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.coach_proposal_declined), style = MaterialTheme.typography.bodyMedium)
                    return@Column
                }
                ProposalState.PENDING -> Unit
            }
            if (change == null) {
                Text(stringResource(R.string.coach_exercise_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.coach_exercise_stale), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onDecline) { Text(stringResource(R.string.coach_proposal_decline)) }
                return@Column
            }
            val current = change.current
            Text(
                stringResource(if (current == null) R.string.coach_exercise_new_label else R.string.coach_exercise_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(current?.name ?: change.proposed.name, style = MaterialTheme.typography.titleMedium)
            if (change.reason.isNotBlank()) Text(change.reason, style = MaterialTheme.typography.bodyMedium)
            var chosen by rememberSaveable(proposal.id) { mutableStateOf(change.fields.map { it.name }.toSet()) }
            if (current == null) {
                ExerciseField.entries.forEach { f ->
                    fieldValue(f, change.proposed, library)?.takeIf { it.isNotBlank() }?.let { v ->
                        Text(stringResource(f.labelRes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                change.fields.forEach { f ->
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.Top) {
                        Checkbox(checked = f.name in chosen, onCheckedChange = { on -> chosen = if (on) chosen + f.name else chosen - f.name })
                        Column(Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(f.labelRes), style = MaterialTheme.typography.titleSmall)
                            FieldDiff(f, current, change.proposed, library)
                        }
                    }
                }
            }
            if (failed) Text(stringResource(R.string.coach_exercise_failed), color = MaterialTheme.colorScheme.error)
            FlowRowButtons {
                Button(
                    onClick = { onApply(chosen.mapNotNull { n -> ExerciseField.entries.firstOrNull { it.name == n } }.toSet()) },
                    enabled = current == null || chosen.isNotEmpty(),
                ) { Text(stringResource(if (current == null) R.string.coach_exercise_add else R.string.coach_exercise_apply)) }
                TextButton(onClick = onDecline) { Text(stringResource(R.string.coach_proposal_decline)) }
            }
        }
    }
}

/** A list field shows what is added and what goes; anything else shows before → after. */
@Composable
private fun FieldDiff(f: ExerciseField, before: Exercise, after: Exercise, library: Map<Long, Exercise>) {
    val lists = when (f) {
        ExerciseField.CUES -> before.cues to after.cues
        ExerciseField.INSTRUCTIONS -> before.instructions to after.instructions
        else -> null
    }
    if (lists != null) {
        val (old, new) = lists
        val added = new.filter { it !in old }
        val removed = old.filter { it !in new }
        added.forEach { Text("+ $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        removed.forEach { Text("− $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        if (added.isEmpty() && removed.isEmpty()) Text(stringResource(R.string.coach_exercise_reordered), style = MaterialTheme.typography.bodySmall)
        return
    }
    val none = stringResource(R.string.coach_exercise_none)
    Text(
        "${fieldValue(f, before, library)?.ifBlank { none } ?: none} → ${fieldValue(f, after, library)?.ifBlank { none } ?: none}",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun fieldValue(f: ExerciseField, e: Exercise, library: Map<Long, Exercise>): String? = when (f) {
    ExerciseField.NAME -> e.name
    ExerciseField.CATEGORIES -> e.categories.map { stringResource(it.labelRes) }.joinToString(" + ")
    ExerciseField.MUSCLES -> listOf(
        e.primaryMuscles.sortedBy { it.ordinal }.map { stringResource(it.labelRes) }.joinToString(),
        e.secondaryMuscles.sortedBy { it.ordinal }.map { stringResource(it.labelRes) }.joinToString().takeIf { it.isNotBlank() }?.let { "($it)" },
    ).filterNotNull().joinToString(" ")
    ExerciseField.EQUIPMENT -> e.equipment
    ExerciseField.INSTRUCTIONS -> e.instructions.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
    ExerciseField.CUES -> e.cues.joinToString("\n") { "• $it" }
    ExerciseField.NOTES -> e.notes
    ExerciseField.LEVEL -> e.level?.let { stringResource(R.string.coach_exercise_level, it) }
    ExerciseField.MEASURE_MODE -> stringResource(e.measureMode.labelRes)
    ExerciseField.UNILATERAL -> stringResource(if (e.unilateral) R.string.coach_yes else R.string.coach_no)
    ExerciseField.STYLE_OF -> e.variationOf?.let { library[it]?.name }
}

@Composable
private fun ActivityProposalCard(
    proposal: CoachEntry.Proposal,
    activity: Activity?,
    failed: Boolean,
    onSave: () -> Unit,
    onDecline: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.coach_activity_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (activity == null) {
                Text(stringResource(R.string.coach_proposal_unreadable_activity), color = MaterialTheme.colorScheme.error)
                return@Column
            }
            val dates = rememberDateTexts()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(activity.type.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(activity.displayTitle(), style = MaterialTheme.typography.titleMedium)
            }
            Text("${dates.fullDate(activity.startedAtMillis)} · ${dates.time(activity.startedAtMillis)}", style = MaterialTheme.typography.bodySmall)
            Text(activity.summary(), style = MaterialTheme.typography.bodyMedium)
            activity.rpe?.let { Text(stringResource(R.string.coach_activity_rpe, it), style = MaterialTheme.typography.bodySmall) }
            activity.segments.forEachIndexed { i, s ->
                Text(
                    "${i + 1}. " + stringResource(s.kind.labelRes) + ": " + listOfNotNull(
                        s.distanceMeters?.let { stringResource(R.string.activity_km, ActivityFormat.formatKm(it)) },
                        s.durationSeconds?.let { ActivityFormat.formatDuration(it) },
                        s.paceSecondsPerKm?.let { stringResource(R.string.activity_pace, ActivityFormat.formatPace(it)) },
                        s.avgHeartRate?.let { stringResource(R.string.activity_hr, it) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (activity.notes.isNotBlank()) Text(activity.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (failed) Text(stringResource(R.string.coach_proposal_unreadable_activity), color = MaterialTheme.colorScheme.error)
            when (proposal.state) {
                ProposalState.PENDING -> FlowRowButtons {
                    Button(onClick = onSave) { Text(stringResource(R.string.coach_activity_save)) }
                    TextButton(onClick = onDecline) { Text(stringResource(R.string.coach_proposal_decline)) }
                }
                ProposalState.SAVED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.coach_activity_saved), modifier = Modifier.weight(1f))
                    proposal.savedId?.let { id -> OutlinedButton(onClick = { onOpen(id) }) { Text(stringResource(R.string.coach_proposal_open)) } }
                }
                ProposalState.DECLINED -> Text(stringResource(R.string.coach_proposal_declined))
            }
        }
    }
}

/** Each proposed change to the plan as a row to tick: what to add, move or drop. */
@Composable
private fun PlanProposalCard(
    proposal: CoachEntry.Proposal,
    change: PlanChange?,
    workoutNames: Map<Long, String>,
    failed: Boolean,
    onApply: (Set<String>) -> Unit,
    onDecline: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.coach_plan_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            when (proposal.state) {
                ProposalState.SAVED -> {
                    Text(stringResource(R.string.coach_plan_applied, proposal.applied.size), style = MaterialTheme.typography.bodyMedium)
                    return@Column
                }
                ProposalState.DECLINED -> {
                    Text(stringResource(R.string.coach_proposal_declined), style = MaterialTheme.typography.bodyMedium)
                    return@Column
                }
                ProposalState.PENDING -> Unit
            }
            if (change == null) {
                Text(stringResource(R.string.coach_plan_stale), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onDecline) { Text(stringResource(R.string.coach_proposal_decline)) }
                return@Column
            }
            if (change.reason.isNotBlank()) Text(change.reason, style = MaterialTheme.typography.bodyMedium)
            var chosen by rememberSaveable(proposal.id) { mutableStateOf(change.keys.toSet()) }
            val dateFormat = remember { java.time.format.DateTimeFormatter.ofPattern("EEE d.M") }
            @Composable
            fun ChangeRow(key: String, sign: String, title: String, detail: String) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(checked = key in chosen, onCheckedChange = { on -> chosen = if (on) chosen + key else chosen - key })
                    Column(Modifier.weight(1f).padding(top = 12.dp)) {
                        Text("$sign $title", style = MaterialTheme.typography.titleSmall)
                        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            fun whenText(p: PlannedWorkout) = p.date.format(dateFormat) + (p.time?.let { " $it" } ?: "")
            change.adds.forEachIndexed { i, p ->
                ChangeRow(
                    "add:$i",
                    "+",
                    whenText(p) + " · " + planTitle(p, workoutNames),
                    (listOfNotNull(p.durationMinutes?.let { formatDuration(it * 60) }, p.notes.takeIf { it.isNotBlank() }) +
                        p.steps.mapIndexed { j, s -> "${j + 1}. " + stepLine(s) }).joinToString("\n"),
                )
            }
            change.moves.forEach { (old, new) ->
                ChangeRow("move:${old.id}", "↻", planTitle(old, workoutNames), stringResource(R.string.coach_plan_move, whenText(old), whenText(new)))
            }
            change.removes.forEach { p -> ChangeRow("remove:${p.id}", "−", whenText(p) + " · " + planTitle(p, workoutNames), stringResource(R.string.coach_plan_remove)) }
            if (failed) Text(stringResource(R.string.coach_plan_stale), color = MaterialTheme.colorScheme.error)
            FlowRowButtons {
                Button(onClick = { onApply(chosen) }, enabled = chosen.isNotEmpty()) { Text(stringResource(R.string.coach_plan_accept)) }
                TextButton(onClick = onDecline) { Text(stringResource(R.string.coach_proposal_decline)) }
            }
        }
    }
}

@Composable
private fun stepLine(s: PlannedStep): String = listOfNotNull(
    stringResource(s.kind.labelRes),
    s.distanceMeters?.let { stringResource(R.string.activity_km, ActivityFormat.formatKm(it)) },
    s.durationSeconds?.let { ActivityFormat.formatDuration(it) },
    CalendarSync.paceRange(s)?.let { stringResource(R.string.activity_pace, it) },
    s.maxHeartRate?.let { stringResource(R.string.plan_hr_max, it) },
).joinToString(" · ")

private val ExerciseField.labelRes: Int
    get() = when (this) {
        ExerciseField.NAME -> R.string.coach_field_name
        ExerciseField.CATEGORIES -> R.string.edit_category
        ExerciseField.MUSCLES -> R.string.coach_field_muscles
        ExerciseField.EQUIPMENT -> R.string.coach_field_equipment
        ExerciseField.INSTRUCTIONS -> R.string.coach_field_instructions
        ExerciseField.CUES -> R.string.coach_field_cues
        ExerciseField.NOTES -> R.string.coach_field_notes
        ExerciseField.LEVEL -> R.string.coach_field_level
        ExerciseField.MEASURE_MODE -> R.string.edit_measure
        ExerciseField.UNILATERAL -> R.string.edit_unilateral
        ExerciseField.STYLE_OF -> R.string.coach_field_style_of
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowButtons(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
private fun NoteChip(note: CoachEntry.Note, onUndo: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Outlined.Psychology, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            when {
                note.undone -> stringResource(R.string.coach_note_undone, note.text)
                note.removed -> stringResource(R.string.coach_note_removed, note.text)
                else -> stringResource(R.string.coach_note_saved, note.text)
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        if (!note.undone) TextButton(onClick = onUndo) { Text(stringResource(R.string.coach_note_undo)) }
    }
}

@Composable
private fun OutOfStepsCard(onRetry: () -> Unit, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.coach_out_of_steps), color = MaterialTheme.colorScheme.onErrorContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry) { Text(stringResource(R.string.ai_retry)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        }
    }
}

@Composable
private fun topicLabel(topic: CoachTopic): String = when (topic) {
    is CoachTopic.Exercise -> stringResource(R.string.coach_topic, topic.name)
    is CoachTopic.Workout -> stringResource(R.string.coach_topic, topic.name)
    is CoachTopic.Session -> stringResource(R.string.coach_topic, topic.name)
    CoachTopic.History -> stringResource(R.string.coach_topic_history)
}

private fun starters(topic: CoachTopic?) = when (topic) {
    null -> listOf(R.string.coach_starter_1, R.string.coach_starter_2, R.string.coach_starter_3)
    is CoachTopic.Exercise -> listOf(R.string.ai_starter_exercise_1, R.string.ai_starter_exercise_2, R.string.ai_starter_exercise_3)
    is CoachTopic.Workout -> listOf(R.string.ai_starter_workout_1, R.string.ai_starter_workout_2, R.string.ai_starter_workout_3)
    is CoachTopic.Session -> listOf(R.string.coach_starter_session_1, R.string.coach_starter_session_2)
    CoachTopic.History -> listOf(R.string.ai_starter_history_1, R.string.ai_starter_history_2, R.string.ai_starter_history_3)
}

private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
