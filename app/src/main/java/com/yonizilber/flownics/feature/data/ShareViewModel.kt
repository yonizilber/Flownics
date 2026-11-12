package com.yonizilber.flownics.feature.data

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yonizilber.flownics.data.transfer.DataTransfer
import com.yonizilber.flownics.data.transfer.ExportResult
import com.yonizilber.flownics.data.transfer.ShareSelection
import com.yonizilber.flownics.domain.model.ActiveRest
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.WorkoutTemplate
import com.yonizilber.flownics.domain.repository.ExerciseRepository
import com.yonizilber.flownics.domain.repository.WorkoutRepository
import com.yonizilber.flownics.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ShareLibrary(val workouts: List<WorkoutTemplate>, val exercises: List<Exercise>)

/** A file ready for the share sheet, or the outcome of saving one. */
sealed interface ShareOutcome {
    /** [launched] once the share sheet was opened for it, so a rotation doesn't open it again. */
    data class Ready(val file: File, val result: ExportResult, val launched: Boolean = false) : ShareOutcome
    data class Saved(val result: ExportResult) : ShareOutcome
    data class Failed(val error: Throwable) : ShareOutcome
}

@HiltViewModel
class ShareViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    exercises: ExerciseRepository,
    workouts: WorkoutRepository,
    private val transfer: DataTransfer,
) : ViewModel() {

    val library: StateFlow<ShareLibrary?> = combine(workouts.observeAll(), exercises.observeAll()) { w, e ->
        ShareLibrary(w.sortedBy { it.name.lowercase() }, e.sortedBy { it.name.lowercase() })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Whatever the screen was opened from is ticked to begin with.
    private val _selection = MutableStateFlow(
        ShareSelection(
            exerciseIds = setOfNotNull(savedStateHandle.get<Long>(Destination.ARG_EXERCISE_ID)?.takeIf { it > 0 }),
            workoutIds = setOfNotNull(savedStateHandle.get<Long>(Destination.ARG_WORKOUT_ID)?.takeIf { it > 0 }),
        ),
    )
    val selection: StateFlow<ShareSelection> = _selection.asStateFlow()

    /** Exercises the ticked workouts bring along, whether or not they are ticked themselves. */
    val withWorkouts: StateFlow<Set<Long>> = combine(library, _selection) { lib, sel ->
        lib?.workouts.orEmpty().filter { it.id in sel.workoutIds }.flatMap(::exercisesOf).toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** How many exercises the file will hold and how many bytes of photos and videos. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val size: StateFlow<Pair<Int, Long>?> = _selection.mapLatest { transfer.shareSize(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _outcome = MutableStateFlow<ShareOutcome?>(null)
    val outcome: StateFlow<ShareOutcome?> = _outcome.asStateFlow()

    fun toggleWorkout(id: Long) = _selection.update { it.copy(workoutIds = it.workoutIds.toggle(id)) }

    fun toggleExercise(id: Long) = _selection.update { it.copy(exerciseIds = it.exerciseIds.toggle(id)) }

    fun setIncludeMedia(include: Boolean) = _selection.update { it.copy(includeMedia = include) }

    fun prepareShare(title: String) = run {
        val (file, result) = transfer.shareFile(_selection.value, title)
        ShareOutcome.Ready(file, result)
    }

    fun saveTo(uri: Uri) = run { ShareOutcome.Saved(transfer.exportShareTo(_selection.value, uri)) }

    fun markLaunched() = _outcome.update { (it as? ShareOutcome.Ready)?.copy(launched = true) ?: it }

    fun clearOutcome() {
        _outcome.value = null
    }

    private fun run(task: suspend () -> ShareOutcome) {
        if (_busy.value) return
        _busy.value = true
        _outcome.value = null
        viewModelScope.launch {
            _outcome.value = try {
                task()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ShareOutcome.Failed(e)
            }
            _busy.value = false
        }
    }

    private fun Set<Long>.toggle(id: Long) = if (id in this) this - id else this + id

    private fun exercisesOf(workout: WorkoutTemplate): List<Long> = workout.blocks.flatMap { block ->
        listOfNotNull((block.roundActiveRest as? ActiveRest.Specific)?.exerciseId) +
            block.items.flatMap { item -> listOfNotNull(item.exerciseId, (item.activeRest as? ActiveRest.Specific)?.exerciseId) }
    }
}
