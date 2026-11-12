package com.yonizilber.flownics.feature.journal

import kotlinx.coroutines.flow.map
import com.yonizilber.flownics.domain.fit.TrainingLoad
import com.yonizilber.flownics.domain.fit.LoadSummary
import com.yonizilber.flownics.data.garmin.ZonesInfo
import com.yonizilber.flownics.data.garmin.Zones
import java.time.DayOfWeek
import java.time.YearMonth
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

data class MonthState(val month: YearMonth, val firstDay: DayOfWeek, val days: List<DayEntry>, val workoutNames: Map<Long, String>)

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
    zones: Zones,
) : ViewModel() {

    /** Training load from heart rate, for the last 28 days; null until there is a heart rate to go by. */
    val load: StateFlow<Pair<LoadSummary, ZonesInfo>?> = zones.info.map { info ->
        info?.let { TrainingLoad.summary(it.recordings, it.zones, LocalDate.now(zone), zone) to it }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)


    private val zone = ZoneId.systemDefault()
    private val firstDay = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    private val weekStart = MutableStateFlow(LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(firstDay)))

    val state: StateFlow<WeekState?> = combine(
        combine(plans.observeAll(), sessions.observeCompleted(), activities.observeAll(), ::Triple),
        workouts.observeAll(),
        weekStart,
        calendar.settings,
    ) { (allPlans, allSessions, allActivities), allWorkouts, start, sync ->
        build(start, 7, allPlans, allSessions, allActivities, allWorkouts.associate { it.id to it.name }, sync.enabled)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val month = MutableStateFlow(YearMonth.now(zone))

    /** The month view: every day of [YearMonth], with what was planned and done. */
    val monthState: StateFlow<MonthState?> = combine(
        combine(plans.observeAll(), sessions.observeCompleted(), activities.observeAll(), ::Triple),
        workouts.observeAll(),
        month,
    ) { (allPlans, allSessions, allActivities), allWorkouts, m ->
        val days = build(m.atDay(1), m.lengthOfMonth(), allPlans, allSessions, allActivities, allWorkouts.associate { it.id to it.name }, false)
        MonthState(m, firstDay, days.days, days.workoutNames)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun previousMonth() = month.update { it.minusMonths(1) }
    fun nextMonth() = month.update { it.plusMonths(1) }

    fun previous() = weekStart.update { it.minusWeeks(1) }
    fun next() = weekStart.update { it.plusWeeks(1) }
    fun thisWeek() = weekStart.update { LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(firstDay)) }

    private fun build(
        start: LocalDate,
        length: Int,
        plans: List<PlannedWorkout>,
        sessions: List<SessionHeader>,
        activities: List<Activity>,
        names: Map<Long, String>,
        calendarOn: Boolean,
    ): WeekState {
        val end = start.plusDays(length - 1L)
        fun dayOf(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val weekPlans = plans.filter { it.date in start..end }
        val weekSessions = sessions.filter { dayOf(it.startedAtMillis) in start..end }
        val weekActivities = activities.filter { dayOf(it.startedAtMillis) in start..end }
        val matched = PlanMatching.match(weekPlans, weekSessions, weekActivities, zone)
        val used = matched.values.toSet()
        val done = (weekSessions.map { DoneItem.Workout(it) } + weekActivities.map { DoneItem.Logged(it) }).sortedBy { it.atMillis }
        val days = (0 until length.toLong()).map { offset ->
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
