package com.yonizilber.flownics.feature.workouts.edit

import com.yonizilber.flownics.domain.model.RestFiller
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yonizilber.flownics.domain.library.move
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.MeasureMode
import com.yonizilber.flownics.domain.model.SetTarget
import com.yonizilber.flownics.domain.model.WorkoutBlock
import com.yonizilber.flownics.domain.model.WorkoutItem
import com.yonizilber.flownics.domain.model.WorkoutTemplate
import com.yonizilber.flownics.domain.repository.ExerciseRepository
import com.yonizilber.flownics.domain.repository.WorkoutRepository
import com.yonizilber.flownics.domain.workout.CompiledWorkout
import com.yonizilber.flownics.domain.workout.WorkoutCompiler
import com.yonizilber.flownics.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Stable keys let Compose keep each card's state (expanded, focus) across reorders. */
data class ItemDraft(val key: Long, val item: WorkoutItem)

/** [block] carries the block's own settings; its items live in [items]. */
data class BlockDraft(val key: Long, val block: WorkoutBlock, val items: List<ItemDraft>)

data class WorkoutDraft(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val name: String = "",
    val showNameError: Boolean = false,
    val description: String = "",
    val blocks: List<BlockDraft> = emptyList(),
    val restFiller: RestFiller? = null,
    val expandedItemKey: Long? = null,
    val saving: Boolean = false,
    val dirty: Boolean = false,
)

data class WorkoutEditorUiState(
    val draft: WorkoutDraft = WorkoutDraft(),
    val library: Map<Long, Exercise> = emptyMap(),
    /** The draft compiled live, so the estimate and warnings update as the user edits. */
    val compiled: CompiledWorkout? = null,
    /** Workouts that can fill this one's rests. */
    val otherWorkouts: List<WorkoutTemplate> = emptyList(),
) {
    val blocks: List<WorkoutBlock> get() = draft.blocks.map { it.toBlock() }
}

private fun BlockDraft.toBlock() = block.copy(items = items.map { it.item })

