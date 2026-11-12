package com.yonizilber.flownics.domain.coach

import com.yonizilber.flownics.domain.ai.AiSchema
import com.yonizilber.flownics.domain.model.Activity
import com.yonizilber.flownics.domain.model.ActivityFormat
import com.yonizilber.flownics.domain.model.ActivitySegment
import com.yonizilber.flownics.domain.model.ActivityType
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.ExerciseLevel
import com.yonizilber.flownics.domain.model.MeasureMode
import com.yonizilber.flownics.domain.model.Muscle
import com.yonizilber.flownics.domain.model.PlanKind
import com.yonizilber.flownics.domain.model.PlannedStep
import com.yonizilber.flownics.domain.model.PlannedWorkout
import com.yonizilber.flownics.domain.model.SegmentKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The parts of an exercise the coach may propose to change. */
enum class ExerciseField { NAME, CATEGORIES, MUSCLES, EQUIPMENT, INSTRUCTIONS, CUES, NOTES, LEVEL, MEASURE_MODE, UNILATERAL, STYLE_OF }

/** A proposed new exercise, or a change to one: [proposed] is the whole exercise as it would be. */
data class ExerciseChange(val current: Exercise?, val proposed: Exercise, val fields: List<ExerciseField>, val reason: String) {
    val isNew: Boolean get() = current == null

    /** [current] with only [chosen] taken from [proposed]: the user may keep part of a proposal. */
    fun applied(chosen: Set<ExerciseField>): Exercise {
        val base = current ?: return proposed
        var e = base
        chosen.forEach { f ->
            e = when (f) {
                ExerciseField.NAME -> e.copy(name = proposed.name)
                ExerciseField.CATEGORIES -> e.copy(category = proposed.category, extraCategories = proposed.extraCategories)
                ExerciseField.MUSCLES -> e.copy(primaryMuscles = proposed.primaryMuscles, secondaryMuscles = proposed.secondaryMuscles)
                ExerciseField.EQUIPMENT -> e.copy(equipment = proposed.equipment)
                ExerciseField.INSTRUCTIONS -> e.copy(instructions = proposed.instructions)
                ExerciseField.CUES -> e.copy(cues = proposed.cues)
                ExerciseField.NOTES -> e.copy(notes = proposed.notes)
                ExerciseField.LEVEL -> e.copy(level = proposed.level)
                ExerciseField.MEASURE_MODE -> e.copy(measureMode = proposed.measureMode)
                ExerciseField.UNILATERAL -> e.copy(unilateral = proposed.unilateral)
                ExerciseField.STYLE_OF -> e.copy(variationOf = proposed.variationOf)
            }
        }
        return e
    }
}

/**
 * A proposed change to the plan: days to add, plans to move, plans to drop. Each row has a key
 * ("add:0", "move:5", "remove:7") so the user can accept some rows and not others.
 */
data class PlanChange(
    val adds: List<PlannedWorkout>,
    val moves: List<Pair<PlannedWorkout, PlannedWorkout>>,
    val removes: List<PlannedWorkout>,
    val reason: String,
) {
    val keys: List<String> get() = adds.indices.map { "add:$it" } + moves.map { "move:${it.first.id}" } + removes.map { "remove:${it.id}" }
}

/** A proposal's arguments couldn't be used; the message tells the model how to fix them. */
class ProposalException(message: String) : Exception(message)

/**
 * The shapes of the coach's change proposals, and how their arguments become app types. Every
 * value is checked against the app's own lists; nothing unknown is guessed into something else.
 * Pure, so it is unit-tested.
 */
object CoachProposals {

    private val categoryNames = ExerciseCategory.entries.map { it.name }
    private val muscleNames = Muscle.entries.map { it.name }

