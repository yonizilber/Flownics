package com.yonizilber.flownics.feature.ai.review

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yonizilber.flownics.data.ai.AiService
import com.yonizilber.flownics.domain.ai.AiException
import com.yonizilber.flownics.domain.ai.AiModel
import com.yonizilber.flownics.domain.ai.AiResult
import com.yonizilber.flownics.domain.ai.FitnessAnswers
import com.yonizilber.flownics.domain.ai.FitnessPrompts
import com.yonizilber.flownics.domain.ai.WorkoutReview
import com.yonizilber.flownics.domain.history.MuscleLoad
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.WorkoutTemplate
import com.yonizilber.flownics.domain.repository.ExerciseRepository
import com.yonizilber.flownics.domain.repository.WorkoutRepository
import com.yonizilber.flownics.domain.workout.CompiledWorkout
import com.yonizilber.flownics.domain.workout.WorkoutCompiler
import com.yonizilber.flownics.feature.ai.AiCall
import com.yonizilber.flownics.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A workout as the AI screens see it: compiled, with its exercises and what it trains. */
data class WorkoutContext(
    val template: WorkoutTemplate,
    val library: Map<Long, Exercise>,
    val compiled: CompiledWorkout,
    val load: MuscleLoad,
)

sealed interface WorkoutLoad {
    data object Loading : WorkoutLoad
    data object NotFound : WorkoutLoad
    data class Loaded(val context: WorkoutContext) : WorkoutLoad
}

fun observeWorkoutContext(workouts: WorkoutRepository, exercises: ExerciseRepository, workoutId: Long) =
    combine(workouts.observe(workoutId), exercises.observeAll()) { template, all ->
        if (template == null) {
            WorkoutLoad.NotFound
        } else {
            val library = all.associateBy { it.id }
            val compiled = WorkoutCompiler.compile(template, library)
            WorkoutLoad.Loaded(WorkoutContext(template, library, compiled, MuscleLoad.of(compiled, library)))
        }
    }

@HiltViewModel
class WorkoutReviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    workouts: WorkoutRepository,
    exercises: ExerciseRepository,
    private val ai: AiService,
) : ViewModel() {

    private val workoutId: Long = checkNotNull(savedStateHandle.get<Long>(Destination.ARG_WORKOUT_ID))

    val workout: StateFlow<WorkoutLoad> = observeWorkoutContext(workouts, exercises, workoutId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutLoad.Loading)

    val aiReady: StateFlow<Boolean> = ai.settings.map { it.ready }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ai.settings.value.ready)
    val model: AiModel get() = ai.settings.value.model

    private val _call = MutableStateFlow<AiCall<AiResult<WorkoutReview>>>(AiCall.Idle)
    val call: StateFlow<AiCall<AiResult<WorkoutReview>>> = _call.asStateFlow()
    private var job: Job? = null

    fun review(goal: String, language: String) {
        val ctx = (workout.value as? WorkoutLoad.Loaded)?.context ?: return
        job?.cancel()
        _call.value = AiCall.Running
        job = viewModelScope.launch {
            _call.value = try {
                val request = FitnessPrompts.workoutReview(ctx.template, ctx.library, ctx.compiled.estimatedSeconds, ctx.load, goal, language)
                AiCall.Done(ai.ask(request, FitnessAnswers::review))
            } catch (e: AiException) {
                AiCall.Failed(e)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _call.value = AiCall.Idle
    }
}
