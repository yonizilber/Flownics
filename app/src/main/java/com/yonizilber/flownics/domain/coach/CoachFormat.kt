package com.yonizilber.flownics.domain.coach

import com.yonizilber.flownics.domain.ai.FitnessPrompts
import com.yonizilber.flownics.domain.history.ExerciseProgress
import com.yonizilber.flownics.domain.history.MuscleLoad
import com.yonizilber.flownics.domain.history.ProgressMetric
import com.yonizilber.flownics.domain.fit.HeartZones
import com.yonizilber.flownics.domain.model.Activity
import com.yonizilber.flownics.domain.model.ActivityFormat
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.PlanKind
import com.yonizilber.flownics.domain.model.PlanStatus
import com.yonizilber.flownics.domain.model.PlannedWorkout
import com.yonizilber.flownics.domain.plan.PlanMatching
import com.yonizilber.flownics.domain.model.SessionHeader
import com.yonizilber.flownics.domain.model.SetLog
import com.yonizilber.flownics.domain.model.SetTarget
import com.yonizilber.flownics.domain.model.WorkoutTemplate
import com.yonizilber.flownics.domain.workout.Side
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * How the coach sees the user's data: compact text, computed here rather than by the model.
 * Sets are listed one by one (runs of identical sets folded), never averaged, so a pyramid, a
 * drop set or a failed last set stays visible. Pure, so it is unit-tested.
 */
object CoachFormat {

    private val time = DateTimeFormatter.ofPattern("HH:mm")