    val exerciseChangeSchema = AiSchema.Obj(
        mapOf(
            "exercise_id" to AiSchema.Integer(description = "The exercise to change, or 0 to propose a new exercise for the library."),
            "change" to AiSchema.Arr(
                AiSchema.Str(enum = ExerciseField.entries.map { it.name }),
                "Which fields this proposal sets. Only these are read; for a new exercise, everything you fill in. Others may be left empty.",
            ),
            "name" to AiSchema.Str(),
            "categories" to AiSchema.Arr(AiSchema.Str(enum = categoryNames), "Every kind it is, the main one first."),
            "primary_muscles" to AiSchema.Arr(AiSchema.Str(enum = muscleNames)),
            "secondary_muscles" to AiSchema.Arr(AiSchema.Str(enum = muscleNames)),
            "equipment" to AiSchema.Str(),
            "instructions" to AiSchema.Arr(AiSchema.Str(), "The full list of steps as it should be (existing ones you keep included), in order."),
            "cues" to AiSchema.Arr(AiSchema.Str(), "The full list of cues as it should be: existing ones you keep plus new ones."),
            "notes" to AiSchema.Str(description = "The full notes as they should be."),
            "level" to AiSchema.Integer(description = "1-5 on the level scale; 0 for none."),
            "measure_mode" to AiSchema.Str(enum = MeasureMode.entries.map { it.name }),
            "unilateral" to AiSchema.Bool(),
            "style_of_exercise_id" to AiSchema.Integer(description = "The exercise whose family this is a style of, or 0 for its own exercise."),
            "reason" to AiSchema.Str(description = "One or two sentences for the user: why this change."),
        ),
    )

    fun exerciseChange(args: JsonObject, library: Map<Long, Exercise>): ExerciseChange {
        val id = args.long("exercise_id") ?: 0
        val current = if (id > 0) library[id] ?: fail("There is no exercise $id. Use exercises_search to find ids, or 0 for a new exercise.") else null
        val fields = args.strings("change").map { n -> ExerciseField.entries.firstOrNull { it.name == n } ?: fail("Unknown field in change: $n") }.distinct()
        val wanted = if (current == null) ExerciseField.entries.toSet() else fields.toSet()
        if (current != null && fields.isEmpty()) fail("change is empty: list the fields this proposal sets.")
        val start = current ?: Exercise(name = "", category = ExerciseCategory.STRENGTH)
        var e = start
        fun has(f: ExerciseField) = f in wanted
        if (has(ExerciseField.NAME)) args.string("name")?.trim()?.takeIf { it.isNotEmpty() }?.let { e = e.copy(name = it.take(NAME_MAX)) }
        if (has(ExerciseField.CATEGORIES)) {
            val cats = args.strings("categories").map { n -> ExerciseCategory.entries.firstOrNull { it.name == n } ?: fail("Unknown category: $n") }.distinct()
            if (cats.isNotEmpty()) e = e.copy(category = cats.first(), extraCategories = cats.drop(1).toSet())
            else if (current != null) fail("categories is empty: give at least one.")
        }
        if (has(ExerciseField.MUSCLES)) {
            val primary = args.strings("primary_muscles").map { muscle(it) }.toSet()
            val secondary = args.strings("secondary_muscles").map { muscle(it) }.toSet() - primary
            e = e.copy(primaryMuscles = primary, secondaryMuscles = secondary)
        }
        if (has(ExerciseField.EQUIPMENT)) e = e.copy(equipment = args.string("equipment")?.trim().orEmpty())
        if (has(ExerciseField.INSTRUCTIONS)) e = e.copy(instructions = args.strings("instructions").map { it.trim() }.filter { it.isNotEmpty() })
        if (has(ExerciseField.CUES)) e = e.copy(cues = args.strings("cues").map { it.trim() }.filter { it.isNotEmpty() })
        if (has(ExerciseField.NOTES)) e = e.copy(notes = args.string("notes")?.trim().orEmpty())
        if (has(ExerciseField.LEVEL)) e = e.copy(level = args.long("level")?.toInt()?.takeIf { it in ExerciseLevel.RANGE })
        if (has(ExerciseField.MEASURE_MODE)) {
            args.string("measure_mode")?.takeIf { it.isNotBlank() }?.let { n ->
                e = e.copy(measureMode = MeasureMode.entries.firstOrNull { it.name == n } ?: fail("Unknown measure_mode: $n"))
            }
        }
        if (has(ExerciseField.UNILATERAL)) (args["unilateral"] as? JsonPrimitive)?.booleanOrNull?.let { e = e.copy(unilateral = it) }
        if (has(ExerciseField.STYLE_OF)) {
            val baseId = args.long("style_of_exercise_id")?.takeIf { it > 0 }
            if (baseId != null && baseId !in library) fail("There is no exercise $baseId to be a style of.")
            if (baseId != null && baseId == id) fail("An exercise can't be a style of itself.")
            e = e.copy(variationOf = baseId)
        }
        if (e.name.isBlank()) fail("A new exercise needs a name.")
        val changed = if (current == null) emptyList() else fields.filter { f -> differs(f, current, e) }
        if (current != null && changed.isEmpty()) fail("Nothing would change: the values given are the same as now.")
        return ExerciseChange(current, e, changed, args.string("reason")?.trim().orEmpty())
    }

