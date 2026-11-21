package com.yonizilber.flownics.feature.plan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yonizilber.flownics.data.repository.ActivityRepository
import com.yonizilber.flownics.data.repository.PlanRepository
import com.yonizilber.flownics.domain.model.ActivityFormat
import com.yonizilber.flownics.domain.model.ActivityType
import com.yonizilber.flownics.domain.model.PlanKind
import com.yonizilber.flownics.domain.model.PlanStatus
import com.yonizilber.flownics.domain.model.PlannedStep
import com.yonizilber.flownics.domain.model.PlannedWorkout
import com.yonizilber.flownics.domain.model.SegmentKind
import com.yonizilber.flownics.domain.model.WorkoutTemplate
import com.yonizilber.flownics.domain.plan.DoneItem
import com.yonizilber.flownics.domain.plan.PlanMatching
import com.yonizilber.flownics.domain.plan.StepComparison
import com.yonizilber.flownics.domain.repository.ExerciseRepository
import com.yonizilber.flownics.domain.repository.SessionRepository
import com.yonizilber.flownics.domain.repository.WorkoutRepository
import com.yonizilber.flownics.domain.workout.WorkoutCompiler
import com.yonizilber.flownics.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

data class StepDraft(
    val key: Long,
    val kind: SegmentKind,
    val duration: String = "",
    val distanceKm: String = "",
    /** "4:20-4:30" or "4:30". */
    val pace: String = "",
    val maxHeartRate: String = "",
    val note: String = "",
)

data class PlanEditorState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime? = null,
    val kind: PlanKind = PlanKind.ACTIVITY,
    val workoutId: Long? = null,
    val activityType: ActivityType = ActivityType.RUN,
    val title: String = "",
    val durationMinutes: String = "",
    val notes: String = "",
    val steps: List<StepDraft> = emptyList(),
    val skipped: Boolean = false,
    val errors: Set<String> = emptySet(),
    val closed: Boolean = false,
)

/** What was done for a saved plan, and (for an activity with parts) how each part went. */
data class PlanOutcome(val done: DoneItem?, val comparison: List<StepComparison>)

