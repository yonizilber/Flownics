package com.yonizilber.flownics.calendar

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.yonizilber.flownics.R
import com.yonizilber.flownics.data.di.ApplicationScope
import com.yonizilber.flownics.data.local.db.dao.PlanDao
import com.yonizilber.flownics.data.local.db.entity.CalendarLinkEntity
import com.yonizilber.flownics.data.repository.ActivityRepository
import com.yonizilber.flownics.data.repository.PlanRepository
import com.yonizilber.flownics.domain.model.Activity
import com.yonizilber.flownics.domain.model.ActivityFormat
import com.yonizilber.flownics.domain.model.ActivityType
import com.yonizilber.flownics.domain.model.PlanKind
import com.yonizilber.flownics.domain.model.PlanStatus
import com.yonizilber.flownics.domain.model.PlannedStep
import com.yonizilber.flownics.domain.model.PlannedWorkout
import com.yonizilber.flownics.domain.model.SegmentKind
import com.yonizilber.flownics.domain.model.SessionHeader
import com.yonizilber.flownics.domain.plan.DoneItem
import com.yonizilber.flownics.domain.plan.PlanMatching
import com.yonizilber.flownics.domain.repository.SessionRepository
import com.yonizilber.flownics.domain.repository.WorkoutRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A calendar the user can write to, for choosing where Flownics puts its events. */
data class WritableCalendar(val id: Long, val name: String, val account: String, val color: Int)

data class CalendarSyncSettings(
    val enabled: Boolean = false,
    val calendarId: Long? = null,
    val calendarName: String = "",
    /** Also show workouts and activities done without a plan. */
    val includeDone: Boolean = true,
    val lastError: String? = null,
    val lastSyncedAtMillis: Long? = null,
)

/**
 * Keeps one calendar the user chose (ideally one named "Flownics") showing the plan: every planned
 * workout as an event, marked ✓ once done, and (if they want) what they did without a plan.
 *
 * It only ever touches events it created itself (remembered in calendar_links), never anything
 * else in the calendar. It works by reconciling: whenever the plan or the history changes, it
 * works out what the calendar should show and writes the difference. A failure (permission taken
 * away, calendar removed) is kept and shown in the settings; it never blocks saving a plan.
 */