@HiltViewModel
class WorkoutEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val workoutRepository: WorkoutRepository,
    exerciseRepository: ExerciseRepository,
) : ViewModel() {

    private val workoutId: Long = savedStateHandle.get<Long>(Destination.ARG_WORKOUT_ID) ?: 0L
    private var original: WorkoutTemplate? = null
    private var nextKey = 0L
    private var started = false

    private val draft = MutableStateFlow(WorkoutDraft())

    private val _saved = Channel<Long>(Channel.BUFFERED)
    val saved: Flow<Long> = _saved.receiveAsFlow()

    val uiState: StateFlow<WorkoutEditorUiState> =
        combine(draft, exerciseRepository.observeAll(), workoutRepository.observeAll()) { d, exercises, all ->
            val library = exercises.associateBy { it.id }
            val template = d.toTemplate()
            WorkoutEditorUiState(
                d,
                library,
                if (d.loading) null else WorkoutCompiler.compile(template, library, all.associateBy { it.id }),
                otherWorkouts = all.filter { it.id != workoutId }.sortedBy { it.name.lowercase() },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutEditorUiState())

    /** Called once with the localized default block title for a brand-new workout. */
    fun start(defaultBlockTitle: String) {
        // The screen calls this on every (re)composition entry, e.g. after rotation; load only once.
        if (started) return
        started = true
        viewModelScope.launch {
            val existing = if (workoutId != 0L) workoutRepository.get(workoutId) else null
            original = existing
            draft.value = if (existing == null) {
                WorkoutDraft(loading = false, blocks = listOf(BlockDraft(newKey(), WorkoutBlock(title = defaultBlockTitle), emptyList())))
            } else {
                WorkoutDraft(
                    loading = false,
                    isNew = false,
                    name = existing.name,
                    description = existing.description,
                    restFiller = existing.restFiller,
                    blocks = existing.blocks.map { block ->
                        BlockDraft(newKey(), block.copy(items = emptyList()), block.items.map { ItemDraft(newKey(), it) })
                    },
                )
            }
        }
    }

    fun onNameChange(value: String) = edit { it.copy(name = value, showNameError = false) }
    fun onDescriptionChange(value: String) = edit { it.copy(description = value) }
    fun onRestFillerChange(value: RestFiller?) = edit { it.copy(restFiller = value) }

    fun onAddBlock(title: String) = edit { it.copy(blocks = it.blocks + BlockDraft(newKey(), WorkoutBlock(title = title), emptyList())) }
    fun onRemoveBlock(key: Long) = edit { d -> d.copy(blocks = d.blocks.filterNot { it.key == key }) }
    fun onMoveBlock(from: Int, to: Int) = edit { it.copy(blocks = it.blocks.move(from, to)) }
    fun onUpdateBlock(key: Long, transform: (WorkoutBlock) -> WorkoutBlock) = editBlock(key) { it.copy(block = transform(it.block)) }

    fun onAddExercises(blockKey: Long, exerciseIds: List<Long>) {
        val library = uiState.value.library
        val added = exerciseIds.mapNotNull { library[it] }.map { ItemDraft(newKey(), defaultItemFor(it)) }
        if (added.isEmpty()) return
        editBlock(blockKey) { it.copy(items = it.items + added) }
        // Open the first new item so its sets/rest are one tap away.
        draft.update { it.copy(expandedItemKey = added.first().key) }
    }

    fun onRemoveItem(blockKey: Long, itemKey: Long) = editBlock(blockKey) { b -> b.copy(items = b.items.filterNot { it.key == itemKey }) }
    fun onMoveItem(blockKey: Long, from: Int, to: Int) = editBlock(blockKey) { it.copy(items = it.items.move(from, to)) }
    fun onUpdateItem(itemKey: Long, transform: (WorkoutItem) -> WorkoutItem) = edit { d ->
        d.copy(
            blocks = d.blocks.map { block ->
                block.copy(items = block.items.map { if (it.key == itemKey) it.copy(item = transform(it.item)) else it })
            },
        )
    }

    fun onToggleExpanded(itemKey: Long) = draft.update { it.copy(expandedItemKey = if (it.expandedItemKey == itemKey) null else itemKey) }

    fun onSave() {
        val d = draft.value
        if (d.loading || d.saving) return
        if (d.name.isBlank()) {
            draft.update { it.copy(showNameError = true) }
            return
        }
        draft.update { it.copy(saving = true) }
        viewModelScope.launch { _saved.send(workoutRepository.save(d.toTemplate())) }
    }

    private fun WorkoutDraft.toTemplate() = WorkoutTemplate(
        id = original?.id ?: 0,
        name = name.trim(),
        description = description.trim(),
        blocks = blocks.map { it.toBlock().copy(title = it.block.title.trim()) },
        restFiller = restFiller,
        createdAtMillis = original?.createdAtMillis ?: 0,
    )

    private fun editBlock(key: Long, transform: (BlockDraft) -> BlockDraft) = edit { d ->
        d.copy(blocks = d.blocks.map { if (it.key == key) transform(it) else it })
    }

    private fun edit(transform: (WorkoutDraft) -> WorkoutDraft) = draft.update { transform(it).copy(dirty = true) }

    private fun newKey(): Long = nextKey++

    private fun defaultItemFor(exercise: Exercise): WorkoutItem {
        val target = when (exercise.measureMode) {
            MeasureMode.REPS, MeasureMode.REPS_WEIGHT -> SetTarget(reps = 10)
            MeasureMode.TIME -> SetTarget(seconds = 30)
            MeasureMode.DISTANCE -> SetTarget(distanceMeters = 400)
        }
        val effort = exercise.category in setOf(ExerciseCategory.STRENGTH, ExerciseCategory.CORE, ExerciseCategory.CARDIO)
        return WorkoutItem(
            exerciseId = exercise.id,
            sets = if (effort) 3 else 1,
            target = target,
            restSeconds = if (effort) 60 else 15,
        )
    }
}
