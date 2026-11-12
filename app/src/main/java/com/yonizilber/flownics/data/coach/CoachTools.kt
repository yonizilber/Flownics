package com.yonizilber.flownics.data.coach

import com.yonizilber.flownics.domain.ai.AgentToolCall
import com.yonizilber.flownics.domain.ai.AiException
import com.yonizilber.flownics.domain.ai.AiSchema
import com.yonizilber.flownics.domain.ai.AiToolSpec
import com.yonizilber.flownics.domain.ai.FitnessAnswers
import com.yonizilber.flownics.domain.ai.FitnessPrompts
import com.yonizilber.flownics.data.repository.ActivityRepository
import com.yonizilber.flownics.data.repository.PlanRepository
import com.yonizilber.flownics.domain.plan.PlanMatching
import com.yonizilber.flownics.domain.coach.CoachFormat
import com.yonizilber.flownics.domain.coach.CoachProposals
import com.yonizilber.flownics.domain.coach.ProposalException
import com.yonizilber.flownics.domain.coach.ProposalKind
import com.yonizilber.flownics.domain.history.MuscleLoad
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.Muscle
import com.yonizilber.flownics.domain.repository.ExerciseRepository
import com.yonizilber.flownics.domain.repository.SessionRepository
import com.yonizilber.flownics.domain.repository.WorkoutRepository
import com.yonizilber.flownics.domain.workout.WorkoutCompiler
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The coach's tools. Reading tools run at once on the user's own data. Changing tools never change
 * anything: [PROPOSE_WORKOUT] becomes a card the user saves or declines. The only thing the coach
 * writes by itself is its own memory, which the user sees and can undo.
 */
object CoachToolNames {
    const val TRAINING_LOG = "training_log"
    const val MUSCLE_LOAD = "muscle_load"
    const val EXERCISES_SEARCH = "exercises_search"
    const val EXERCISE_DETAILS = "exercise_details"
    const val WORKOUT_DETAILS = "workout_details"
    const val PROPOSE_WORKOUT = "propose_workout"
    const val PROPOSE_EXERCISE_CHANGE = "propose_exercise_change"
    const val PROPOSE_ACTIVITY = "propose_activity"
    const val PLAN_LIST = "plan_list"
    const val PROPOSE_PLAN = "propose_plan"
    const val MEMORY_SAVE = "memory_save"
    const val MEMORY_DELETE = "memory_delete"
}

/** What running a tool gave: the text for the model, and what (if anything) to show the user. */
sealed interface ToolOutcome {
    val content: String
    val isError: Boolean

    data class Read(override val content: String, override val isError: Boolean = false) : ToolOutcome
    data class Proposed(override val content: String, val kind: ProposalKind, val replacesWorkoutId: Long? = null) : ToolOutcome {
        override val isError = false
    }
    data class Remembered(override val content: String, val noteId: Long, val text: String, val removed: Boolean) : ToolOutcome {
        override val isError = false
    }
}

