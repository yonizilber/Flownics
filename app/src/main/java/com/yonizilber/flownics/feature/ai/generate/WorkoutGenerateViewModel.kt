package com.yonizilber.flownics.feature.ai.generate

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yonizilber.flownics.data.ai.AiService
import com.yonizilber.flownics.domain.ai.AiException
import com.yonizilber.flownics.domain.ai.AiModel
import com.yonizilber.flownics.domain.ai.AiResult
import com.yonizilber.flownics.domain.ai.FitnessAnswers
import com.yonizilber.flownics.domain.ai.FitnessPrompts
import com.yonizilber.flownics.domain.ai.GeneratedWorkout
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.repository.ExerciseRepository
import com.yonizilber.flownics.domain.repository.WorkoutRepository
import com.yonizilber.flownics.feature.ai.AiCall
import com.yonizilber.flownics.feature.ai.review.WorkoutLoad
import com.yonizilber.flownics.feature.ai.review.observeWorkoutContext
import com.yonizilber.flownics.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Builds a workout with the AI: a variation of an existing one (workoutId > 0) or a new draft
 * (workoutId 0). The result is only a preview until the user saves it as a new workout.
 */
@HiltViewModel
class WorkoutGenerateViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val workouts: WorkoutRepository,
    exercises: ExerciseRepository,
    private val ai: AiService,
) : ViewModel() {

    private val workoutId: Long = savedStateHandle.get<Long>(Destination.ARG_WORKOUT_ID) ?: 0L
    val isVariation: Boolean = workoutId != 0L

    val source: StateFlow<WorkoutLoad> =
        (if (isVariation) observeWorkoutContext(workouts, exercises, workoutId) else flowOf(WorkoutLoad.NotFound))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutLoad.Loading)

    val library: StateFlow<List<Exercise>?> = exercises.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val aiReady: StateFlow<Boolean> = ai.settings.map { it.ready }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ai.settings.value.ready)
    val model: AiModel get() = ai.settings.value.model

    private val _call = MutableStateFlow<AiCall<AiResult<GeneratedWorkout>>>(AiCall.Idle)
    val call: StateFlow<AiCall<AiResult<GeneratedWorkout>>> = _call.asStateFlow()
    private var job: Job? = null

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    fun generate(request: String, minutes: Int, language: String) {
        val all = library.value ?: return
        val byId = all.associateBy { it.id }
        val prompt = if (isVariation) {
            val ctx = (source.value as? WorkoutLoad.Loaded)?.context ?: return
            FitnessPrompts.workoutVariation(ctx.template, ctx.library, ctx.compiled.estimatedSeconds, ctx.load, request, language)
        } else {
            FitnessPrompts.workoutDraft(all, minutes, request, language)
        }
        job?.cancel()
        _call.value = AiCall.Running
        job = viewModelScope.launch {
            _call.value = try {
                AiCall.Done(ai.ask(prompt) { FitnessAnswers.workout(it, byId) })
            } catch (e: AiException) {
                AiCall.Failed(e)
            }
        }
    }

    fun reset() {
        job?.cancel()
        _call.value = AiCall.Idle
    }

    /** Saves the preview as a new workout (the original, if any, is left as it was). */
    fun save(fallbackName: String, onSaved: (Long) -> Unit) {
        val generated = (_call.value as? AiCall.Done)?.value?.value ?: return
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            val template = generated.template.let { it.copy(name = it.name.ifBlank { fallbackName }) }
            onSaved(workouts.save(template))
        }
    }
}
