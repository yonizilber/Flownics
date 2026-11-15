package com.yonizilber.flownics.feature.library.edit

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yonizilber.flownics.data.ai.AiService
import com.yonizilber.flownics.data.ai.ExerciseAiNote
import com.yonizilber.flownics.data.local.media.MediaStorage
import com.yonizilber.flownics.data.local.media.MediaTooLargeException
import com.yonizilber.flownics.domain.ai.AiException
import com.yonizilber.flownics.domain.ai.AiResult
import com.yonizilber.flownics.domain.ai.ExerciseSuggestion
import com.yonizilber.flownics.domain.ai.FitnessAnswers
import com.yonizilber.flownics.domain.ai.FitnessPrompts
import com.yonizilber.flownics.domain.library.MuscleSelection
import com.yonizilber.flownics.domain.library.move
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseTag
import com.yonizilber.flownics.domain.model.Tags
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.ExerciseMedia
import com.yonizilber.flownics.domain.model.ExerciseStage
import com.yonizilber.flownics.domain.model.MeasureMode
import com.yonizilber.flownics.domain.model.MediaKind
import com.yonizilber.flownics.domain.model.Muscle
import com.yonizilber.flownics.domain.model.ExerciseLevel
import com.yonizilber.flownics.domain.repository.ExerciseRepository
import com.yonizilber.flownics.domain.repository.VariationRepository
import com.yonizilber.flownics.feature.ai.AiCall
import com.yonizilber.flownics.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import javax.inject.Inject

data class CueDraft(val key: Long, val text: String)

data class StageDraft(val key: Long, val name: String = "", val description: String = "", val seconds: String = "")

data class ExerciseEditorState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val name: String = "",
    val showNameError: Boolean = false,
    /** Every kind it is, in the order chosen: the first is the main one (its icon). Never empty. */
    val categories: List<ExerciseCategory> = listOf(ExerciseCategory.STRENGTH),
    val measureMode: MeasureMode = MeasureMode.REPS,
    val unilateral: Boolean = false,
    val level: Int? = null,
    /** The base exercise, when this one is a variation of it; its name for display. */
    val variationOf: Long? = null,
    val variationOfName: String? = null,
    val muscles: MuscleSelection = MuscleSelection(),
    val equipment: String = "",
    val instructions: List<CueDraft> = emptyList(),
    val cues: List<CueDraft> = emptyList(),
    val stages: List<StageDraft> = emptyList(),
    val media: List<ExerciseMedia> = emptyList(),
    val notes: String = "",
    /** Free labels, each with what this exercise does for it. */
    val tags: List<ExerciseTag> = emptyList(),
    /** A general step (warm-up, easy jog): no specific exercise, no reps; timed or until "next". */
    val general: Boolean = false,
    val importing: Boolean = false,
    val saving: Boolean = false,
    val dirty: Boolean = false,
)

/** The parts of an AI suggestion the user can take, each on its own. */
enum class FillField { CATEGORY, MEASURE, UNILATERAL, LEVEL, EQUIPMENT, MUSCLES, INSTRUCTIONS, CUES, STAGES, SUMMARY }

sealed interface EditorEvent {
    data class Saved(val id: Long) : EditorEvent
    data object MediaFailed : EditorEvent
    data class VideoTooLarge(val limitMb: Int) : EditorEvent
}

