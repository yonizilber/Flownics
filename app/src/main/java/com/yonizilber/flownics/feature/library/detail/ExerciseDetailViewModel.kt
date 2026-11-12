package com.yonizilber.flownics.feature.library.detail

import com.yonizilber.flownics.domain.library.Families
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yonizilber.flownics.data.ai.AiService
import com.yonizilber.flownics.data.ai.ExerciseAiNote
import com.yonizilber.flownics.data.local.media.MediaStorage
import com.yonizilber.flownics.domain.history.ExerciseProgress
import com.yonizilber.flownics.domain.history.ExerciseSession
import com.yonizilber.flownics.domain.model.ActiveRest
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.repository.ExerciseRepository
import com.yonizilber.flownics.domain.repository.VariationRepository
import com.yonizilber.flownics.domain.repository.SessionRepository
import com.yonizilber.flownics.domain.repository.WorkoutRepository
import com.yonizilber.flownics.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

sealed interface ExerciseDetailUiState {
    data object Loading : ExerciseDetailUiState
    data object NotFound : ExerciseDetailUiState
    data class Loaded(val exercise: Exercise) : ExerciseDetailUiState
}

@HiltViewModel
class ExerciseDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ExerciseRepository,
    private val mediaStorage: MediaStorage,
    workoutRepository: WorkoutRepository,
    sessionRepository: SessionRepository,
    ai: AiService,
    variationRepository: VariationRepository,
) : ViewModel() {

    private val exerciseId: Long = checkNotNull(savedStateHandle.get<Long>(Destination.ARG_EXERCISE_ID))

    val uiState: StateFlow<ExerciseDetailUiState> = repository.observe(exerciseId)
        .map { if (it == null) ExerciseDetailUiState.NotFound else ExerciseDetailUiState.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExerciseDetailUiState.Loading)

    /** How many workouts reference this exercise (as an item or as specific active rest). */
    val usedInWorkouts: StateFlow<Int> = workoutRepository.observeAll()
        .map { templates ->
            templates.count { template ->
                template.blocks.any { block ->
                    (block.roundActiveRest as? ActiveRest.Specific)?.exerciseId == exerciseId ||
                        block.items.any { it.exerciseId == exerciseId || (it.activeRest as? ActiveRest.Specific)?.exerciseId == exerciseId }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** This exercise in finished workouts, oldest first. */
    val history: StateFlow<List<ExerciseSession>?> = sessionRepository.observeExerciseLogs(exerciseId)
        .map { ExerciseProgress.bySession(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The library exercises that are variations of the same base as this one (the base included),
     * easiest first; empty when this exercise has no family.
     */
    val family: StateFlow<List<Exercise>> = repository.observeAll()
        .map { all ->
            val self = all.firstOrNull { it.id == exerciseId } ?: return@map emptyList()
            Families.familyOf(self, all).members
                .takeIf { it.size > 1 }
                .orEmpty()
                .sortedWith(compareBy<Exercise> { it.level ?: Int.MAX_VALUE }.thenBy { it.name.lowercase() })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The family's default (the one a workout gets unless another style is picked). */
    val familyBaseId: StateFlow<Long?> = repository.observeAll()
        .map { all -> all.firstOrNull { it.id == exerciseId }?.let { Families.familyOf(it, all).base.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun makeFamilyDefault() = viewModelScope.launch { repository.makeFamilyDefault(exerciseId) }

    /** Suggested variations kept under this exercise. */
    val savedVariationCount: StateFlow<Int> = variationRepository.observeFor(exerciseId).map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Where AI-suggested details came from, newest first. */
    val aiNotes: StateFlow<List<ExerciseAiNote>> = ai.observeExerciseNotes(exerciseId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun mediaFile(name: String): File = mediaStorage.file(name)

    fun shareableUri(name: String): Uri = mediaStorage.shareableUri(name)

    fun duplicate(newName: String, onDone: (Long) -> Unit) {
        viewModelScope.launch { onDone(repository.duplicate(exerciseId, newName)) }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.delete(exerciseId)
            onDone()
        }
    }
}