@Singleton
class CoachTools @Inject constructor(
    private val exercises: ExerciseRepository,
    private val workouts: WorkoutRepository,
    private val sessions: SessionRepository,
    private val activities: ActivityRepository,
    private val plans: PlanRepository,
    private val store: CoachStore,
    private val zones: com.yonizilber.flownics.data.garmin.Zones,
) {
    val specs: List<AiToolSpec> = listOf(
        AiToolSpec(
            CoachToolNames.TRAINING_LOG,
            "The user's training between two dates, newest first: workouts from the app (each exercise with every set as done: " +
                "reps × kg, seconds, meters; identical sets folded, skipped sets counted; length and notes) and activities they logged " +
                "(runs, rides…: distance, time, pace, heart rate, effort, and every part such as each interval and recovery). " +
                "A workout done with the watch recording shows its heart rate set by set. " +
                "With exercise_id, only that exercise, plus its all-time records. Use it before judging progress, load or recovery.",
            AiSchema.Obj(
                mapOf(
                    "from_date" to AiSchema.Str(description = "YYYY-MM-DD, inclusive."),
                    "to_date" to AiSchema.Str(description = "YYYY-MM-DD, inclusive."),
                    "exercise_id" to AiSchema.Integer(description = "One exercise's id, or 0 for all."),
                ),
            ),
        ),
        AiToolSpec(
            CoachToolNames.MUSCLE_LOAD,
            "Sets per muscle between two dates, counted by the app from the exercises' muscle tags (a secondary muscle counts as half), and which muscles weren't worked.",
            AiSchema.Obj(
                mapOf(
                    "from_date" to AiSchema.Str(description = "YYYY-MM-DD, inclusive."),
                    "to_date" to AiSchema.Str(description = "YYYY-MM-DD, inclusive."),
                ),
            ),
        ),
        AiToolSpec(
            CoachToolNames.EXERCISES_SEARCH,
            "Find exercises in the user's library by words in the name or equipment, by muscle, and/or by category. " +
                "Returns id, name, category, how it is measured, level and muscles. An empty query with $ANY for both lists the whole library (up to 60).",
            AiSchema.Obj(
                mapOf(
                    "query" to AiSchema.Str(description = "Words to look for, or empty."),
                    "muscle" to AiSchema.Str(enum = listOf(ANY) + Muscle.entries.map { it.name }, description = "$ANY for any muscle."),
                    "category" to AiSchema.Str(enum = listOf(ANY) + ExerciseCategory.entries.map { it.name }, description = "$ANY for any category."),
                ),
            ),
        ),
        AiToolSpec(
            CoachToolNames.EXERCISE_DETAILS,
            "Everything the library holds about one exercise: muscles, instructions, cues, stages, notes.",
            AiSchema.Obj(mapOf("exercise_id" to AiSchema.Integer())),
        ),
        AiToolSpec(
            CoachToolNames.WORKOUT_DETAILS,
            "One saved workout's full structure: blocks, exercises with their ids, sets, targets, rest, estimated length and sets per muscle.",
            AiSchema.Obj(mapOf("workout_id" to AiSchema.Integer())),
        ),
        AiToolSpec(
            CoachToolNames.PROPOSE_WORKOUT,
            "Propose a new workout, or a changed version of a saved one, built only from exercises in the library (by id). " +
                "It is shown to the user as a card; nothing is saved unless they choose to. Don't repeat the whole workout in your reply.",
            AiSchema.Obj(
                FitnessPrompts.workoutSchema.properties + mapOf(
                    "replaces_workout_id" to AiSchema.Integer(description = "The saved workout this changes, or 0 for a new one."),
                ),
            ),
        ),
        AiToolSpec(
            CoachToolNames.PROPOSE_EXERCISE_CHANGE,
            "Propose changes to an exercise in the library (e.g. add the cues you just gave to its cues, fix its muscles, " +
                "make it a style of another exercise's family), or a new exercise (exercise_id 0). Shown to the user as a card listing " +
                "each change; they choose which to apply. For list fields give the whole list as it should be.",
            CoachProposals.exerciseChangeSchema,
        ),
        AiToolSpec(
            CoachToolNames.PROPOSE_ACTIVITY,
            "Propose logging an activity the user tells you about (a run, a ride, a swim…), with its parts if they described them. " +
                "Shown to the user as a card; it is saved to their history only if they choose to. Ask for what's missing first if it matters (at least the time).",
            CoachProposals.activitySchema,
        ),
        AiToolSpec(
            CoachToolNames.PLAN_LIST,
            "The training plan between two dates: each planned day with its id, what it is, its parts and targets, and whether it was done, skipped or not done. The status already has the next 14 days.",
            AiSchema.Obj(
                mapOf(
                    "from_date" to AiSchema.Str(description = "YYYY-MM-DD, inclusive."),
                    "to_date" to AiSchema.Str(description = "YYYY-MM-DD, inclusive."),
                ),
            ),
        ),
        AiToolSpec(
            CoachToolNames.PROPOSE_PLAN,
            "Propose changes to the training plan: days to add (a saved workout by id, or a run or other activity with its parts and pace or heart-rate targets), " +
                "plans to move, plans to drop. Shown to the user as a card listing each change; they choose which to accept. " +
                "Accepted plans appear in their journal and, if they turned it on, in their calendar. Look at their recent training and plan first.",
            CoachProposals.planChangeSchema,
        ),
        AiToolSpec(
            CoachToolNames.MEMORY_SAVE,
            "Remember a lasting fact about the user for future conversations: a goal, an injury or limitation, a preference, " +
                "their schedule, how they respond to training. One fact per call, in a short sentence. Not for one-off details or what the profile already says.",
            AiSchema.Obj(mapOf("text" to AiSchema.Str())),
        ),
        AiToolSpec(
            CoachToolNames.MEMORY_DELETE,
            "Forget one of your memory notes (by its id from the status) when it is no longer true.",
            AiSchema.Obj(mapOf("note_id" to AiSchema.Integer())),
        ),
    )

    /** Runs one call. A bad argument comes back to the model as an error it can correct. */
    suspend fun run(call: AgentToolCall, zone: ZoneId, now: Long): ToolOutcome = try {
        when (call.name) {
            CoachToolNames.TRAINING_LOG -> {
                val (from, to) = range(call.args)
                Read(
                    CoachFormat.trainingLog(
                        from, to, call.args.long("exercise_id")?.takeIf { it > 0 },
                        sessions.observeCompleted().first(), sessions.observeCompletedLogs().first(), library(), zone,
                        activities = activities.observeAll().first(),
                        watch = activities.observeAttached().first(),
                        zones = zones.info.value?.zones,
                    ),
                )
            }
            CoachToolNames.MUSCLE_LOAD -> {
                val (from, to) = range(call.args)
                Read(CoachFormat.muscleLoad(from, to, sessions.observeCompleted().first(), sessions.observeCompletedLogs().first(), library(), zone))
            }
            CoachToolNames.EXERCISES_SEARCH -> Read(search(call.args))
            CoachToolNames.EXERCISE_DETAILS -> {
                val id = call.args.long("exercise_id") ?: bad("exercise_id is required")
                val e = exercises.get(id) ?: bad("There is no exercise $id. Use exercises_search to find ids.")
                Read("Exercise ${e.id}:\n" + FitnessPrompts.describe(e))
            }
            CoachToolNames.WORKOUT_DETAILS -> {
                val id = call.args.long("workout_id") ?: bad("workout_id is required")
                val w = workouts.get(id) ?: bad("There is no saved workout $id. The status lists the saved workouts.")
                val library = library()
                val compiled = WorkoutCompiler.compile(w, library)
                Read("Workout ${w.id}:\n" + FitnessPrompts.describeWorkout(w, library, compiled.estimatedSeconds, MuscleLoad.of(compiled, library)))
            }
            CoachToolNames.PROPOSE_WORKOUT -> propose(call.args)
            CoachToolNames.PLAN_LIST -> {
                val (from, to) = range(call.args)
                val all = plans.observeAll().first()
                val matched = PlanMatching.match(all, sessions.observeCompleted().first(), activities.observeAll().first(), zone)
                Read(CoachFormat.planList(from, to, all, matched, workouts.observeAll().first().associate { it.id to it.name }))
            }
            CoachToolNames.PROPOSE_PLAN -> {
                val change = try {
                    CoachProposals.planChange(call.args, plans.observeAll().first().associateBy { it.id }, workouts.observeAll().first().map { it.id }.toSet())
                } catch (e: ProposalException) {
                    bad(e.message.orEmpty())
                }
                ToolOutcome.Proposed(
                    "Shown to the user as a card: ${change.adds.size} to add, ${change.moves.size} to move, ${change.removes.size} to drop. " +
                        "Nothing changes until they accept; they may accept only some rows.",
                    ProposalKind.PLAN,
                )
            }
            CoachToolNames.PROPOSE_EXERCISE_CHANGE -> {
                val change = try {
                    CoachProposals.exerciseChange(call.args, library())
                } catch (e: ProposalException) {
                    bad(e.message.orEmpty())
                }
                ToolOutcome.Proposed(
                    if (change.isNew) {
                        "Shown to the user as a card: a new exercise \"${change.proposed.name}\" (not saved). They decide in the app."
                    } else {
                        "Shown to the user as a card: changes to exercise ${change.current?.id} (${change.fields.joinToString { it.name.lowercase() }}), not applied yet. " +
                            "They choose which to apply in the app."
                    },
                    ProposalKind.EXERCISE,
                )
            }
            CoachToolNames.PROPOSE_ACTIVITY -> {
                val activity = try {
                    CoachProposals.activity(call.args, zone)
                } catch (e: ProposalException) {
                    bad(e.message.orEmpty())
                }
                ToolOutcome.Proposed(
                    "Shown to the user as a card: ${CoachFormat.activityHeadline(activity, zone).substringAfter(": ")}, ${activity.segments.size} parts. " +
                        "Not saved yet; they decide in the app.",
                    ProposalKind.ACTIVITY,
                )
            }
            CoachToolNames.MEMORY_SAVE -> {
                val text = call.args.string("text")?.trim().orEmpty().ifEmpty { bad("text is empty") }
                val id = store.addNote(text, now)
                ToolOutcome.Remembered("Saved as note $id.", id, text, removed = false)
            }
            CoachToolNames.MEMORY_DELETE -> {
                val id = call.args.long("note_id") ?: bad("note_id is required")
                val text = store.notes().firstOrNull { it.id == id }?.text ?: bad("There is no note $id.")
                store.deleteNote(id)
                ToolOutcome.Remembered("Note $id deleted.", id, text, removed = true)
            }
            else -> Read("Unknown tool ${call.name}.", isError = true)
        }
    } catch (e: BadArgument) {
        Read(e.message.orEmpty(), isError = true)
    }

    private suspend fun propose(args: JsonObject): ToolOutcome {
        val library = library()
        val generated = try {
            FitnessAnswers.workout(args.toString(), library)
        } catch (e: AiException.BadResponse) {
            bad("The workout couldn't be read: ${e.detail}. Send it again in the described shape.")
        }
        if (generated.template.blocks.isEmpty()) {
            bad("None of the items used an exercise id from the library, so there is nothing to propose. Look the ids up with exercises_search.")
        }
        val replaces = args.long("replaces_workout_id")?.takeIf { it > 0 }
        val replacing = replaces?.let { workouts.get(it) }
        val minutes = (WorkoutCompiler.compile(generated.template, library).estimatedSeconds + 59) / 60
        val content = buildString {
            append("Shown to the user as a proposal card (not saved): \"${generated.template.name}\", ")
            append("${generated.template.blocks.size} blocks, ${generated.itemCount} items, about $minutes min. ")
            if (replacing != null) append("They can replace workout ${replacing.id} (\"${replacing.name}\") with it or save it as a new one. ")
            if (replaces != null && replacing == null) append("There is no saved workout $replaces, so it is offered as a new workout. ")
            if (generated.skippedItems > 0) append("${generated.skippedItems} items were left out because their exercise ids aren't in the library. ")
            append("The user decides in the app; say in a sentence or two what you proposed and why.")
        }
        return ToolOutcome.Proposed(content, ProposalKind.WORKOUT, replacing?.id)
    }

    private suspend fun search(args: JsonObject): String {
        val query = args.string("query")?.trim()?.lowercase().orEmpty()
        val muscle = args.string("muscle")?.let { m -> Muscle.entries.firstOrNull { it.name == m } }
        val category = args.string("category")?.let { c -> ExerciseCategory.entries.firstOrNull { it.name == c } }
        val words = query.split(' ').filter { it.isNotBlank() }
        val found = exercises.observeAll().first()
            .filter { e -> words.all { w -> e.name.lowercase().contains(w) || e.equipment.lowercase().contains(w) } }
            .filter { e -> muscle == null || muscle in e.primaryMuscles || muscle in e.secondaryMuscles }
            .filter { e -> category == null || category in e.categories }
            .sortedBy { it.name.lowercase() }
        if (found.isEmpty()) return "No exercise matches. Try fewer words, or no filters to list the library."
        return buildString {
            appendLine("${found.size} exercises (id: name | category | measured by | level | muscles | equipment):")
            found.take(MAX_SEARCH).forEach { appendLine(CoachFormat.exerciseLine(it)) }
            if (found.size > MAX_SEARCH) append("…and ${found.size - MAX_SEARCH} more. Narrow the search.")
        }.trimEnd()
    }

    private suspend fun library(): Map<Long, Exercise> = exercises.observeAll().first().associateBy { it.id }

    private fun range(args: JsonObject): Pair<LocalDate, LocalDate> {
        val from = date(args, "from_date")
        val to = date(args, "to_date")
        if (to < from) bad("to_date is before from_date")
        return from to to
    }

    private fun date(args: JsonObject, key: String): LocalDate = try {
        LocalDate.parse(args.string(key).orEmpty().trim())
    } catch (e: DateTimeParseException) {
        bad("$key must be a date as YYYY-MM-DD")
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }

    private class BadArgument(message: String) : Exception(message)

    private fun bad(message: String): Nothing = throw BadArgument(message)

    private companion object {
        const val MAX_SEARCH = 60
        // Not an empty string: Gemini may refuse an empty enum value.
        const val ANY = "ANY"
    }
}

private typealias Read = ToolOutcome.Read
