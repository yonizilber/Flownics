package com.yonizilber.flownics.feature.history.detail

import com.yonizilber.flownics.data.garmin.Zones
import com.yonizilber.flownics.data.garmin.ZonesInfo
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import com.yonizilber.flownics.domain.model.SetHeart
import com.yonizilber.flownics.domain.model.Activity
import com.yonizilber.flownics.domain.fit.WorkoutHeart
import com.yonizilber.flownics.domain.fit.HeartPoint
import com.yonizilber.flownics.domain.fit.FitActivity
import com.yonizilber.flownics.data.repository.ActivityRepository
import com.yonizilber.flownics.data.garmin.RecordingLinker
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yonizilber.flownics.domain.history.ExerciseProgress
import com.yonizilber.flownics.domain.history.ProgressMetric
import com.yonizilber.flownics.domain.model.SetLog
import com.yonizilber.flownics.domain.model.WorkoutSession
import com.yonizilber.flownics.domain.repository.ExerciseRepository
import com.yonizilber.flownics.domain.repository.SessionRepository
import com.yonizilber.flownics.domain.repository.WorkoutRepository
import com.yonizilber.flownics.domain.workout.WorkStep
import com.yonizilber.flownics.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One exercise's sets in this workout, in the order they were done. */
data class ExerciseSets(
    val exerciseId: Long,
    /** The current name, or the name saved with the log when the exercise was deleted. */
    val name: String,
    /** Still in the library, so its page can be opened. */
    val exists: Boolean,
    val sets: List<SetLog>,
    /** Metrics this workout beat every earlier one on. */
    val newRecords: Set<ProgressMetric>,
)

sealed interface SessionDetailUiState {
    data object Loading : SessionDetailUiState
    data object NotFound : SessionDetailUiState
    data class Loaded(
        val session: WorkoutSession,
        val logs: List<SetLog>,
        val exercises: List<ExerciseSets>,
        /** Work steps in the plan; more than [logs] means the workout was ended early. */
        val plannedSets: Int,
        val templateExists: Boolean,
    ) : SessionDetailUiState
}

/** A watch recording made during this workout: its totals, its heart rate over time, set by set. */
data class WatchData(val activity: Activity, val heart: List<HeartPoint>) {
    val bySet: Map<Int, SetHeart> get() = activity.metrics?.sets.orEmpty().associateBy { it.stepIndex }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessions: SessionRepository,
    exerciseRepository: ExerciseRepository,
    workoutRepository: WorkoutRepository,
    activities: ActivityRepository,
    private val linker: RecordingLinker,
    zones: Zones,
) : ViewModel() {

    val zonesInfo: StateFlow<ZonesInfo?> = zones.info


    private val sessionId: Long = checkNotNull(savedStateHandle.get<Long>(Destination.ARG_SESSION_ID))

    val uiState: StateFlow<SessionDetailUiState> = combine(
        sessions.observe(sessionId),
        sessions.observeLogs(sessionId),
        sessions.observeCompletedLogs(),
        exerciseRepository.observeAll(),
        workoutRepository.observeAll(),
    ) { session, logs, history, exercises, templates ->
        if (session == null) return@combine SessionDetailUiState.NotFound
        val library = exercises.associateBy { it.id }
        val historyByExercise = history.groupBy { it.exerciseId }
        SessionDetailUiState.Loaded(
            session = session,
            logs = logs,
            exercises = logs.groupBy { it.exerciseId }.map { (exerciseId, sets) ->
                ExerciseSets(
                    exerciseId = exerciseId,
                    name = library[exerciseId]?.name ?: sets.first().exerciseName,
                    exists = exerciseId in library,
                    sets = sets,
                    newRecords = ExerciseProgress.newRecords(
                        ExerciseProgress.bySession(historyByExercise[exerciseId].orEmpty()),
                        sessionId,
                    ),
                )
            },
            plannedSets = session.steps.count { it is WorkStep },
            templateExists = session.templateId != null && templates.any { it.id == session.templateId },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionDetailUiState.Loading)

    val watch: StateFlow<WatchData?> = activities.observeAttachedTo(sessionId)
        .mapLatest { a ->
            a?.let {
                val heart = withContext(Dispatchers.IO) {
                    runCatching { FitActivity.heart(linker.file(it.id).readBytes()) }.getOrDefault(emptyList())
                }
                WatchData(it, WorkoutHeart.thin(heart))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Lists the recording as its own activity again, and doesn't join it to this workout any more. */
    fun keepApart() {
        val id = watch.value?.activity?.id ?: return
        viewModelScope.launch { linker.keepApart(id) }
    }

    fun updateNotes(notes: String) {
        viewModelScope.launch { sessions.updateNotes(sessionId, notes.trim()) }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            sessions.delete(sessionId)
            onDone()
        }
    }
}
