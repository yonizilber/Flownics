package com.yonizilber.flownics.feature.activity

import com.yonizilber.flownics.data.garmin.Zones
import com.yonizilber.flownics.data.garmin.ZonesInfo
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yonizilber.flownics.data.repository.ActivityRepository
import com.yonizilber.flownics.domain.model.Activity
import com.yonizilber.flownics.domain.model.ActivityFormat
import com.yonizilber.flownics.domain.model.ActivitySegment
import com.yonizilber.flownics.domain.model.ActivitySource
import com.yonizilber.flownics.domain.model.ActivityType
import com.yonizilber.flownics.domain.model.SegmentKind
import com.yonizilber.flownics.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/** One part of the activity as typed: text, so half-typed numbers aren't lost or rounded. */
data class SegmentDraft(
    val key: Long,
    val kind: SegmentKind,
    val duration: String = "",
    val distanceKm: String = "",
    val avgHeartRate: String = "",
    // Recorded by the watch; not editable, carried over so an edit doesn't lose them.
    val cadenceSpm: Int? = null,
    val stepLengthCm: Int? = null,
)

data class ActivityEditorState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val type: ActivityType = ActivityType.RUN,
    val title: String = "",
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.now().withSecond(0).withNano(0),
    val duration: String = "",
    val distanceKm: String = "",
    val avgHeartRate: String = "",
    val maxHeartRate: String = "",
    val elevation: String = "",
    val rpe: Int? = null,
    val notes: String = "",
    val segments: List<SegmentDraft> = emptyList(),
    /** Fields the user must fix before saving (named by [ActivityField]). */
    val errors: Set<ActivityField> = emptySet(),
    val savedId: Long? = null,
    val deleted: Boolean = false,
)

enum class ActivityField { DURATION, DISTANCE, AVG_HR, MAX_HR, ELEVATION, SEGMENTS }

