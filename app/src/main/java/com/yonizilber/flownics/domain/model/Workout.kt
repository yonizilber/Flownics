package com.yonizilber.flownics.domain.model

data class WorkoutTemplate(
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val blocks: List<WorkoutBlock> = emptyList(),
    /** Another workout whose exercises fill this one's rests, one per rest, in order. */
    val restFiller: RestFiller? = null,
    /** Active rests wait for the user at zero (counting the extra time) instead of moving on. */
    val confirmActiveRest: Boolean = true,
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
)

/**
 * A section of a workout ("Warm-up", "Main", "Cool-down", or anything the user names it).
 * STRAIGHT_SETS runs each item's sets back to back; CIRCUIT runs one set of every item per round
 * (a 2-item circuit is a superset).
 */
data class WorkoutBlock(
    val title: String = "",
    val structure: BlockStructure = BlockStructure.STRAIGHT_SETS,
    /** CIRCUIT only. */
    val rounds: Int = 3,
    /** CIRCUIT only: rest after each round except the last. */
    val roundRestSeconds: Int = 60,
    /** CIRCUIT only: what to do during the rest between rounds. */
    val roundActiveRest: ActiveRest = ActiveRest.None,
    val items: List<WorkoutItem> = emptyList(),
)

enum class BlockStructure { STRAIGHT_SETS, CIRCUIT }

data class WorkoutItem(
    val exerciseId: Long,
    /** STRAIGHT_SETS only; a circuit's rounds decide how many times each item runs. */
    val sets: Int = 3,
    val target: SetTarget = SetTarget(),
    /** STRAIGHT_SETS: rest after every set. CIRCUIT: rest after this exercise within a round. */
    val restSeconds: Int = 60,
    val activeRest: ActiveRest = ActiveRest.None,
    /** A timed set of this exercise waits for the user at zero instead of moving on. */
    val confirmEnd: Boolean = false,
)

/** Which fields apply depends on the exercise's [MeasureMode]; the rest stay null. */
data class SetTarget(
    val reps: Int? = null,
    val seconds: Int? = null,
    val weightKg: Double? = null,
    val distanceMeters: Int? = null,
    /**
     * Logged sets only: reps done straight after the main part at another weight, in order (a
     * drop set: as many as possible with the weight, then on with less or none). Never planned.
     */
    val more: List<SetPart> = emptyList(),
)

/** Reps continued within the same set at another weight; [weightKg] null is without weight. */
data class SetPart(val reps: Int, val weightKg: Double? = null)

/** What fills a rest period: nothing, one chosen exercise, or a rotation drawn from the library. */
sealed interface ActiveRest {
    data object None : ActiveRest

    data class Specific(val exerciseId: Long) : ActiveRest

    data class Pool(
        val categories: Set<ExerciseCategory> = setOf(ExerciseCategory.FLEXIBILITY, ExerciseCategory.MOBILITY),
        val focus: PoolFocus = PoolFocus.WORKED_MUSCLES,
    ) : ActiveRest
}

enum class PoolFocus {
    /** Prefer exercises for the muscles just worked (e.g. stretch the quads after squats). */
    WORKED_MUSCLES,
    ANY,
}

/**
 * Two workouts done as one: during each rest of this workout (one without its own active rest),
 * the next exercise of [workoutId] comes up, in that workout's order. Each of its exercises comes
 * once, or with [repeatSets] as many times as it has sets there. With [leftoversAtEnd], what
 * didn't fit in the rests is done after the last set, so the other workout is done in full.
 * With [roundsTogether], a superset or circuit of the other workout comes as a whole: a round of
 * it in each rest, rather than one of its exercises.
 */
data class RestFiller(
    val workoutId: Long,
    val leftoversAtEnd: Boolean = true,
    val repeatSets: Boolean = false,
    val roundsTogether: Boolean = true,
)
