package com.yonizilber.flownics.feature.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yonizilber.flownics.calendar.CalendarSync
import com.yonizilber.flownics.data.repository.ActivityRepository
import com.yonizilber.flownics.data.repository.PlanRepository
import com.yonizilber.flownics.domain.model.Activity
import com.yonizilber.flownics.domain.model.PlannedWorkout
import com.yonizilber.flownics.domain.model.SessionHeader
import com.yonizilber.flownics.domain.plan.DoneItem
import com.yonizilber.flownics.domain.plan.PlanMatching
import com.yonizilber.flownics.domain.repository.SessionRepository
import com.yonizilber.flownics.domain.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject

/** One day of the week view: what was planned (with whether it was done) and what else was done. */
data class DayEntry(
    val date: LocalDate,
    val plans: List<Pair<PlannedWorkout, DoneItem?>>,
    /** Done that day without a plan. */
    val unplanned: List<DoneItem>,
)

data class WeekState(
    val start: LocalDate,
    val days: List<DayEntry>,
    val workoutNames: Map<Long, String>,
    val planned: Int,
    val done: Int,
    val km: Double,
    val calendarOn: Boolean,
)

@HiltViewModel
class WeekViewModel @Inject constructor(
    plans: PlanRepository,
    sessions: SessionRepository,
    activities: ActivityRepository,
    workouts: WorkoutRepository,
    calendar: CalendarSync,
) : ViewModel() {

    private val zone = ZoneId.systemDefault()
    private val firstDay = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    private val weekStart = MutableStateFlow(LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(firstDay)))

    val state: StateFlow<WeekState?> = combine(
        combine(plans.observeAll(), sessions.observeCompleted(), activities.observeAll(), ::Triple),
        workouts.observeAll(),
        weekStart,
        calendar.settings,
    ) { (allPlans, allSessions, allActivities), allWorkouts, start, sync ->
        build(start, allPlans, allSessions, allActivities, allWorkouts.associate { it.id to it.name }, sync.enabled)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun previous() = weekStart.update { it.minusWeeks(1) }
    fun next() = weekStart.update { it.plusWeeks(1) }
    fun thisWeek() = weekStart.update { LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(firstDay)) }

    private fun build(
        start: LocalDate,
        plans: List<PlannedWorkout>,
        sessions: List<SessionHeader>,
        activities: List<Activity>,
        names: Map<Long, String>,
        calendarOn: Boolean,
    ): WeekState {
        val end = start.plusDays(6)
        fun dayOf(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val weekPlans = plans.filter { it.date in start..end }
        val weekSessions = sessions.filter { dayOf(it.startedAtMillis) in start..end }
        val weekActivities = activities.filter { dayOf(it.startedAtMillis) in start..end }
        val matched = PlanMatching.match(weekPlans, weekSessions, weekActivities, zone)
        val used = matched.values.toSet()
        val done = (weekSessions.map { DoneItem.Workout(it) } + weekActivities.map { DoneItem.Logged(it) }).sortedBy { it.atMillis }
        val days = (0..6L).map { offset ->
            val date = start.plusDays(offset)
            DayEntry(
                date = date,
                plans = weekPlans.filter { it.date == date }.sortedBy { it.time }.map { it to matched[it.id] },
                unplanned = done.filter { it !in used && dayOf(it.atMillis) == date },
            )
        }
        return WeekState(
            start = start,
            days = days,
            workoutNames = names,
            planned = weekPlans.size,
            done = done.size,
            km = weekActivities.sumOf { it.distanceMeters ?: 0 } / 1000.0,
            calendarOn = calendarOn,
        )
    }
}