@Singleton
class CalendarSync @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val dao: PlanDao,
    private val plans: PlanRepository,
    private val sessions: SessionRepository,
    private val activities: ActivityRepository,
    private val workouts: WorkoutRepository,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<CalendarSyncSettings> = _settings.asStateFlow()
    private val mutex = Mutex()
    private var started = false

    /** Starts following the plan and history. Called once, from the application. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            // Only what decides the events: a sync writing its own result (time, error) into the
            // settings must not start another sync.
            val wanted = _settings.map { Target(it.enabled, it.calendarId, it.includeDone) }.distinctUntilChanged()
            combine(plans.observeAll(), sessions.observeCompleted(), activities.observeAll(), workouts.observeAll(), wanted) { p, s, a, w, target ->
                Snapshot(p, s, a, w.associate { it.id to it.name }, target)
            }
                .debounce(DEBOUNCE_MS)
                .collect { snapshot -> if (snapshot.target.enabled) reconcile(snapshot) }
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Calendars on this phone that accept new events (Google accounts and local ones). */
    suspend fun writableCalendars(): List<WritableCalendar> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
        )
        val where = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        buildList {
            context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, where, args, null)?.use { c ->
                while (c.moveToNext()) add(WritableCalendar(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3)))
            }
        }.sortedWith(compareByDescending<WritableCalendar> { it.name.contains("flownics", ignoreCase = true) }.thenBy { it.name.lowercase() })
    }

    /** Turns syncing on into [calendar]. Events already made in another calendar are moved. */
    fun enable(calendar: WritableCalendar) {
        val old = _settings.value
        save(old.copy(enabled = true, calendarId = calendar.id, calendarName = calendar.name, lastError = null))
        if (old.calendarId != null && old.calendarId != calendar.id) scope.launch { removeAll() }
    }

    fun setIncludeDone(include: Boolean) = save(_settings.value.copy(includeDone = include))

    /** Stops syncing. With [removeEvents], also deletes every event Flownics made. */
    fun disable(removeEvents: Boolean) {
        save(_settings.value.copy(enabled = false))
        if (removeEvents) scope.launch { removeAll() }
    }

    private suspend fun removeAll() = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                dao.links().forEach { link ->
                    deleteEvent(link.eventId)
                    dao.deleteLink(link.itemKey)
                }
            }.onFailure { fail(it) }
        }
    }

    private suspend fun reconcile(s: Snapshot) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val calendarId = s.target.calendarId ?: return@withContext
            if (!hasPermission()) {
                fail(SecurityException("no calendar permission"))
                return@withContext
            }
            runCatching {
                val zone = ZoneId.systemDefault()
                val matched = PlanMatching.match(s.plans, s.sessions, s.activities, zone)
                val windowStart = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(DONE_WINDOW_DAYS)
                val desired = linkedMapOf<String, Event>()
                s.plans.forEach { p -> desired["plan:${p.id}"] = planEvent(p, matched[p.id], s.workoutNames, zone) }
                if (s.target.includeDone) {
                    val usedByPlans = matched.values.toSet()
                    s.sessions.filter { it.startedAtMillis >= windowStart && DoneItem.Workout(it) !in usedByPlans }
                        .forEach { desired["session:${it.id}"] = sessionEvent(it) }
                    s.activities.filter { it.startedAtMillis >= windowStart && DoneItem.Logged(it) !in usedByPlans }
                        .forEach { desired["activity:${it.id}"] = activityEvent(it) }
                }
                val existing = s.plans.map { "plan:${it.id}" }.toSet() +
                    s.sessions.associateBy { "session:${it.id}" }.filterValues { it.startedAtMillis < windowStart }.keys +
                    s.activities.associateBy { "activity:${it.id}" }.filterValues { it.startedAtMillis < windowStart }.keys
                val links = dao.links().associateBy { it.itemKey }

                // Gone, or no longer to be shown (e.g. an activity that now fulfils a plan). Old
                // done items outside the window keep their events.
                links.values.filter { it.itemKey !in desired && (it.itemKey !in existing || it.itemKey.startsWith("plan:")) }.forEach {
                    deleteEvent(it.eventId)
                    dao.deleteLink(it.itemKey)
                }
                desired.forEach { (key, event) ->
                    val link = links[key]
                    val hash = event.hashCode()
                    if (link != null && link.calendarId == calendarId && link.contentHash == hash) return@forEach
                    val updated = link != null && link.calendarId == calendarId && updateEvent(link.eventId, event, zone)
                    val eventId = if (updated) link!!.eventId else {
                        link?.let { deleteEvent(it.eventId) }
                        insertEvent(calendarId, event, zone)
                    }
                    dao.putLink(CalendarLinkEntity(key, calendarId, eventId, hash))
                }
                save(_settings.value.copy(lastError = null, lastSyncedAtMillis = System.currentTimeMillis()))
            }.onFailure { fail(it) }
        }
    }

    // ---- Events -----------------------------------------------------------------------------

    private data class Event(val title: String, val description: String, val startMillis: Long, val endMillis: Long, val allDay: Boolean)

    private fun planEvent(p: PlannedWorkout, done: DoneItem?, workoutNames: Map<Long, String>, zone: ZoneId): Event {
        val name = p.title.ifBlank {
            when (p.kind) {
                PlanKind.WORKOUT -> p.workoutId?.let(workoutNames::get).orEmpty()
                PlanKind.ACTIVITY -> context.getString(typeLabel(p.activityType))
            }
        }.ifBlank { context.getString(R.string.plan_untitled) }
        val mark = when {
            done != null -> "✓ "
            p.status == PlanStatus.SKIPPED -> "✗ "
            else -> ""
        }
        val description = buildList {
            if (p.notes.isNotBlank()) add(p.notes)
            p.steps.forEachIndexed { i, step -> add("${i + 1}. " + stepText(step)) }
            done?.let { add(context.getString(R.string.calendar_done_line, doneSummary(it))) }
            add(context.getString(R.string.calendar_made_by))
        }.joinToString("\n")
        val minutes = (p.durationMinutes ?: DEFAULT_MINUTES).coerceAtLeast(MIN_MINUTES)
        return if (p.time == null) {
            val start = p.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            Event(mark + name, description, start, start + TimeUnit.DAYS.toMillis(1), allDay = true)
        } else {
            val start = p.date.atTime(p.time).atZone(zone).toInstant().toEpochMilli()
            Event(mark + name, description, start, start + TimeUnit.MINUTES.toMillis(minutes.toLong()), allDay = false)
        }
    }

    private fun sessionEvent(s: SessionHeader) =
        Event("✓ " + s.workoutName, context.getString(R.string.calendar_made_by), s.startedAtMillis, maxOf(s.endedAtMillis, s.startedAtMillis + 60_000), false)

    private fun activityEvent(a: Activity) =
        Event("✓ " + a.title.ifBlank { context.getString(typeLabel(a.type)) }, listOf(activitySummary(a), context.getString(R.string.calendar_made_by)).joinToString("\n"), a.startedAtMillis, a.endedAtMillis, false)

    private fun doneSummary(done: DoneItem) = when (done) {
        is DoneItem.Workout -> context.getString(R.string.calendar_done_workout, done.session.durationSeconds / 60)
        is DoneItem.Logged -> activitySummary(done.activity)
    }

    private fun activitySummary(a: Activity) = listOfNotNull(
        a.distanceMeters?.let { context.getString(R.string.activity_km, ActivityFormat.formatKm(it)) },
        ActivityFormat.formatDuration(a.durationSeconds),
        a.paceSecondsPerKm?.let { context.getString(R.string.activity_pace, ActivityFormat.formatPace(it)) },
        a.avgHeartRate?.let { context.getString(R.string.activity_hr, it) },
    ).joinToString(" · ")

    private fun stepText(step: PlannedStep): String = listOfNotNull(
        context.getString(segmentLabel(step.kind)),
        step.distanceMeters?.let { context.getString(R.string.activity_km, ActivityFormat.formatKm(it)) },
        step.durationSeconds?.let { ActivityFormat.formatDuration(it) },
        paceRange(step)?.let { context.getString(R.string.activity_pace, it) },
        step.maxHeartRate?.let { context.getString(R.string.plan_hr_max, it) },
        step.note.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    private fun insertEvent(calendarId: Long, e: Event, zone: ZoneId): Long {
        val values = values(e, zone).apply { put(CalendarContract.Events.CALENDAR_ID, calendarId) }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: error("The calendar didn't accept the event")
        return ContentUris.parseId(uri)
    }

    /** False when the event is gone (deleted in the calendar app): it is then made again. */
    private fun updateEvent(eventId: Long, e: Event, zone: ZoneId): Boolean =
        context.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), values(e, zone), null, null) > 0

    private fun deleteEvent(eventId: Long) {
        context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null)
    }

    private fun values(e: Event, zone: ZoneId) = ContentValues().apply {
        put(CalendarContract.Events.TITLE, e.title)
        put(CalendarContract.Events.DESCRIPTION, e.description)
        put(CalendarContract.Events.DTSTART, e.startMillis)
        put(CalendarContract.Events.DTEND, e.endMillis)
        put(CalendarContract.Events.ALL_DAY, if (e.allDay) 1 else 0)
        put(CalendarContract.Events.EVENT_TIMEZONE, if (e.allDay) "UTC" else zone.id)
        put(CalendarContract.Events.AVAILABILITY, if (e.allDay) CalendarContract.Events.AVAILABILITY_FREE else CalendarContract.Events.AVAILABILITY_BUSY)
    }

    // ---- Settings ---------------------------------------------------------------------------

    private fun fail(e: Throwable) {
        val text = when (e) {
            is SecurityException -> context.getString(R.string.calendar_error_permission)
            else -> context.getString(R.string.calendar_error_other, e.message ?: e.javaClass.simpleName)
        }
        save(_settings.value.copy(lastError = text))
    }

    private fun load() = CalendarSyncSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        calendarId = prefs.getLong(KEY_CALENDAR, -1).takeIf { it >= 0 },
        calendarName = prefs.getString(KEY_NAME, "").orEmpty(),
        includeDone = prefs.getBoolean(KEY_DONE, true),
        lastError = prefs.getString(KEY_ERROR, null),
        lastSyncedAtMillis = prefs.getLong(KEY_SYNCED, -1).takeIf { it > 0 },
    )

    private fun save(s: CalendarSyncSettings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, s.enabled)
            .putLong(KEY_CALENDAR, s.calendarId ?: -1)
            .putString(KEY_NAME, s.calendarName)
            .putBoolean(KEY_DONE, s.includeDone)
            .putString(KEY_ERROR, s.lastError)
            .putLong(KEY_SYNCED, s.lastSyncedAtMillis ?: -1)
            .apply()
        _settings.value = s
    }

    private data class Target(val enabled: Boolean, val calendarId: Long?, val includeDone: Boolean)

    private data class Snapshot(
        val plans: List<PlannedWorkout>,
        val sessions: List<SessionHeader>,
        val activities: List<Activity>,
        val workoutNames: Map<Long, String>,
        val target: Target,
    )

    companion object {
        const val PREFS = "calendar_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_CALENDAR = "calendar_id"
        private const val KEY_NAME = "calendar_name"
        private const val KEY_DONE = "include_done"
        private const val KEY_ERROR = "last_error"
        private const val KEY_SYNCED = "last_synced"
        private const val DEBOUNCE_MS = 1_500L
        private const val DONE_WINDOW_DAYS = 60L
        private const val DEFAULT_MINUTES = 60
        private const val MIN_MINUTES = 15

        fun typeLabel(type: ActivityType?) = when (type) {
            ActivityType.RUN -> R.string.activity_type_run
            ActivityType.WALK -> R.string.activity_type_walk
            ActivityType.HIKE -> R.string.activity_type_hike
            ActivityType.RIDE -> R.string.activity_type_ride
            ActivityType.SWIM -> R.string.activity_type_swim
            ActivityType.OTHER, null -> R.string.activity_type_other
        }

        fun segmentLabel(kind: SegmentKind) = when (kind) {
            SegmentKind.WARMUP -> R.string.segment_warmup
            SegmentKind.WORK -> R.string.segment_work
            SegmentKind.RECOVERY -> R.string.segment_recovery
            SegmentKind.COOLDOWN -> R.string.segment_cooldown
            SegmentKind.STEADY -> R.string.segment_steady
        }

        /** "4:20–4:30", "4:30" or null. */
        fun paceRange(step: PlannedStep): String? {
            val fast = step.paceFastSecondsPerKm?.let(ActivityFormat::formatPace)
            val slow = step.paceSlowSecondsPerKm?.let(ActivityFormat::formatPace)
            return when {
                fast != null && slow != null && fast != slow -> "$fast–$slow"
                else -> fast ?: slow
            }
        }
    }
}
