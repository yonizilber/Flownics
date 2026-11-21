package com.yonizilber.flownics.domain.ai

import com.yonizilber.flownics.domain.model.BlockStructure
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.ExerciseLevel
import com.yonizilber.flownics.domain.model.ExerciseStage
import com.yonizilber.flownics.domain.model.MeasureMode
import com.yonizilber.flownics.domain.model.Muscle
import com.yonizilber.flownics.domain.model.SetTarget
import com.yonizilber.flownics.domain.model.WorkoutBlock
import com.yonizilber.flownics.domain.model.WorkoutItem
import com.yonizilber.flownics.domain.model.WorkoutTemplate
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** An answer plus where it came from and what it cost, for the result card. */
data class AiResult<T>(val value: T, val sources: List<AiSource>, val model: AiModel, val costUsd: Double)

enum class Confidence { HIGH, MEDIUM, LOW }

data class ExerciseSuggestion(
    val summary: String,
    val category: ExerciseCategory?,
    /** Other kinds it also is; never repeats [category]. */
    val extraCategories: List<ExerciseCategory> = emptyList(),
    val measureMode: MeasureMode?,
    val unilateral: Boolean,
    val equipment: String,
    val primaryMuscles: Set<Muscle>,
    val secondaryMuscles: Set<Muscle>,
    val instructions: List<String>,
    val cues: List<String>,
    val stages: List<ExerciseStage>,
    val confidence: Confidence?,
    /** 1-5, null when the model gave none. */
    val level: Int?,
    val caveats: String,
    /** Values outside the app's lists (an unknown muscle name, say). Shown, never silently dropped. */
    val unknownValues: List<String>,
    /** When refining: what was changed and why, one line each. */
    val changes: List<String> = emptyList(),
)

enum class VariationKind { EASIER, HARDER, ALTERNATIVE }

data class ExerciseVariation(
    val name: String,
    val kind: VariationKind,
    val difference: String,
    val equipment: String,
    val category: ExerciseCategory?,
    val why: String,
    /** On the app's 1-5 scale, relative to the original's. */
    val level: Int? = null,
)

/** [baseLevel]: the AI's rating of the original exercise on the same scale. */
data class ExerciseVariations(val variations: List<ExerciseVariation>, val notes: String, val baseLevel: Int? = null)

enum class ReviewRating { GOOD, OK, NEEDS_WORK }
enum class IssueSeverity { INFO, WARN, PROBLEM }

data class ReviewIssue(val severity: IssueSeverity, val text: String)

data class WorkoutReview(
    val rating: ReviewRating?,
    val verdict: String,
    val balance: String,
    val issues: List<ReviewIssue>,
    val suggestions: List<String>,
)

data class MissingExercise(val name: String, val why: String)

data class GeneratedWorkout(
    /** Not saved yet (id 0). Only exercises that exist in the library are in it. */
    val template: WorkoutTemplate,
    val explanation: String,
    val missingExercises: List<MissingExercise>,
    /** Items the model gave with an exercise id that isn't in the library; left out and reported. */
    val skippedItems: Int,
) {
    val itemCount: Int get() = template.blocks.sumOf { it.items.size }
}

/** Turns the JSON each prompt asked for into app types, checking every value against the app's lists. */
object FitnessAnswers {

    private val json = Json { ignoreUnknownKeys = true }

