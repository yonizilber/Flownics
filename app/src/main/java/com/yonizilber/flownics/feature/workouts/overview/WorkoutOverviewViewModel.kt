package com.yonizilber.flownics.feature.workouts.overview

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.WorkoutTemplate
import com.yonizilber.flownics.domain.repository.ExerciseRepository
import com.yonizilber.flownics.domain.repository.WorkoutRepository
import com.yonizilber.flownics.domain.workout.CompiledWorkout
import com.yonizilber.flownics.domain.workout.WorkoutCompiler
import com.yonizilber.flownics.navigation.Destination
import com.yonizilber.flownics.player.PlayerController
import com.yonizilber.flownics.player.StartResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface WorkoutOverviewUiState {
    data object Loading : WorkoutOverviewUiState
    data object NotFound : WorkoutOverviewUiState
    data class Loaded(
        val template: WorkoutTemplate,
        val compiled: CompiledWorkout,
        val library: Map<Long, Exercise>,
    ) : WorkoutOverviewUiState
}

@HiltViewModel
class WorkoutOverviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val workoutRepository: WorkoutRepository,
    exerciseRepository: ExerciseRepository,
    private val player: PlayerController,
) : ViewModel() {

    private val workoutId: Long = checkNotNull(savedStateHandle.get<Long>(Destination.ARG_WORKOUT_ID))

    val uiState: StateFlow<WorkoutOverviewUiState> =
        combine(workoutRepository.observe(workoutId), exerciseRepository.observeAll(), workoutRepository.observeAll()) { template, exercises, all ->
            if (template == null) {
                WorkoutOverviewUiState.NotFound
            } else {
                val library = exercises.associateBy { it.id }
                WorkoutOverviewUiState.Loaded(template, WorkoutCompiler.compile(template, library, all.associateBy { it.id }), library)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutOverviewUiState.Loading)

    /** Why the last start didn't happen; null when there is nothing to show. */
    private val _startProblem = MutableStateFlow<StartResult?>(null)
    val startProblem: StateFlow<StartResult?> = _startProblem.asStateFlow()

    fun start(onStarted: () -> Unit) {
        viewModelScope.launch {
            when (val result = player.start(workoutId)) {
                StartResult.Started -> onStarted()
                else -> {
                    // Load the other workout so "go to it" opens a ready player.
                    if (result == StartResult.AnotherInProgress) player.restoreIfNeeded()
                    _startProblem.value = result
                }
            }
        }
    }

    /** Discards the workout already in progress, then starts this one. */
    fun replaceAndStart(onStarted: () -> Unit) {
        _startProblem.value = null
        viewModelScope.launch {
            player.discard()
            start(onStarted)
        }
    }

    fun dismissStartProblem() {
        _startProblem.value = null
    }

    fun duplicate(newName: String, onDone: (Long) -> Unit) {
        viewModelScope.launch { onDone(workoutRepository.duplicate(workoutId, newName)) }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            workoutRepository.delete(workoutId)
            onDone()
        }
    }
}