@HiltViewModel
class PlanEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val plans: PlanRepository,
    private val workouts: WorkoutRepository,
    private val exercises: ExerciseRepository,
    sessions: SessionRepository,
    activities: ActivityRepository,
) : ViewModel() {

    private val planId = savedStateHandle.get<Long>(Destination.ARG_PLAN_ID) ?: 0L
    private val initialDay = savedStateHandle.get<Long>(Destination.ARG_EPOCH_DAY)?.takeIf { it > 0 }?.let(LocalDate::ofEpochDay)
    private var original: PlannedWorkout? = null
    private var nextKey = 1L

    private val _state = MutableStateFlow(PlanEditorState())
    val state: StateFlow<PlanEditorState> = _state.asStateFlow()

    val workoutList: StateFlow<List<WorkoutTemplate>> = workouts.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val outcome: StateFlow<PlanOutcome?> = combine(plans.observeAll(), sessions.observeCompleted(), activities.observeAll()) { all, s, a ->
        val plan = all.firstOrNull { it.id == planId } ?: return@combine null
        val done = PlanMatching.match(listOf(plan), s, a, ZoneId.systemDefault())[plan.id]
        val segments = (done as? DoneItem.Logged)?.activity?.segments.orEmpty()
        PlanOutcome(done, if (done is DoneItem.Logged && plan.steps.isNotEmpty()) PlanMatching.compare(plan.steps, segments) else emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            val existing = if (planId > 0) plans.get(planId) else null
            original = existing
            _state.value = existing?.let { p ->
                PlanEditorState(
                    loading = false,
                    isNew = false,
                    date = p.date,
                    time = p.time,
                    kind = p.kind,
                    workoutId = p.workoutId,
                    activityType = p.activityType ?: ActivityType.RUN,
                    title = p.title,
                    durationMinutes = p.durationMinutes?.toString().orEmpty(),
                    notes = p.notes,
                    steps = p.steps.map { it.toDraft() },
                    skipped = p.status == PlanStatus.SKIPPED,
                )
            } ?: PlanEditorState(loading = false, date = initialDay ?: LocalDate.now())
            pristine = _state.value
        }
    }

    /** The saved plan, to read. */
    val saved: PlannedWorkout? get() = original

    /** The form as it was loaded, to tell whether anything was changed and to go back to it. */
    private var pristine: PlanEditorState? = null

    fun hasChanges(): Boolean = pristine?.let { _state.value.copy(errors = emptySet()) != it } ?: false

    fun discardEdits() {
        pristine?.let { _state.value = it }
    }

    fun update(transform: (PlanEditorState) -> PlanEditorState) = _state.update { transform(it).copy(errors = emptySet()) }

    fun addStep(kind: SegmentKind) = update { it.copy(steps = it.steps + StepDraft(nextKey++, kind)) }

    fun updateStep(key: Long, transform: (StepDraft) -> StepDraft) = update { s -> s.copy(steps = s.steps.map { if (it.key == key) transform(it) else it }) }

    fun removeStep(key: Long) = update { s -> s.copy(steps = s.steps.filterNot { it.key == key }) }

    fun addRepeats(reps: Int, work: StepDraft, recovery: StepDraft?) = update { s ->
        s.copy(steps = s.steps + (1..reps.coerceIn(1, 50)).flatMap { listOfNotNull(work.copy(key = nextKey++), recovery?.copy(key = nextKey++)) })
    }

    fun save() {
        val s = _state.value
        val errors = mutableSetOf<String>()
        if (s.kind == PlanKind.WORKOUT && s.workoutId == null) errors += "workout"
        val steps = if (s.kind == PlanKind.ACTIVITY) s.steps.mapNotNull { d ->
            val duration = d.duration.takeIf { it.isNotBlank() }?.let { ActivityFormat.parseDuration(it) ?: run { errors += "steps"; null } }
            val distance = d.distanceKm.takeIf { it.isNotBlank() }?.let { ActivityFormat.parseKm(it) ?: run { errors += "steps"; null } }
            val pace = d.pace.takeIf { it.isNotBlank() }?.let { parsePaceRange(it) ?: run { errors += "steps"; null } }
            val hr = d.maxHeartRate.takeIf { it.isNotBlank() }?.let { ActivityFormat.parseInt(it, 60..230) ?: run { errors += "steps"; null } }
            if (duration == null && distance == null) null else PlannedStep(d.kind, duration, distance, pace?.first, pace?.second, hr, d.note.trim())
        } else emptyList()
        val minutes = s.durationMinutes.takeIf { it.isNotBlank() }?.let { ActivityFormat.parseInt(it, 1..1440) ?: run { errors += "duration"; null } }
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        viewModelScope.launch {
            val estimated = minutes ?: when (s.kind) {
                PlanKind.WORKOUT -> s.workoutId?.let { workouts.get(it) }?.let { w ->
                    val library = exercises.observeAll().first().associateBy { it.id }
                    (WorkoutCompiler.compile(w, library).estimatedSeconds + 59) / 60
                }
                PlanKind.ACTIVITY -> steps.mapNotNull { it.estimatedSeconds() }.sum().takeIf { it > 0 }?.let { (it + 59) / 60 }
            }
            plans.save(
                PlannedWorkout(
                    id = original?.id ?: 0,
                    date = s.date,
                    time = s.time,
                    kind = s.kind,
                    workoutId = if (s.kind == PlanKind.WORKOUT) s.workoutId else null,
                    activityType = if (s.kind == PlanKind.ACTIVITY) s.activityType else null,
                    title = s.title.trim(),
                    durationMinutes = estimated,
                    notes = s.notes.trim(),
                    steps = steps,
                    status = if (s.skipped) PlanStatus.SKIPPED else PlanStatus.PLANNED,
                    createdAtMillis = original?.createdAtMillis ?: 0,
                ),
            )
            _state.update { it.copy(closed = true) }
        }
    }

    fun delete() {
        val id = original?.id ?: return
        viewModelScope.launch {
            plans.delete(id)
            _state.update { it.copy(closed = true) }
        }
    }

    private fun PlannedStep.toDraft() = StepDraft(
        key = nextKey++,
        kind = kind,
        duration = durationSeconds?.let(ActivityFormat::formatDuration).orEmpty(),
        distanceKm = distanceMeters?.let(ActivityFormat::formatKm).orEmpty(),
        pace = listOfNotNull(paceFastSecondsPerKm, paceSlowSecondsPerKm).distinct().joinToString("-") { ActivityFormat.formatPace(it) },
        maxHeartRate = maxHeartRate?.toString().orEmpty(),
        note = note,
    )

    companion object {
        /** "4:20-4:30" (either order, any dash) or "4:30" to (faster, slower) seconds per km. */
        fun parsePaceRange(text: String): Pair<Int, Int>? {
            val parts = text.split('-', '–', '—').map { it.trim() }.filter { it.isNotEmpty() }
            val paces = parts.map { ActivityFormat.parseDuration(it, bareNumberIsMinutes = true) ?: return null }
            if (paces.isEmpty() || paces.size > 2 || paces.any { it !in 120..1200 }) return null
            return paces.min() to paces.max()
        }
    }
}