    private fun differs(f: ExerciseField, a: Exercise, b: Exercise) = when (f) {
        ExerciseField.NAME -> a.name != b.name
        ExerciseField.CATEGORIES -> a.categories != b.categories
        ExerciseField.MUSCLES -> a.primaryMuscles != b.primaryMuscles || a.secondaryMuscles != b.secondaryMuscles
        ExerciseField.EQUIPMENT -> a.equipment != b.equipment
        ExerciseField.INSTRUCTIONS -> a.instructions != b.instructions
        ExerciseField.CUES -> a.cues != b.cues
        ExerciseField.NOTES -> a.notes != b.notes
        ExerciseField.LEVEL -> a.level != b.level
        ExerciseField.MEASURE_MODE -> a.measureMode != b.measureMode
        ExerciseField.UNILATERAL -> a.unilateral != b.unilateral
        ExerciseField.STYLE_OF -> a.variationOf != b.variationOf
    }

    val activitySchema = AiSchema.Obj(
        mapOf(
            "type" to AiSchema.Str(enum = ActivityType.entries.map { it.name }),
            "title" to AiSchema.Str(description = "Short, or empty to use the type."),
            "date" to AiSchema.Str(description = "YYYY-MM-DD"),
            "start_time" to AiSchema.Str(description = "HH:MM, 24-hour; your best estimate if the user didn't say."),
            "duration_seconds" to AiSchema.Integer(description = "Total time; 0 to add up the segments."),
            "distance_km" to AiSchema.Num(description = "0 when unknown."),
            "avg_heart_rate" to AiSchema.Integer(description = "0 when unknown."),
            "max_heart_rate" to AiSchema.Integer(description = "0 when unknown."),
            "elevation_gain_m" to AiSchema.Integer(description = "0 when unknown."),
            "rpe" to AiSchema.Integer(description = "How hard it felt, 1-10; 0 when unknown."),
            "notes" to AiSchema.Str(),
            "segments" to AiSchema.Arr(
                AiSchema.Obj(
                    mapOf(
                        "kind" to AiSchema.Str(enum = SegmentKind.entries.map { it.name }),
                        "duration_seconds" to AiSchema.Integer(description = "0 when unknown."),
                        "distance_km" to AiSchema.Num(description = "0 when unknown."),
                        "avg_heart_rate" to AiSchema.Integer(description = "0 when unknown."),
                        "note" to AiSchema.Str(),
                    ),
                ),
                "The parts in order (warm-up, each repeat and recovery, cool-down), only as the user described them. Empty if they didn't.",
            ),
        ),
    )

    fun activity(args: JsonObject, zone: ZoneId): Activity {
        val type = args.string("type")?.let { n -> ActivityType.entries.firstOrNull { it.name == n } } ?: fail("type must be one of ${ActivityType.entries.joinToString()}")
        val date = runCatching { LocalDate.parse(args.string("date").orEmpty().trim()) }.getOrNull() ?: fail("date must be YYYY-MM-DD")
        val time = runCatching { LocalTime.parse(args.string("start_time").orEmpty().trim()) }.getOrNull() ?: fail("start_time must be HH:MM")
        val segments = (args["segments"] as? JsonArray).orEmpty().map { el ->
            val s = el.jsonObject
            val kind = s.string("kind")?.let { n -> SegmentKind.entries.firstOrNull { it.name == n } } ?: fail("Unknown segment kind: ${s.string("kind")}")
            ActivitySegment(
                kind = kind,
                durationSeconds = s.long("duration_seconds")?.toInt()?.takeIf { it > 0 },
                distanceMeters = s.km("distance_km"),
                avgHeartRate = s.long("avg_heart_rate")?.toInt()?.takeIf { it in HR },
                note = s.string("note")?.trim().orEmpty(),
            )
        }.filter { it.durationSeconds != null || it.distanceMeters != null }
        val duration = args.long("duration_seconds")?.toInt()?.takeIf { it > 0 }
            ?: segments.takeIf { it.isNotEmpty() && it.all { s -> s.durationSeconds != null } }?.sumOf { it.durationSeconds ?: 0 }
            ?: fail("duration_seconds is needed (or a time for every segment).")
        return Activity(
            type = type,
            title = args.string("title")?.trim().orEmpty(),
            startedAtMillis = date.atTime(time).atZone(zone).toInstant().toEpochMilli(),
            durationSeconds = duration,
            distanceMeters = args.km("distance_km") ?: segments.mapNotNull { it.distanceMeters }.sum().takeIf { it > 0 },
            avgHeartRate = args.long("avg_heart_rate")?.toInt()?.takeIf { it in HR },
            maxHeartRate = args.long("max_heart_rate")?.toInt()?.takeIf { it in HR },
            elevationGainMeters = args.long("elevation_gain_m")?.toInt()?.takeIf { it > 0 },
            rpe = args.long("rpe")?.toInt()?.takeIf { it in Activity.RPE_RANGE },
            notes = args.string("notes")?.trim().orEmpty(),
            segments = segments,
        )
    }