    fun exerciseFill(text: String): ExerciseSuggestion {
        val dto = decode<FillDto>(text)
        val unknown = mutableListOf<String>()
        val primary = dto.primaryMuscles.mapNotNull { enumOrReport<Muscle>(it, unknown) }.toSet()
        val secondary = dto.secondaryMuscles.mapNotNull { enumOrReport<Muscle>(it, unknown) }.toSet() - primary
        return ExerciseSuggestion(
            summary = dto.summary.trim(),
            category = enumOrReport<ExerciseCategory>(dto.category, unknown),
            extraCategories = dto.extraCategories.mapNotNull { enumOrReport<ExerciseCategory>(it, unknown) }.distinct()
                .filter { it.name != dto.category },
            measureMode = enumOrReport<MeasureMode>(dto.measureMode, unknown),
            unilateral = dto.unilateral,
            equipment = dto.equipment.trim(),
            primaryMuscles = primary,
            secondaryMuscles = secondary,
            instructions = dto.instructions.map { it.trim().removePrefix("-").trim() }.filter { it.isNotEmpty() },
            cues = dto.cues.map { it.trim() }.filter { it.isNotEmpty() },
            stages = dto.stages
                .filter { it.name.isNotBlank() || it.description.isNotBlank() }
                .map { ExerciseStage(it.name.trim(), it.description.trim(), it.seconds.takeIf { s -> s > 0 }) },
            confidence = enumOrReport<Confidence>(dto.confidence, unknown),
            level = dto.level.asLevel(),
            caveats = dto.caveats.trim(),
            unknownValues = unknown,
            changes = dto.changes.map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    fun variations(text: String): ExerciseVariations {
        val dto = decode<VariationsDto>(text)
        return ExerciseVariations(
            variations = dto.variations.filter { it.name.isNotBlank() }.map {
                ExerciseVariation(
                    name = it.name.trim(),
                    kind = enumOrNull<VariationKind>(it.kind) ?: VariationKind.ALTERNATIVE,
                    difference = it.difference.trim(),
                    equipment = it.equipment.trim(),
                    category = enumOrNull<ExerciseCategory>(it.category),
                    why = it.why.trim(),
                    level = it.level.asLevel(),
                )
            },
            notes = dto.notes.trim(),
            baseLevel = dto.baseLevel.asLevel(),
        )
    }

    /** (url, why) pairs, in the model's order. The links are checked by the caller. */
    fun videos(text: String): List<Pair<String, String>> =
        decode<VideosDto>(text).videos.filter { it.url.isNotBlank() }.map { it.url.trim() to it.why.trim() }

    fun review(text: String): WorkoutReview {
        val dto = decode<ReviewDto>(text)
        return WorkoutReview(
            rating = enumOrNull<ReviewRating>(dto.rating),
            verdict = dto.verdict.trim(),
            balance = dto.balance.trim(),
            issues = dto.issues.filter { it.text.isNotBlank() }
                .map { ReviewIssue(enumOrNull<IssueSeverity>(it.severity) ?: IssueSeverity.INFO, it.text.trim()) },
            suggestions = dto.suggestions.map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    fun workout(text: String, library: Map<Long, Exercise>): GeneratedWorkout {
        val dto = decode<WorkoutDto>(text)
        var skipped = 0
        val blocks = dto.blocks.mapNotNull { block ->
            val structure = enumOrNull<BlockStructure>(block.structure) ?: BlockStructure.STRAIGHT_SETS
            val items = block.items.mapNotNull { item ->
                val exercise = library[item.exerciseId]
                if (exercise == null) {
                    skipped++
                    return@mapNotNull null
                }
                WorkoutItem(
                    exerciseId = exercise.id,
                    sets = item.sets.coerceIn(1, MAX_SETS),
                    target = targetFor(exercise.measureMode, item),
                    restSeconds = item.restSeconds.coerceIn(0, MAX_REST_SECONDS),
                )
            }
            if (items.isEmpty()) return@mapNotNull null
            WorkoutBlock(
                title = block.title.trim(),
                structure = structure,
                rounds = block.rounds.coerceIn(1, MAX_ROUNDS),
                roundRestSeconds = block.roundRestSeconds.coerceIn(0, MAX_REST_SECONDS),
                items = items,
            )
        }
        return GeneratedWorkout(
            template = WorkoutTemplate(name = dto.name.trim(), description = dto.description.trim(), blocks = blocks),
            explanation = dto.explanation.trim(),
            missingExercises = dto.missingExercises.filter { it.name.isNotBlank() }.map { MissingExercise(it.name.trim(), it.why.trim()) },
            skippedItems = skipped,
        )
    }

    // A target only carries the fields the exercise is measured by, like the workout editor's.
    private fun targetFor(mode: MeasureMode, item: WorkoutItemDto) = when (mode) {
        MeasureMode.REPS -> SetTarget(reps = item.reps.positive())
        MeasureMode.REPS_WEIGHT -> SetTarget(reps = item.reps.positive(), weightKg = item.weightKg.takeIf { it > 0 })
        MeasureMode.TIME -> SetTarget(seconds = item.seconds.positive())
        MeasureMode.DISTANCE -> SetTarget(distanceMeters = item.distanceMeters.positive())
    }

    private fun Int.positive(): Int? = takeIf { it > 0 }

    // 0 is the schema's "not rated"; anything outside the scale is not guessed into it.
    private fun Int.asLevel(): Int? = takeIf { it in ExerciseLevel.RANGE }

    private inline fun <reified T> decode(text: String): T = try {
        json.decodeFromString<T>(text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
    } catch (e: SerializationException) {
        throw AiException.BadResponse("The answer was not in the expected shape", e)
    } catch (e: IllegalArgumentException) {
        throw AiException.BadResponse("The answer was not in the expected shape", e)
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    private inline fun <reified T : Enum<T>> enumOrReport(name: String, unknown: MutableList<String>): T? {
        if (name.isBlank()) return null
        return enumOrNull<T>(name) ?: null.also { unknown += name.trim() }
    }

    private const val MAX_SETS = 20
    private const val MAX_ROUNDS = 20
    private const val MAX_REST_SECONDS = 600

    @Serializable
    private data class FillDto(
        val summary: String = "",
        val category: String = "",
        val extraCategories: List<String> = emptyList(),
        val measureMode: String = "",
        val unilateral: Boolean = false,
        val equipment: String = "",
        val primaryMuscles: List<String> = emptyList(),
        val secondaryMuscles: List<String> = emptyList(),
        val instructions: List<String> = emptyList(),
        val cues: List<String> = emptyList(),
        val stages: List<StageDto> = emptyList(),
        val confidence: String = "",
        val level: Int = 0,
        val caveats: String = "",
        val changes: List<String> = emptyList(),
    )

    @Serializable
    private data class StageDto(val name: String = "", val description: String = "", val seconds: Int = 0)

    @Serializable
    private data class VariationsDto(val variations: List<VariationDto> = emptyList(), val notes: String = "", val baseLevel: Int = 0)

    @Serializable
    private data class VariationDto(
        val name: String = "",
        val kind: String = "",
        val difference: String = "",
        val equipment: String = "",
        val category: String = "",
        val why: String = "",
        val level: Int = 0,
    )

    @Serializable
    private data class VideosDto(val videos: List<VideoDto> = emptyList())

    @Serializable
    private data class VideoDto(val url: String = "", val why: String = "")

    @Serializable
    private data class ReviewDto(
        val rating: String = "",
        val verdict: String = "",
        val balance: String = "",
        val issues: List<IssueDto> = emptyList(),
        val suggestions: List<String> = emptyList(),
    )

    @Serializable
    private data class IssueDto(val severity: String = "", val text: String = "")

    @Serializable
    private data class WorkoutDto(
        val name: String = "",
        val description: String = "",
        val blocks: List<BlockDto> = emptyList(),
        val explanation: String = "",
        val missingExercises: List<MissingDto> = emptyList(),
    )

    @Serializable
    private data class BlockDto(
        val title: String = "",
        val structure: String = "",
        val rounds: Int = 1,
        val roundRestSeconds: Int = 0,
        val items: List<WorkoutItemDto> = emptyList(),
    )

    @Serializable
    private data class WorkoutItemDto(
        val exerciseId: Long = 0,
        val sets: Int = 1,
        val reps: Int = 0,
        val seconds: Int = 0,
        val weightKg: Double = 0.0,
        val distanceMeters: Int = 0,
        val restSeconds: Int = 60,
    )

    @Serializable
    private data class MissingDto(val name: String = "", val why: String = "")
}