@HiltViewModel
class ActivityEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ActivityRepository,
    zones: Zones,
    private val runs: com.yonizilber.flownics.data.run.RunStructures,
) : ViewModel() {

    /** A recorded run's structure, and its readings every 10 s for the chart. */
    private val _structure = MutableStateFlow<Pair<com.yonizilber.flownics.domain.run.RunStructure, List<com.yonizilber.flownics.domain.run.RunAnalysis.DetailRow>>?>(null)
    val structure: StateFlow<Pair<com.yonizilber.flownics.domain.run.RunStructure, List<com.yonizilber.flownics.domain.run.RunAnalysis.DetailRow>>?> = _structure.asStateFlow()

    val zonesInfo: StateFlow<ZonesInfo?> = zones.info


    private val activityId = savedStateHandle.get<Long>(Destination.ARG_ACTIVITY_ID) ?: 0L
    private val zone = ZoneId.systemDefault()
    private var original: Activity? = null
    private var nextKey = 1L

    private val _state = MutableStateFlow(ActivityEditorState())
    val state: StateFlow<ActivityEditorState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val existing = if (activityId > 0) repository.get(activityId) else null
            original = existing
            _state.value = existing?.toState() ?: ActivityEditorState(loading = false)
            pristine = _state.value
            existing?.let { a ->
                val s = runs.get(a) ?: return@let
                val rows = runs.samples(a)?.let { com.yonizilber.flownics.domain.run.RunAnalysis.detail(it, 0, s.durationS, CHART_STEP) }.orEmpty()
                _structure.value = s to rows
            }
        }
    }

    /** The saved activity: what the screen shows to read, and "add to calendar". */
    val saved: Activity? get() = original

    /** The form as it was loaded, to tell whether anything was changed and to go back to it. */
    private var pristine: ActivityEditorState? = null

    fun hasChanges(): Boolean = pristine?.let { _state.value.copy(errors = emptySet()) != it } ?: false

    /** Back to the saved values, nothing kept from the edit. */
    fun discardEdits() {
        pristine?.let { _state.value = it }
    }

    fun update(transform: (ActivityEditorState) -> ActivityEditorState) = _state.update { transform(it).copy(errors = emptySet()) }

    fun addSegment(kind: SegmentKind) = update { it.copy(segments = it.segments + SegmentDraft(nextKey++, kind)) }

    fun updateSegment(key: Long, transform: (SegmentDraft) -> SegmentDraft) =
        update { s -> s.copy(segments = s.segments.map { if (it.key == key) transform(it) else it }) }

    fun removeSegment(key: Long) = update { s -> s.copy(segments = s.segments.filterNot { it.key == key }) }

    /** Adds [reps] repeats of work then recovery, e.g. 6 × (1 km, 2:00 jog). */
    fun addRepeats(reps: Int, workDuration: String, workKm: String, recoveryDuration: String, recoveryKm: String) = update { s ->
        val added = (1..reps.coerceIn(1, MAX_REPEATS)).flatMap {
            listOf(
                SegmentDraft(nextKey++, SegmentKind.WORK, workDuration, workKm),
                SegmentDraft(nextKey++, SegmentKind.RECOVERY, recoveryDuration, recoveryKm),
            )
        }.let { list -> if (recoveryDuration.isBlank() && recoveryKm.isBlank()) list.filter { it.kind == SegmentKind.WORK } else list }
        s.copy(segments = s.segments + added)
    }

    fun save() {
        val s = _state.value
        val errors = mutableSetOf<ActivityField>()
        val segments = s.segments.mapNotNull { d ->
            val duration = d.duration.takeIf { it.isNotBlank() }?.let { ActivityFormat.parseDuration(it) ?: run { errors += ActivityField.SEGMENTS; null } }
            val distance = d.distanceKm.takeIf { it.isNotBlank() }?.let { ActivityFormat.parseKm(it) ?: run { errors += ActivityField.SEGMENTS; null } }
            val hr = d.avgHeartRate.takeIf { it.isNotBlank() }?.let { ActivityFormat.parseInt(it, HR_RANGE) ?: run { errors += ActivityField.SEGMENTS; null } }
            if (duration == null && distance == null) null else ActivitySegment(d.kind, duration, distance, hr, cadenceSpm = d.cadenceSpm, stepLengthCm = d.stepLengthCm)
        }
        // A blank total is filled from the parts, so intervals don't have to be added up by hand.
        val duration = if (s.duration.isBlank()) {
            segments.mapNotNull { it.durationSeconds }.sum().takeIf { it > 0 && segments.all { seg -> seg.durationSeconds != null } }
        } else {
            ActivityFormat.parseDuration(s.duration)
        }
        if (duration == null) errors += ActivityField.DURATION
        val distance = if (s.distanceKm.isBlank()) {
            segments.mapNotNull { it.distanceMeters }.sum().takeIf { it > 0 }
        } else {
            ActivityFormat.parseKm(s.distanceKm) ?: run { errors += ActivityField.DISTANCE; null }
        }
        val avg = optional(s.avgHeartRate, HR_RANGE, ActivityField.AVG_HR, errors)
        val max = optional(s.maxHeartRate, HR_RANGE, ActivityField.MAX_HR, errors)
        val elevation = optional(s.elevation, 0..10_000, ActivityField.ELEVATION, errors)
        if (errors.isNotEmpty() || duration == null) {
            _state.update { it.copy(errors = errors) }
            return
        }
        val activity = Activity(
            id = original?.id ?: 0,
            type = s.type,
            title = s.title.trim(),
            startedAtMillis = s.date.atTime(s.time).atZone(zone).toInstant().toEpochMilli(),
            durationSeconds = duration,
            distanceMeters = distance,
            avgHeartRate = avg,
            maxHeartRate = max,
            elevationGainMeters = elevation,
            rpe = s.rpe,
            notes = s.notes.trim(),
            segments = segments,
            // A recording stays one: its source, its id (so it isn't imported again) and its samples' metrics.
            source = original?.source ?: ActivitySource.MANUAL,
            externalId = original?.externalId,
            metrics = original?.metrics,
            createdAtMillis = original?.createdAtMillis ?: 0,
        )
        viewModelScope.launch {
            val id = repository.save(activity)
            _state.update { it.copy(savedId = id) }
        }
    }

    fun delete() {
        val id = original?.id ?: return
        viewModelScope.launch {
            repository.delete(id)
            _state.update { it.copy(deleted = true) }
        }
    }

    private fun optional(text: String, range: IntRange, field: ActivityField, errors: MutableSet<ActivityField>): Int? =
        if (text.isBlank()) null else ActivityFormat.parseInt(text, range) ?: run { errors += field; null }

    private fun Activity.toState(): ActivityEditorState {
        val at = Instant.ofEpochMilli(startedAtMillis).atZone(zone)
        return ActivityEditorState(
            loading = false,
            isNew = false,
            type = type,
            title = title,
            date = at.toLocalDate(),
            time = at.toLocalTime().withSecond(0).withNano(0),
            duration = ActivityFormat.formatDuration(durationSeconds),
            distanceKm = distanceMeters?.let(ActivityFormat::formatKm).orEmpty(),
            avgHeartRate = avgHeartRate?.toString().orEmpty(),
            maxHeartRate = maxHeartRate?.toString().orEmpty(),
            elevation = elevationGainMeters?.toString().orEmpty(),
            rpe = rpe,
            notes = notes,
            segments = segments.map {
                SegmentDraft(
                    nextKey++,
                    it.kind,
                    it.durationSeconds?.let(ActivityFormat::formatDuration).orEmpty(),
                    it.distanceMeters?.let(ActivityFormat::formatKm).orEmpty(),
                    it.avgHeartRate?.toString().orEmpty(),
                    it.cadenceSpm,
                    it.stepLengthCm,
                )
            },
        )
    }

    private companion object {
        const val CHART_STEP = 10
        val HR_RANGE = 30..250
        const val MAX_REPEATS = 50
    }
}