    private val stepSchema = AiSchema.Obj(
        mapOf(
            "kind" to AiSchema.Str(enum = SegmentKind.entries.map { it.name }),
            "duration_seconds" to AiSchema.Integer(description = "0 when given by distance."),
            "distance_km" to AiSchema.Num(description = "0 when given by time."),
            "pace_fast" to AiSchema.Str(description = "Faster end of the pace range per km as m:ss, or empty."),
            "pace_slow" to AiSchema.Str(description = "Slower end of the pace range per km as m:ss, or empty."),
            "max_heart_rate" to AiSchema.Integer(description = "Heart-rate ceiling, or 0."),
            "note" to AiSchema.Str(),
        ),
    )

    val planChangeSchema = AiSchema.Obj(
        mapOf(
            "add" to AiSchema.Arr(
                AiSchema.Obj(
                    mapOf(
                        "date" to AiSchema.Str(description = "YYYY-MM-DD"),
                        "start_time" to AiSchema.Str(description = "HH:MM, or empty for any time that day."),
                        "kind" to AiSchema.Str(enum = PlanKind.entries.map { it.name }, description = "WORKOUT: a saved workout by id. ACTIVITY: a run, ride… with parts."),
                        "workout_id" to AiSchema.Integer(description = "For WORKOUT: a saved workout's id; 0 otherwise."),
                        "activity_type" to AiSchema.Str(enum = ActivityType.entries.map { it.name }, description = "For ACTIVITY."),
                        "title" to AiSchema.Str(description = "Short, e.g. 'Intervals 6×1 km'; may be empty."),
                        "duration_minutes" to AiSchema.Integer(description = "0 to work it out."),
                        "notes" to AiSchema.Str(description = "What to aim for and why, in a sentence or two."),
                        "steps" to AiSchema.Arr(stepSchema, "For ACTIVITY: the parts in order, repeats written out one by one. Empty for WORKOUT."),
                    ),
                ),
                "Days to add to the plan. Empty for none.",
            ),
            "move" to AiSchema.Arr(
                AiSchema.Obj(
                    mapOf(
                        "plan_id" to AiSchema.Integer(),
                        "date" to AiSchema.Str(description = "YYYY-MM-DD"),
                        "start_time" to AiSchema.Str(description = "HH:MM, or empty."),
                    ),
                ),
                "Planned items to move to another day or time. Empty for none.",
            ),
            "remove" to AiSchema.Arr(AiSchema.Integer(), "Plan ids to drop. Empty for none."),
            "reason" to AiSchema.Str(description = "One or two sentences for the user: the idea behind this plan."),
        ),
    )

