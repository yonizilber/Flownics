package com.yonizilber.flownics.feature.workouts.list

import kotlinx.coroutines.launch
import com.yonizilber.flownics.player.StartResult
import com.yonizilber.flownics.feature.player.AddedExercise
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.data.local.prefs.WorkoutDefaults
import com.yonizilber.flownics.data.local.prefs.AppPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yonizilber.flownics.domain.model.WorkoutTemplate
import com.yonizilber.flownics.domain.repository.ExerciseRepository
import com.yonizilber.flownics.domain.repository.WorkoutRepository
import com.yonizilber.flownics.domain.workout.WorkoutCompiler
import com.yonizilber.flownics.player.ActiveWorkout
import com.yonizilber.flownics.player.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class WorkoutSummary(
    val template: WorkoutTemplate,
    val estimatedSeconds: Int,
    val exerciseCount: Int,
    val hasWarnings: Boolean,
)

data class WorkoutsUiState(
    val loading: Boolean = true,
    val workouts: List<WorkoutSummary> = emptyList(),
    val libraryIsEmpty: Boolean = false,
)

@HiltViewModel
class WorkoutsViewModel @Inject constructor(
    workoutRepository: WorkoutRepository,
    exerciseRepository: ExerciseRepository,
    private val player: PlayerController,
    prefs: AppPreferences,
) : ViewModel() {

    val defaults: StateFlow<WorkoutDefaults> = prefs.observeWorkoutDefaults()
    val library: StateFlow<Map<Long, Exercise>> = player.library

    /** A workout without a plan, starting with [first]; more is added from the player as it goes. */
    fun startFree(first: AddedExercise, onStarted: () -> Unit) = viewModelScope.launch {
        val id = first.newName?.let { player.createQuick(it, first.newMode, first.newGeneral) } ?: first.exerciseId
        if (player.startFree(listOf(Triple(id, first.sets, first.target)), first.restSeconds) == StartResult.Started) onStarted()
    }


    init {
        player.restoreIfNeeded()
    }

    /** The workout currently running (or left paused), for the resume banner. */
    val activeWorkout: StateFlow<ActiveWorkout?> = player.state

    val uiState: StateFlow<WorkoutsUiState> =
        combine(workoutRepository.observeAll(), exerciseRepository.observeAll()) { templates, exercises ->
            val library = exercises.associateBy { it.id }
            WorkoutsUiState(
                loading = false,
                workouts = templates.map { template ->
                    val compiled = WorkoutCompiler.compile(template, library, templates.associateBy { it.id })
                    WorkoutSummary(
                        template = template,
                        estimatedSeconds = compiled.estimatedSeconds,
                        exerciseCount = template.blocks.flatMap { it.items }.map { it.exerciseId }.distinct().size,
                        hasWarnings = compiled.warnings.isNotEmpty(),
                    )
                },
                libraryIsEmpty = exercises.isEmpty(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutsUiState())
}
