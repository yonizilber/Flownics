package com.yonizilber.flownics.domain.model

data class Exercise(
    val id: Long = 0,
    val name: String,
    /** The main kind, shown as the exercise's icon. */
    val category: ExerciseCategory,
    /** Other kinds it also is, e.g. a single-leg RDL is strength and balance. Never holds [category]. */
    val extraCategories: Set<ExerciseCategory> = emptySet(),
    val primaryMuscles: Set<Muscle> = emptySet(),
    val secondaryMuscles: Set<Muscle> = emptySet(),
    val equipment: String = "",
    /** How to do it, step by step: the basis of the exercise. */
    val instructions: List<String> = emptyList(),
    /** What to watch for while doing it (not steps). */
    val cues: List<String> = emptyList(),
    val stages: List<ExerciseStage> = emptyList(),
    val media: List<ExerciseMedia> = emptyList(),
    val measureMode: MeasureMode = MeasureMode.REPS,
    val unilateral: Boolean = false,
    /** 1 (anyone can do it) to 5 (very advanced); null when not rated. See [ExerciseLevel]. */
    val level: Int? = null,
    /**
     * The exercise this one is a variation of, if any. Exercises sharing a base form a family,
     * shown together by level. Not a foreign key: deleting the base just ends the family.
     */
    val variationOf: Long? = null,
    val notes: String = "",
    /** The user's own labels ("side split", "knee rehab"), each with what this exercise does for it. */
    val tags: List<ExerciseTag> = emptyList(),
    /**
     * A general step rather than a specific exercise ("general warm-up", "easy jog"): told by its
     * name and notes, timed or until the user moves on, with no reps to log.
     */
    val general: Boolean = false,
    /** Made from just a name during a workout; its details are still to be filled in. */
    val quickAdded: Boolean = false,
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
) {
    /** Every kind it is, the main one first. Filters and pools match on any of them. */
    val categories: List<ExerciseCategory> get() = listOf(category) + (extraCategories - category).sortedBy { it.ordinal }
}

/** A free label on an exercise; [note] says what this exercise does for it ("opens the adductors"). */
data class ExerciseTag(val name: String, val note: String = "")

/** What a tag is for, written once for all its exercises ("side split: the goal is the floor by summer"). */
data class TagInfo(val name: String, val description: String = "")

object Tags {
    /** Tags are matched ignoring case and spaces at the ends. */
    fun key(name: String) = name.trim().lowercase()

    /** Every tag in the library, by how many exercises carry it, then by name. */
    fun all(exercises: Collection<Exercise>): List<Pair<String, Int>> =
        exercises.flatMap { e -> e.tags.map { it.name.trim() }.distinctBy(::key) }
            .groupBy(::key)
            .map { (_, names) -> names.first() to names.size }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })
}

// A distinct phase of one exercise (e.g. "lower slowly" → "pause" → "explode up"), or a progression
// step. Stages stay part of the same exercise rather than becoming separate exercises.
data class ExerciseStage(
    val name: String,
    val description: String = "",
    val durationSeconds: Int? = null,
)

data class ExerciseMedia(
    val kind: MediaKind,
    // IMAGE/VIDEO: a file name inside the app's private media folder. LINK: an https URL.
    val location: String,
)

enum class MediaKind { IMAGE, VIDEO, LINK }

// What the exercise is for. Drives active-rest pairing (e.g. flexibility work during strength rest).
enum class ExerciseCategory {
    STRENGTH,
    FLEXIBILITY,
    MOBILITY,
    CARDIO,
    CORE,
    BALANCE,
    BREATHING,
    WARMUP,
}

enum class MeasureMode { REPS, TIME, REPS_WEIGHT, DISTANCE }

enum class Muscle {
    NECK,
    TRAPS,
    SHOULDERS,
    CHEST,
    UPPER_BACK,
    LATS,
    LOWER_BACK,
    BICEPS,
    TRICEPS,
    FOREARMS,
    ABS,
    OBLIQUES,
    HIP_FLEXORS,
    GLUTES,
    ADDUCTORS,
    ABDUCTORS,
    QUADS,
    HAMSTRINGS,
    CALVES,
}

object ExerciseLevel {
    val RANGE = 1..5
}
