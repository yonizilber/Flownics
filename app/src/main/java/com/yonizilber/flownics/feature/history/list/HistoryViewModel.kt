package com.yonizilber.flownics.feature.history.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yonizilber.flownics.data.repository.ActivityRepository
import com.yonizilber.flownics.domain.history.MuscleLoad
import com.yonizilber.flownics.domain.model.Activity
import com.yonizilber.flownics.domain.history.PeriodTotals
import com.yonizilber.flownics.domain.history.volumeKg
import com.yonizilber.flownics.domain.model.SessionHeader
import com.yonizilber.flownics.domain.repository.ExerciseRepository
import com.yonizilber.flownics.domain.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class HistoryEntry(val header: SessionHeader, val setsDone: Int, val setsSkipped: Int, val volumeKg: Double)

/** One line of the history: a workout from the player, or an activity logged by hand. */
sealed interface HistoryItem {
    val atMillis: Long
    val key: String

    data class Workout(val entry: HistoryEntry) : HistoryItem {
        override val atMillis get() = entry.header.startedAtMillis
        override val key get() = "s${entry.header.id}"
    }

    data class Done(val activity: Activity) : HistoryItem {
        override val atMillis get() = activity.startedAtMillis
        override val key get() = "a${activity.id}"
    }
}

data class HistoryUiState(
    val loading: Boolean = true,
    /** Newest first. */
    val items: List<HistoryItem> = emptyList(),
    val lastWeek: PeriodTotals = PeriodTotals(0, 0, 0, 0.0),
    /** Kilometres covered in the last 7 days by logged activities. */
    val lastWeekKm: Double = 0.0,
    val lastWeekMuscles: MuscleLoad = MuscleLoad(emptyMap(), 0),
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    sessions: SessionRepository,
    exercises: ExerciseRepository,
    activities: ActivityRepository,
) : ViewModel() {

    val uiState: StateFlow<HistoryUiState> = combine(
        sessions.observeCompleted(),
        sessions.observeCompletedLogs(),
        exercises.observeAll(),
        activities.observeAll(),
    ) { headers, logs, library, done ->
        val bySession = logs.groupBy { it.sessionId }
        // A rolling week rather than a calendar one: it reads the same whatever day the week starts on.
        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(WINDOW_DAYS)
        val recent = headers.filter { it.startedAtMillis >= since }
        val recentIds = recent.map { it.id }.toSet()
        val recentDone = done.filter { it.startedAtMillis >= since }
        val workoutTotals = PeriodTotals.of(recent, logs)
        HistoryUiState(
            loading = false,
            items = (
                headers.map { header ->
                    val sets = bySession[header.id].orEmpty()
                    HistoryItem.Workout(HistoryEntry(header, sets.count { !it.skipped }, sets.count { it.skipped }, sets.volumeKg()))
                } + done.map { HistoryItem.Done(it) }
                ).sortedByDescending { it.atMillis },
            lastWeek = workoutTotals.copy(
                workouts = workoutTotals.workouts + recentDone.size,
                seconds = workoutTotals.seconds + recentDone.sumOf { it.durationSeconds.toLong() },
            ),
            lastWeekKm = recentDone.sumOf { it.distanceMeters ?: 0 } / 1000.0,
            lastWeekMuscles = MuscleLoad.of(logs.filter { it.sessionId in recentIds }, library.associateBy { it.id }),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    private companion object {
        const val WINDOW_DAYS = 7L
    }
}