@HiltViewModel
class ExerciseEditViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repository: ExerciseRepository,
    private val mediaStorage: MediaStorage,
    private val ai: AiService,
    private val variations: VariationRepository,
) : ViewModel() {

    private val exerciseId: Long = savedStateHandle.get<Long>(Destination.ARG_EXERCISE_ID) ?: 0L
    private var original: Exercise? = null
    private var nextKey = 0L

    // Files copied in during this edit. If the edit is abandoned they are deleted again, so a
    // cancelled edit never leaves orphan photos in private storage.
    private val importedThisSession = mutableSetOf<String>()
    private var saved = false

    private val _state = MutableStateFlow(ExerciseEditorState())
    val state: StateFlow<ExerciseEditorState> = _state.asStateFlow()

    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events: Flow<EditorEvent> = _events.receiveAsFlow()

    val aiReady: StateFlow<Boolean> = ai.settings.map { it.ready }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ai.settings.value.ready)
    val aiModel get() = ai.settings.value.model

    /** Every tag already in the library, most used first, to pick from. */
    val knownTags: StateFlow<List<String>> = repository.observeAll().map { all -> Tags.all(all).map { it.first } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _fill = MutableStateFlow<AiCall<AiResult<ExerciseSuggestion>>>(AiCall.Idle)
    val fill: StateFlow<AiCall<AiResult<ExerciseSuggestion>>> = _fill.asStateFlow()
    private var fillJob: Job? = null

    // What the user took from an AI suggestion; written next to the exercise when it is saved.
    private var pendingNote: ExerciseAiNote? = null

    init {
        viewModelScope.launch {
            val existing = if (exerciseId != 0L) repository.get(exerciseId) else null
            original = existing
            val baseId = existing?.variationOf ?: savedStateHandle.get<Long>(Destination.ARG_VARIATION_OF)?.takeIf { it > 0 }
            val baseName = baseId?.let { repository.get(it)?.name }
            _state.value = if (existing == null) {
                // A new exercise can arrive pre-filled, e.g. from an AI-suggested variation.
                ExerciseEditorState(
                    loading = false,
                    name = prefill(Destination.ARG_PREFILL_NAME),
                    equipment = prefill(Destination.ARG_PREFILL_EQUIPMENT),
                    notes = prefill(Destination.ARG_PREFILL_NOTES),
                    categories = listOf(
                        prefill(Destination.ARG_PREFILL_CATEGORY)
                            .let { c -> ExerciseCategory.entries.firstOrNull { it.name == c } } ?: ExerciseCategory.STRENGTH,
                    ),
                    level = savedStateHandle.get<Int>(Destination.ARG_PREFILL_LEVEL)?.takeIf { it in ExerciseLevel.RANGE },
                    variationOf = baseId.takeIf { baseName != null },
                    variationOfName = baseName,
                    instructions = listOf(CueDraft(newKey(), "")),
                    cues = listOf(CueDraft(newKey(), "")),
                )
            } else {
                existing.toEditorState().copy(variationOf = baseId.takeIf { baseName != null }, variationOfName = baseName)
            }
        }
    }

    private fun prefill(key: String): String = savedStateHandle.get<String>(key).orEmpty()

    /** Asks the AI to fill in this exercise from its name and whatever is already entered. */
    fun requestFill(hint: String, webSearch: Boolean, language: String) {
        val s = _state.value
        if (s.name.isBlank()) {
            _state.update { it.copy(showNameError = true) }
            return
        }
        fillJob?.cancel()
        _fill.value = AiCall.Running
        fillJob = viewModelScope.launch {
            _fill.value = try {
                AiCall.Done(ai.ask(FitnessPrompts.exerciseFill(s.toExercise(), hint, language, webSearch), FitnessAnswers::exerciseFill))
            } catch (e: AiException) {
                AiCall.Failed(e)
            }
        }
    }

    /** The form as an exercise, unsaved: what AI requests describe. */
    fun draftExercise(): Exercise = _state.value.toExercise()

    fun dismissFill() {
        fillJob?.cancel()
        _fill.value = AiCall.Idle
    }

    /** Copies the chosen parts into the form. Nothing is saved until the user saves the exercise. */
    fun applyFill(fields: Set<FillField>) {
        val result = (_fill.value as? AiCall.Done)?.value ?: return
        val suggestion = result.value
        edit { s ->
            var next = s
            if (FillField.CATEGORY in fields) suggestion.category?.let { main -> next = next.copy(categories = (listOf(main) + suggestion.extraCategories).distinct()) }
            if (FillField.MEASURE in fields) suggestion.measureMode?.let { next = next.copy(measureMode = it) }
            if (FillField.UNILATERAL in fields) next = next.copy(unilateral = suggestion.unilateral)
            if (FillField.LEVEL in fields) suggestion.level?.let { next = next.copy(level = it) }
            if (FillField.EQUIPMENT in fields) next = next.copy(equipment = suggestion.equipment)
            if (FillField.MUSCLES in fields) {
                next = next.copy(muscles = MuscleSelection(suggestion.primaryMuscles, suggestion.secondaryMuscles))
            }
            if (FillField.INSTRUCTIONS in fields) {
                // Steps are a sequence, so they replace rather than mix with the user's own.
                next = next.copy(instructions = suggestion.instructions.map { CueDraft(newKey(), it) })
            }
            if (FillField.CUES in fields) {
                // Added after the user's own cues, never replacing them; an empty placeholder row goes.
                val kept = next.cues.filter { it.text.isNotBlank() }
                val known = kept.map { it.text.trim().lowercase() }.toSet()
                next = next.copy(cues = kept + suggestion.cues.filter { it.lowercase() !in known }.map { CueDraft(newKey(), it) })
            }
            if (FillField.STAGES in fields) {
                next = next.copy(
                    stages = next.stages + suggestion.stages.map {
                        StageDraft(newKey(), it.name, it.description, it.durationSeconds?.toString().orEmpty())
                    },
                )
            }
            if (FillField.SUMMARY in fields && suggestion.summary.isNotBlank()) {
                next = next.copy(notes = listOf(next.notes.trim(), suggestion.summary).filter { it.isNotEmpty() }.joinToString("\n\n"))
            }
            next
        }
        pendingNote = ExerciseAiNote(
            atMillis = System.currentTimeMillis(),
            model = result.model,
            modelId = result.model.id,
            summary = suggestion.summary,
            applied = fields.map { it.name.lowercase() }.sorted(),
            sources = result.sources,
        )
        _fill.value = AiCall.Idle
    }

    fun mediaFile(name: String): File = mediaStorage.file(name)

    fun onNameChange(value: String) = edit { it.copy(name = value, showNameError = false) }
    /** Adds or removes a kind. The last one can't be removed: an exercise is always something. */
    fun onCategoryToggle(value: ExerciseCategory) = edit {
        when {
            value !in it.categories -> it.copy(categories = it.categories + value)
            it.categories.size > 1 -> it.copy(categories = it.categories - value)
            else -> it
        }
    }

    /** Makes a chosen kind the main one. */
    fun onCategoryMain(value: ExerciseCategory) = edit { if (value in it.categories) it.copy(categories = listOf(value) + (it.categories - value)) else it }
    fun onMeasureModeChange(value: MeasureMode) = edit { it.copy(measureMode = value) }
    fun onUnilateralChange(value: Boolean) = edit { it.copy(unilateral = value) }
    fun onLevelChange(value: Int?) = edit { it.copy(level = value) }
    fun onClearVariationOf() = edit { it.copy(variationOf = null, variationOfName = null) }

    /** Makes this exercise a style of [base] (it joins [base]'s family). */
    fun onSetVariationOf(base: Exercise) = edit { it.copy(variationOf = base.id, variationOfName = base.name) }

    /** Other exercises, to choose the family this one is a style of. */
    val otherExercises: StateFlow<List<Exercise>> = repository.observeAll()
        .map { all -> all.filter { it.id != exerciseId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun onMuscleTap(muscle: Muscle) = edit { it.copy(muscles = it.muscles.cycle(muscle)) }
    fun onEquipmentChange(value: String) = edit { it.copy(equipment = value) }
    fun onNotesChange(value: String) = edit { it.copy(notes = value) }

    fun onAddTag(name: String) = edit { s ->
        val clean = name.trim()
        if (clean.isEmpty() || s.tags.any { Tags.key(it.name) == Tags.key(clean) }) s else s.copy(tags = s.tags + ExerciseTag(clean))
    }
    fun onRemoveTag(name: String) = edit { s -> s.copy(tags = s.tags.filterNot { Tags.key(it.name) == Tags.key(name) }) }
    fun onTagNote(name: String, note: String) = edit { s -> s.copy(tags = s.tags.map { if (Tags.key(it.name) == Tags.key(name)) it.copy(note = note) else it }) }

    /** A general step is told by time (or "until next"), never by reps. */
    fun onGeneralChange(value: Boolean) = edit { it.copy(general = value, measureMode = if (value) MeasureMode.TIME else it.measureMode) }

    fun onAddStep() = edit { it.copy(instructions = it.instructions + CueDraft(newKey(), "")) }
    fun onStepChange(key: Long, text: String) = edit { s ->
        s.copy(instructions = s.instructions.map { if (it.key == key) it.copy(text = text) else it })
    }
    fun onRemoveStep(key: Long) = edit { s -> s.copy(instructions = s.instructions.filterNot { it.key == key }) }
    fun onMoveStep(from: Int, to: Int) = edit { it.copy(instructions = it.instructions.move(from, to)) }

    fun onAddCue() = edit { it.copy(cues = it.cues + CueDraft(newKey(), "")) }
    fun onCueChange(key: Long, text: String) = edit { s ->
        s.copy(cues = s.cues.map { if (it.key == key) it.copy(text = text) else it })
    }
    fun onRemoveCue(key: Long) = edit { s -> s.copy(cues = s.cues.filterNot { it.key == key }) }
    fun onMoveCue(from: Int, to: Int) = edit { it.copy(cues = it.cues.move(from, to)) }

    fun onAddStage() = edit { it.copy(stages = it.stages + StageDraft(newKey())) }
    fun onStageChange(stage: StageDraft) = edit { s ->
        val cleaned = stage.copy(seconds = stage.seconds.filter(Char::isDigit).take(4))
        s.copy(stages = s.stages.map { if (it.key == stage.key) cleaned else it })
    }
    fun onRemoveStage(key: Long) = edit { s -> s.copy(stages = s.stages.filterNot { it.key == key }) }
    fun onMoveStage(from: Int, to: Int) = edit { it.copy(stages = it.stages.move(from, to)) }

    fun onRemoveMedia(index: Int) = edit { s -> s.copy(media = s.media.filterIndexed { i, _ -> i != index }) }

    fun onAddLink(url: String): Boolean {
        val trimmed = url.trim()
        val parsed = Uri.parse(trimmed)
        if (parsed.scheme != "https" || parsed.host.isNullOrBlank()) return false
        edit { it.copy(media = it.media + ExerciseMedia(MediaKind.LINK, trimmed)) }
        return true
    }

    fun onImagesPicked(uris: List<Uri>) = importMedia(uris, MediaKind.IMAGE)

    fun onVideoPicked(uri: Uri?) = importMedia(listOfNotNull(uri), MediaKind.VIDEO)

    private fun importMedia(uris: List<Uri>, kind: MediaKind) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(importing = true) }
            for (uri in uris) {
                try {
                    val name = if (kind == MediaKind.IMAGE) mediaStorage.importImage(uri) else mediaStorage.importVideo(uri)
                    importedThisSession += name
                    edit { it.copy(media = it.media + ExerciseMedia(kind, name)) }
                } catch (e: MediaTooLargeException) {
                    _events.send(EditorEvent.VideoTooLarge((e.limitBytes / (1024 * 1024)).toInt()))
                } catch (e: IOException) {
                    _events.send(EditorEvent.MediaFailed)
                } catch (e: SecurityException) {
                    _events.send(EditorEvent.MediaFailed)
                }
            }
            _state.update { it.copy(importing = false) }
        }
    }

    fun onSave() {
        val s = _state.value
        if (s.saving || s.loading) return
        if (s.name.isBlank()) {
            _state.update { it.copy(showNameError = true) }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val exercise = s.toExercise()
            val id = repository.save(exercise)
            // Puts it in the family properly: under the family's default, its own styles along.
            if (exercise.variationOf != original?.variationOf) repository.setStyleOf(id, exercise.variationOf)
            pendingNote?.let { ai.saveExerciseNote(id, it) }
            // Made from a kept variation: link it, so the variation shows it's now in the library.
            savedStateHandle.get<Long>(Destination.ARG_VARIATION_ID)?.takeIf { it > 0 && original == null }
                ?.let { variations.markAdded(it, id) }
            saved = true
            val kept = exercise.media.map { it.location }.toSet()
            mediaStorage.delete(importedThisSession - kept)
            _events.send(EditorEvent.Saved(id))
        }
    }

    override fun onCleared() {
        if (!saved) mediaStorage.deleteNow(importedThisSession)
        super.onCleared()
    }

    private fun edit(transform: (ExerciseEditorState) -> ExerciseEditorState) =
        _state.update { transform(it).copy(dirty = true) }

    private fun newKey(): Long = nextKey++

    private fun Exercise.toEditorState() = ExerciseEditorState(
        loading = false,
        isNew = false,
        name = name,
        categories = categories,
        measureMode = measureMode,
        unilateral = unilateral,
        level = level,
        muscles = MuscleSelection(primaryMuscles, secondaryMuscles),
        equipment = equipment,
        // An exercise from before instructions existed gets one empty step to start typing in.
        instructions = instructions.ifEmpty { listOf("") }.map { CueDraft(newKey(), it) },
        cues = cues.map { CueDraft(newKey(), it) },
        stages = stages.map { StageDraft(newKey(), it.name, it.description, it.durationSeconds?.toString().orEmpty()) },
        media = media,
        notes = notes,
        tags = tags,
        general = general,
    )

    private fun ExerciseEditorState.toExercise() = Exercise(
        id = original?.id ?: 0,
        name = name.trim(),
        category = categories.first(),
        extraCategories = categories.drop(1).toSet(),
        primaryMuscles = muscles.primary,
        secondaryMuscles = muscles.secondary,
        equipment = equipment.trim(),
        instructions = instructions.map { it.text.trim() }.filter { it.isNotEmpty() },
        cues = cues.map { it.text.trim() }.filter { it.isNotEmpty() },
        stages = stages
            .filter { it.name.isNotBlank() || it.description.isNotBlank() }
            .map { ExerciseStage(it.name.trim(), it.description.trim(), it.seconds.toIntOrNull()?.takeIf { s -> s > 0 }) },
        media = media,
        measureMode = measureMode,
        unilateral = unilateral,
        level = level,
        variationOf = variationOf,
        notes = notes.trim(),
        tags = tags.map { it.copy(name = it.name.trim(), note = it.note.trim()) },
        general = general,
        // Saved from the editor: its details are no longer pending.
        quickAdded = false,
        createdAtMillis = original?.createdAtMillis ?: 0,
    )
}