    fun day(millis: Long, zone: ZoneId): String {
        val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        return "$date ${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    }

    /** The short status that starts every coach turn: today, the profile, memory, the last two weeks. */
    fun status(
        nowMillis: Long,
        zone: ZoneId,
        profile: CoachProfile,
        notes: List<CoachNote>,
        sessions: List<SessionHeader>,
        logs: List<SetLog>,
        library: Map<Long, Exercise>,
        workouts: List<WorkoutTemplate>,
        estimatedMinutes: (WorkoutTemplate) -> Int,
        activities: List<Activity> = emptyList(),
        plans: List<PlannedWorkout> = emptyList(),
        /** Training load in words, when there is heart rate to work it out from. */
        load: String? = null,
    ): String = buildString {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        appendLine("Now: ${now.toLocalDate()} (${now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}) ${now.format(time)}, time zone ${zone.id}.")
        if (profile.isBlank) {
            appendLine("Profile: the user hasn't filled it in (they can, from the coach's memory screen).")
        } else {
            appendLine("Profile, in the user's words:")
            if (profile.goals.isNotBlank()) appendLine("- Goals: ${profile.goals}")
            if (profile.limitations.isNotBlank()) appendLine("- Limitations or injuries: ${profile.limitations}")
            if (profile.equipment.isNotBlank()) appendLine("- Equipment: ${profile.equipment}")
            if (profile.schedule.isNotBlank()) appendLine("- Schedule: ${profile.schedule}")
        }
        if (notes.isEmpty()) {
            appendLine("Your memory notes: none yet.")
        } else {
            appendLine("Your memory notes (id: text):")
            notes.forEach { appendLine("- ${it.id}: ${it.text}") }
        }
        val since = nowMillis - 14 * DAY_MILLIS
        val recent = sessions.filter { it.startedAtMillis >= since }.sortedBy { it.startedAtMillis }
        appendLine("Workouts done in the last 14 days: ${recent.size}${if (recent.isEmpty()) "." else ":"}")
        recent.forEach { s ->
            val done = logs.count { it.sessionId == s.id && !it.skipped }
            appendLine("- ${day(s.startedAtMillis, zone)}: ${s.workoutName} (session ${s.id}), ${s.durationSeconds / 60} min, $done sets")
        }
        sessions.maxByOrNull { it.startedAtMillis }?.takeIf { it.startedAtMillis < since }?.let {
            appendLine("Last workout before that: ${day(it.startedAtMillis, zone)} ${it.workoutName}.")
        }
        val recentActivities = activities.filter { it.startedAtMillis >= since }.sortedBy { it.startedAtMillis }
        appendLine("Activities logged in the last 14 days (runs, rides…): ${recentActivities.size}${if (recentActivities.isEmpty()) "." else ":"}")
        recentActivities.forEach { appendLine("- " + activityHeadline(it, zone)) }
        val today = now.toLocalDate()
        val names = workouts.associate { it.id to it.name }
        val matched = PlanMatching.match(plans, sessions, activities, zone)
        val past = plans.filter { it.date >= today.minusDays(14) && it.date < today }
        if (past.isNotEmpty()) {
            val done = past.count { it.id in matched }
            val skipped = past.count { it.id !in matched && it.status == PlanStatus.SKIPPED }
            appendLine("Plan in the last 14 days: ${past.size} planned, $done done, $skipped skipped, ${past.size - done - skipped} not done.")
        }
        val ahead = plans.filter { it.date >= today && it.date <= today.plusDays(14) }
        appendLine("Planned for the next 14 days (today included): ${ahead.size}${if (ahead.isEmpty()) "." else ":"}")
        ahead.forEach { appendLine("- " + planLine(it, matched[it.id], names)) }
        load?.let { appendLine(it) }
        val weekIds = sessions.filter { it.startedAtMillis >= nowMillis - 7 * DAY_MILLIS }.map { it.id }.toSet()
        appendLine("Sets per muscle, last 7 days: " + loadLine(MuscleLoad.of(logs.filter { it.sessionId in weekIds }, library)))
        if (workouts.isEmpty()) {
            appendLine("Saved workouts: none.")
        } else {
            appendLine("Saved workouts (id: name, about how long):")
            workouts.take(MAX_WORKOUTS).forEach { appendLine("- ${it.id}: ${it.name}, ~${estimatedMinutes(it)} min") }
            if (workouts.size > MAX_WORKOUTS) appendLine("- …and ${workouts.size - MAX_WORKOUTS} more (ask workout_details by id).")
        }
        append("Exercise library: ${library.size} exercises (look them up with exercises_search).")
    }

    /**
     * Finished workouts between two dates (inclusive), newest first, each exercise with every set.
     * With [exerciseId], only that exercise, plus its records and the trend of its best set.
     */
    fun trainingLog(
        from: LocalDate,
        to: LocalDate,
        exerciseId: Long?,
        sessions: List<SessionHeader>,
        logs: List<SetLog>,
        library: Map<Long, Exercise>,
        zone: ZoneId,
        limit: Int = MAX_SESSIONS,
        activities: List<Activity> = emptyList(),
        /** Watch recordings made during workouts, by session id. */
        watch: Map<Long, Activity> = emptyMap(),
        zones: HeartZones? = null,
    ): String = buildString {
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val bySession = logs.filter { exerciseId == null || it.exerciseId == exerciseId }.groupBy { it.sessionId }
        val inRange = sessions
            .filter { it.startedAtMillis in start until end }
            .filter { exerciseId == null || it.id in bySession }
            .sortedByDescending { it.startedAtMillis }
        val about = exerciseId?.let { id -> " with ${library[id]?.name ?: "exercise $id"}" }.orEmpty()
        // Activities (runs, rides…) have no exercises, so an exercise filter leaves them out.
        val activitiesInRange = if (exerciseId != null) emptyList() else activities.filter { it.startedAtMillis in start until end }
        appendLine("Workouts$about from $from to $to: ${inRange.size}." + if (exerciseId == null) " Activities: ${activitiesInRange.size}." else "")
        if (exerciseId != null) {
            val all = logs.filter { it.exerciseId == exerciseId }
            val history = ExerciseProgress.bySession(all)
            ExerciseProgress.records(history).forEach { r ->
                appendLine("Record ${metricName(r.metric)}: ${number(r.value)} on ${day(r.atMillis, zone)}")
            }
        }
        val shownSessions = inRange.take(limit)
        val shownActivities = activitiesInRange.sortedByDescending { it.startedAtMillis }.take(limit)
        shownActivities.forEach { a ->
            appendLine()
            // Kilometre splits only when few activities are shown: a long range stays readable.
            append(activityDetail(a, zone, withSplits = shownActivities.size <= SPLITS_UP_TO, zones = zones))
            appendLine()
        }
        shownSessions.forEach { s ->
            appendLine()
            appendLine("${day(s.startedAtMillis, zone)} ${Instant.ofEpochMilli(s.startedAtMillis).atZone(zone).format(time)}: ${s.workoutName} (session ${s.id}), ${s.durationSeconds / 60} min")
            val sets = bySession[s.id].orEmpty().sortedBy { it.stepIndex }
            val recording = watch[s.id]
            val heart = recording?.metrics?.sets.orEmpty().associateBy { it.stepIndex }
            recording?.let { r ->
                appendLine("  Watch: " + listOfNotNull(r.avgHeartRate?.let { "avg HR $it" }, r.maxHeartRate?.let { "max HR $it" }).joinToString(", ").ifEmpty { "recorded, no heart rate" })
                zonesLine(r, zones)?.let { appendLine("  $it") }
            }
            sets.groupBy { it.exerciseId }.forEach { (id, exerciseSets) ->
                val name = library[id]?.name ?: exerciseSets.first().exerciseName
                appendLine("- $name (exercise $id): ${setsLine(exerciseSets)}")
                val hr = exerciseSets.mapNotNull { log -> heart[log.stepIndex] }
                if (hr.isNotEmpty()) {
                    appendLine("  HR per set (avg/peak, drop in the minute after): " + hr.joinToString("; ") { h -> "${h.avg}/${h.max}" + (h.drop?.let { d -> " −$d" } ?: "") })
                }
            }
            if (s.notes.isNotBlank()) appendLine("  Notes: ${s.notes.replace('\n', ' ')}")
        }
        val cut = listOfNotNull(
            "${inRange.size} workouts".takeIf { inRange.size > limit },
            "${activitiesInRange.size} activities".takeIf { activitiesInRange.size > limit },
        )
        if (cut.isNotEmpty()) {
            appendLine()
            append("Showing the newest $limit of ${cut.joinToString(" and ")}. Ask for a shorter date range to see the rest.")
        }
    }.trimEnd()

    /** Plans between two dates with their state and every part with its targets. */
    fun planList(from: LocalDate, to: LocalDate, plans: List<PlannedWorkout>, matched: Map<Long, com.yonizilber.flownics.domain.plan.DoneItem>, workoutNames: Map<Long, String>): String = buildString {
        val inRange = plans.filter { it.date in from..to }.sortedWith(compareBy({ it.date }, { it.time }))
        appendLine("Planned from $from to $to: ${inRange.size}.")
        inRange.forEach { appendLine("- " + planLine(it, matched[it.id], workoutNames)) }
    }.trimEnd()

    /** "2026-11-11 Wed 18:00: Run "Intervals" (plan 5, ~50 min), planned; parts: …; aim: …". */
    fun planLine(p: PlannedWorkout, done: com.yonizilber.flownics.domain.plan.DoneItem?, workoutNames: Map<Long, String>): String = buildString {
        append("${p.date} ${p.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}")
        p.time?.let { append(" $it") }
        append(": ")
        when (p.kind) {
            PlanKind.WORKOUT -> append("workout ${p.workoutId} \"${p.workoutId?.let(workoutNames::get) ?: "deleted"}\"")
            PlanKind.ACTIVITY -> append(p.activityType?.name ?: "activity")
        }
        if (p.title.isNotBlank()) append(" \"${p.title}\"")
        append(" (plan ${p.id}${p.durationMinutes?.let { ", ~$it min" }.orEmpty()}), ")
        append(
            when {
                done is com.yonizilber.flownics.domain.plan.DoneItem.Logged -> "done (activity ${done.activity.id})"
                done is com.yonizilber.flownics.domain.plan.DoneItem.Workout -> "done (session ${done.session.id})"
                p.status == PlanStatus.SKIPPED -> "skipped"
                else -> "planned"
            },
        )
        if (p.steps.isNotEmpty()) {
            append("; parts: ")
            append(
                p.steps.joinToString("; ") { s ->
                    listOfNotNull(
                        s.kind.name.lowercase(),
                        s.distanceMeters?.let { "${ActivityFormat.formatKm(it)} km" },
                        s.durationSeconds?.let { ActivityFormat.formatDuration(it) },
                        listOfNotNull(s.paceFastSecondsPerKm, s.paceSlowSecondsPerKm).distinct().takeIf { it.isNotEmpty() }
                            ?.joinToString("–") { ActivityFormat.formatPace(it) }?.let { "@$it/km" },
                        s.maxHeartRate?.let { "HR≤$it" },
                    ).joinToString(" ")
                },
            )
        }
        if (p.notes.isNotBlank()) append("; aim: ${p.notes.replace('\n', ' ')}")
    }

    /** "Time in zones: Z1 5 min, Z2 30 min…; load 85" for a recording with heart rate. */
    private fun zonesLine(a: Activity, zones: HeartZones?): String? {
        val h = a.metrics?.hrHistogram.orEmpty()
        if (zones == null || h.sum() == 0) return null
        val minutes = zones.secondsInZones(h).map { (it + 30) / 60 }
        return "Time in zones: " + minutes.mapIndexed { i, m -> "Z${i + 1} $m min" }.joinToString(", ") + "; load ${zones.trimp(h)}"
    }

    /** One line: when, what, how far, how long, how fast, how hard. */
    fun activityHeadline(a: Activity, zone: ZoneId): String = buildString {
        append("${day(a.startedAtMillis, zone)} ${Instant.ofEpochMilli(a.startedAtMillis).atZone(zone).format(time)}: ")
        append(a.title.ifBlank { a.type.name.lowercase().replaceFirstChar { it.uppercase() } })
        append(" (${a.type.name}, activity ${a.id}), ")
        a.distanceMeters?.let { append("${ActivityFormat.formatKm(it)} km in ") }
        append(ActivityFormat.formatDuration(a.durationSeconds))
        a.paceSecondsPerKm?.let { append(" (${ActivityFormat.formatPace(it)}/km)") }
        a.avgHeartRate?.let { append(", avg HR $it") }
        a.maxHeartRate?.let { append(", max HR $it") }
        a.elevationGainMeters?.let { append(", climb $it m") }
        a.rpe?.let { append(", felt $it/10") }
    }

    /** The headline, then every part in order (so intervals keep their shape), then the notes. */
    fun activityDetail(a: Activity, zone: ZoneId, withSplits: Boolean = true, zones: HeartZones? = null): String = buildString {
        append(activityHeadline(a, zone))
        a.segments.forEachIndexed { i, s ->
            appendLine()
            append("  ${i + 1}. ${s.kind.name.lowercase()}: ")
            append(
                listOfNotNull(
                    s.distanceMeters?.let { "${ActivityFormat.formatKm(it)} km" },
                    s.durationSeconds?.let { ActivityFormat.formatDuration(it) },
                    s.paceSecondsPerKm?.let { "${ActivityFormat.formatPace(it)}/km" },
                    s.avgHeartRate?.let { "HR $it" },
                    s.cadenceSpm?.let { "$it spm" },
                    s.stepLengthCm?.let { "step $it cm" },
                    s.note.takeIf { it.isNotBlank() },
                ).joinToString(", "),
            )
        }
        a.metrics?.let { m ->
            val form = listOfNotNull(
                m.cadence?.let { if (m.cadenceIsSteps) "cadence $it spm" else "cadence $it rpm" },
                m.stepLengthCm?.let { "step length $it cm" + if (m.stepLengthMeasured) "" else " (worked out from speed and cadence)" },
                m.verticalOscillationMm?.let { "vertical oscillation ${it / 10.0} cm" },
                m.groundContactMs?.let { "ground contact $it ms" },
                m.avgPowerW?.let { "power $it W" },
                m.decouplingPercent?.let { "HR drift vs pace $it% (first half to second; meaningful only for a steady effort)" },
            )
            if (form.isNotEmpty()) {
                appendLine()
                append("  Recorded: " + form.joinToString(", "))
            }
            zonesLine(a, zones)?.let {
                appendLine()
                append("  $it")
            }
            if (m.cadenceIsSteps && m.splits.isNotEmpty() && withSplits) {
                appendLine()
                append("  Km splits: " + m.splits.joinToString("; ") { s ->
                    listOfNotNull("${s.km}: ${ActivityFormat.formatDuration(s.seconds)}", s.avgHeartRate?.let { "HR $it" }, s.cadence?.let { "$it spm" }).joinToString(" ")
                })
            }
        }
        if (a.notes.isNotBlank()) {
            appendLine()
            append("  Notes: ${a.notes.replace('\n', ' ')}")
        }
    }

    /** Every set in order; identical neighbours folded ("8×60 kg ×3"), skipped ones counted. */
    fun setsLine(sets: List<SetLog>): String {
        val done = sets.filterNot { it.skipped }.map { describe(it.actual) + sideOf(it.side) }
        val folded = mutableListOf<Pair<String, Int>>()
        done.forEach { s -> if (folded.lastOrNull()?.first == s) folded[folded.lastIndex] = s to folded.last().second + 1 else folded += s to 1 }
        val text = folded.joinToString(", ") { (s, n) -> if (n > 1) "$s ×$n" else s }.ifEmpty { "no sets done" }
        val skipped = sets.count { it.skipped }
        return if (skipped > 0) "$text ($skipped skipped)" else text
    }

    fun describe(t: SetTarget): String {
        val parts = buildList {
            when {
                t.reps != null && t.weightKg != null -> add("${t.reps}×${number(t.weightKg)} kg")
                t.reps != null -> add("${t.reps} reps")
                t.weightKg != null -> add("${number(t.weightKg)} kg")
            }
            t.seconds?.let { add("$it s") }
            t.distanceMeters?.let { add("$it m") }
        }
        return parts.joinToString(" ").ifEmpty { "done" }
    }

    fun muscleLoad(from: LocalDate, to: LocalDate, sessions: List<SessionHeader>, logs: List<SetLog>, library: Map<Long, Exercise>, zone: ZoneId): String {
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val ids = sessions.filter { it.startedAtMillis in start until end }.map { it.id }.toSet()
        val load = MuscleLoad.of(logs.filter { it.sessionId in ids }, library)
        return buildString {
            appendLine("Sets per muscle from $from to $to (${ids.size} workouts; a secondary muscle counts as half a set):")
            appendLine(loadLine(load))
            if (load.untouched.isNotEmpty()) appendLine("Not worked: ${load.untouched.joinToString { it.name }}")
            if (load.uncountedSets > 0) append("${load.uncountedSets} sets were of exercises without muscle tags, so they aren't counted.")
        }.trimEnd()
    }

    fun exerciseLine(e: Exercise): String = FitnessPrompts.describeLibrary(listOf(e)).replaceFirst("#${e.id} ", "${e.id}: ")

    private fun loadLine(load: MuscleLoad): String =
        load.ranked.joinToString { (m, v) -> "${m.name} ${number(v)}" }.ifEmpty { "none" }

    private fun sideOf(side: Side?) = when (side) {
        Side.LEFT -> " (L)"
        Side.RIGHT -> " (R)"
        null -> ""
    }

    private fun metricName(metric: ProgressMetric) = when (metric) {
        ProgressMetric.WEIGHT -> "heaviest weight (kg)"
        ProgressMetric.VOLUME -> "volume in one workout (kg)"
        ProgressMetric.REPS -> "most reps in a set"
        ProgressMetric.SECONDS -> "longest set (s)"
        ProgressMetric.DISTANCE -> "longest distance (m)"
    }

    private fun number(v: Double) = String.format(Locale.ROOT, "%.1f", v).removeSuffix(".0")

    private const val DAY_MILLIS = 86_400_000L
    const val MAX_SESSIONS = 30
    private const val SPLITS_UP_TO = 6
    private const val MAX_WORKOUTS = 40
}