    fun planChange(args: JsonObject, existing: Map<Long, PlannedWorkout>, workoutIds: Set<Long>): PlanChange {
        val adds = (args["add"] as? JsonArray).orEmpty().mapIndexed { i, el ->
            val a = el.jsonObject
            val kind = a.string("kind")?.let { n -> PlanKind.entries.firstOrNull { it.name == n } } ?: fail("add[$i].kind must be WORKOUT or ACTIVITY")
            val workoutId = a.long("workout_id")?.takeIf { it > 0 }
            if (kind == PlanKind.WORKOUT && (workoutId == null || workoutId !in workoutIds)) fail("add[$i].workout_id must be a saved workout's id (see the status).")
            val steps = (a["steps"] as? JsonArray).orEmpty().mapIndexed { j, st ->
                val o = st.jsonObject
                val sk = o.string("kind")?.let { n -> SegmentKind.entries.firstOrNull { it.name == n } } ?: fail("add[$i].steps[$j].kind is unknown")
                val paces = listOfNotNull(pace(o.string("pace_fast"), "add[$i].steps[$j].pace_fast"), pace(o.string("pace_slow"), "add[$i].steps[$j].pace_slow")).sorted()
                PlannedStep(
                    kind = sk,
                    durationSeconds = o.long("duration_seconds")?.toInt()?.takeIf { it > 0 },
                    distanceMeters = o.km("distance_km"),
                    paceFastSecondsPerKm = paces.firstOrNull(),
                    paceSlowSecondsPerKm = paces.lastOrNull(),
                    maxHeartRate = o.long("max_heart_rate")?.toInt()?.takeIf { it in HR },
                    note = o.string("note")?.trim().orEmpty(),
                ).also { if (it.durationSeconds == null && it.distanceMeters == null) fail("add[$i].steps[$j] needs a time or a distance.") }
            }
            PlannedWorkout(
                date = date(a.string("date"), "add[$i].date"),
                time = time(a.string("start_time"), "add[$i].start_time"),
                kind = kind,
                workoutId = workoutId.takeIf { kind == PlanKind.WORKOUT },
                activityType = if (kind == PlanKind.ACTIVITY) a.string("activity_type")?.let { n -> ActivityType.entries.firstOrNull { it.name == n } } ?: ActivityType.RUN else null,
                title = a.string("title")?.trim().orEmpty(),
                durationMinutes = a.long("duration_minutes")?.toInt()?.takeIf { it in 1..1440 }
                    ?: steps.mapNotNull { it.estimatedSeconds() }.sum().takeIf { it > 0 }?.let { (it + 59) / 60 },
                notes = a.string("notes")?.trim().orEmpty(),
                steps = if (kind == PlanKind.ACTIVITY) steps else emptyList(),
            )
        }
        val moves = (args["move"] as? JsonArray).orEmpty().mapIndexed { i, el ->
            val m = el.jsonObject
            val id = m.long("plan_id") ?: fail("move[$i].plan_id is missing")
            val plan = existing[id] ?: fail("There is no plan $id. Use plan_list to see the plan.")
            plan to plan.copy(date = date(m.string("date"), "move[$i].date"), time = time(m.string("start_time"), "move[$i].start_time"))
        }
        val removes = (args["remove"] as? JsonArray).orEmpty().map { el ->
            val id = (el as? JsonPrimitive)?.longOrNull ?: fail("remove holds plan ids")
            existing[id] ?: fail("There is no plan $id. Use plan_list to see the plan.")
        }
        if (adds.isEmpty() && moves.isEmpty() && removes.isEmpty()) fail("The proposal is empty: add, move or remove something.")
        return PlanChange(adds, moves, removes, args.string("reason")?.trim().orEmpty())
    }

    private fun date(text: String?, what: String): LocalDate = runCatching { LocalDate.parse(text.orEmpty().trim()) }.getOrNull() ?: fail("$what must be YYYY-MM-DD")

    private fun time(text: String?, what: String): LocalTime? =
        text?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalTime.parse(it) }.getOrNull() ?: fail("$what must be HH:MM or empty") }

    private fun pace(text: String?, what: String): Int? =
        text?.trim()?.takeIf { it.isNotEmpty() }?.let { ActivityFormat.parseDuration(it)?.takeIf { s -> s in 120..1200 } ?: fail("$what must be a pace like 4:30") }

    private fun muscle(n: String) = Muscle.entries.firstOrNull { it.name == n } ?: fail("Unknown muscle: $n")

    private fun fail(message: String): Nothing = throw ProposalException(message)

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
    private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    private fun JsonObject.km(key: String): Int? = (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }?.let { ActivityFormat.parseKm(it.toString()) }

    private val HR = 30..250
    private const val NAME_MAX = 120
}
