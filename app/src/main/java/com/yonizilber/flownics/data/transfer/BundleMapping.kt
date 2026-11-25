package com.yonizilber.flownics.data.transfer

import com.yonizilber.flownics.data.local.db.entity.SetParts

import com.yonizilber.flownics.data.local.db.entity.ActiveRestJson
import com.yonizilber.flownics.data.local.db.entity.ActivityEntity
import com.yonizilber.flownics.data.local.db.entity.AiUsageEntity
import com.yonizilber.flownics.data.local.db.entity.PlannedStepJson
import com.yonizilber.flownics.data.local.db.entity.PlannedWorkoutEntity
import com.yonizilber.flownics.data.local.db.entity.SegmentJson
import com.yonizilber.flownics.data.repository.toDomain
import com.yonizilber.flownics.domain.coach.CoachEntryKind
import com.yonizilber.flownics.data.local.db.entity.BlockJson
import com.yonizilber.flownics.data.local.db.entity.ExerciseAiNoteEntity
import com.yonizilber.flownics.data.local.db.entity.ExerciseEntity
import com.yonizilber.flownics.data.local.db.entity.TagJson
import com.yonizilber.flownics.data.local.db.entity.ExerciseVariationEntity
import com.yonizilber.flownics.data.local.db.entity.MediaJson
import com.yonizilber.flownics.data.local.db.entity.SessionPlanJson
import com.yonizilber.flownics.data.local.db.entity.SetLogEntity
import com.yonizilber.flownics.data.local.db.entity.StageJson
import com.yonizilber.flownics.data.local.db.entity.WorkoutSessionEntity
import com.yonizilber.flownics.data.local.db.entity.WorkoutTemplateEntity
import com.yonizilber.flownics.data.mapper.decodeCategories
import com.yonizilber.flownics.data.mapper.decodeMuscles
import com.yonizilber.flownics.data.mapper.encodeCategories
import com.yonizilber.flownics.data.mapper.encodeMuscles
import com.yonizilber.flownics.data.mapper.toDomain
import com.yonizilber.flownics.domain.ai.AiOutcome
import com.yonizilber.flownics.domain.ai.VariationKind
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.ExerciseLevel
import com.yonizilber.flownics.domain.model.Muscle
import com.yonizilber.flownics.domain.workout.Side
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.Locale

// Between the database rows and the file format. Reading a row back through the app's own
// mappers is the validation: an unknown name (from a newer app, or a hand-edited file) fails there.

private val strings = ListSerializer(String.serializer())

internal fun ExerciseEntity.toDto(json: Json) = ExerciseDto(
    id = id,
    name = name,
    category = category,
    extraCategories = decodeCategories(extraCategories).sortedBy { it.ordinal }.map { it.name },
    primaryMuscles = decodeMuscles(primaryMuscles).sortedBy { it.ordinal }.map { it.name },
    secondaryMuscles = decodeMuscles(secondaryMuscles).sortedBy { it.ordinal }.map { it.name },
    equipment = equipment,
    instructions = json.decodeFromString(strings, instructionsJson),
    cues = json.decodeFromString(strings, cuesJson),
    stages = json.decodeFromString(ListSerializer(StageJson.serializer()), stagesJson),
    media = json.decodeFromString(ListSerializer(MediaJson.serializer()), mediaJson),
    measureMode = measureMode,
    unilateral = unilateral,
    level = level,
    variationOf = variationOf,
    notes = notes,
    tags = json.decodeFromString(ListSerializer(TagJson.serializer()), tagsJson),
    general = general,
    quickAdded = quickAdded,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

internal fun ExerciseDto.toEntity(
    json: Json,
    id: Long = this.id,
    name: String = this.name,
    variationOf: Long? = this.variationOf,
    media: List<MediaJson> = this.media,
) = ExerciseEntity(
    id = id,
    name = name,
    category = category,
    extraCategories = encodeCategories(extraCategories.map(ExerciseCategory::valueOf).toSet() - ExerciseCategory.valueOf(category)),
    primaryMuscles = encodeMuscles(primaryMuscles.map(Muscle::valueOf).toSet()),
    secondaryMuscles = encodeMuscles(secondaryMuscles.map(Muscle::valueOf).toSet()),
    equipment = equipment,
    cuesJson = json.encodeToString(strings, cues),
    instructionsJson = json.encodeToString(strings, instructions),
    stagesJson = json.encodeToString(ListSerializer(StageJson.serializer()), stages),
    mediaJson = json.encodeToString(ListSerializer(MediaJson.serializer()), media),
    measureMode = measureMode,
    unilateral = unilateral,
    level = level,
    variationOf = variationOf,
    notes = notes,
    tagsJson = json.encodeToString(ListSerializer(TagJson.serializer()), tags),
    general = general,
    quickAdded = quickAdded,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

internal fun WorkoutTemplateEntity.toDto(json: Json) = WorkoutDto(
    id = id,
    name = name,
    description = description,
    blocks = json.decodeFromString(ListSerializer(BlockJson.serializer()), blocksJson),
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    restFillerWorkoutId = restFillerWorkoutId,
    restFillerLeftovers = restFillerLeftovers,
    restFillerRepeatSets = restFillerRepeatSets,
    restFillerRoundsTogether = restFillerRoundsTogether,
    confirmActiveRest = confirmActiveRest,
)

internal fun WorkoutDto.toEntity(
    json: Json,
    id: Long = this.id,
    name: String = this.name,
    blocks: List<BlockJson> = this.blocks,
    restFillerWorkoutId: Long? = this.restFillerWorkoutId,
) =
    WorkoutTemplateEntity(
        id = id,
        name = name,
        description = description,
        blocksJson = json.encodeToString(ListSerializer(BlockJson.serializer()), blocks),
        restFillerWorkoutId = restFillerWorkoutId,
        restFillerLeftovers = restFillerLeftovers,
        restFillerRepeatSets = restFillerRepeatSets,
        restFillerRoundsTogether = restFillerRoundsTogether,
        confirmActiveRest = confirmActiveRest,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )

internal fun WorkoutSessionEntity.toDto(json: Json, logs: List<SetLogEntity>) = SessionDto(
    id = id,
    templateId = templateId,
    workoutName = workoutName,
    plan = json.decodeFromString(SessionPlanJson.serializer(), planJson),
    startedAtMillis = startedAtMillis,
    endedAtMillis = endedAtMillis,
    notes = notes,
    logs = logs.map {
        SetLogDto(
            stepIndex = it.stepIndex,
            exerciseId = it.exerciseId,
            exerciseName = it.exerciseName,
            blockIndex = it.blockIndex,
            setNumber = it.setNumber,
            round = it.round,
            side = it.side,
            reps = it.reps,
            seconds = it.seconds,
            weightKg = it.weightKg,
            distanceMeters = it.distanceMeters,
            skipped = it.skipped,
            completedAtMillis = it.completedAtMillis,
            startedAtMillis = it.startedAtMillis,
            more = SetParts.decode(it.moreJson),
        )
    },
)

/** Only finished sessions travel, so a restored one is always COMPLETED. */
internal fun SessionDto.toEntity(json: Json) = WorkoutSessionEntity(
    id = id,
    templateId = templateId,
    workoutName = workoutName,
    planJson = json.encodeToString(SessionPlanJson.serializer(), plan),
    status = "COMPLETED",
    startedAtMillis = startedAtMillis,
    endedAtMillis = endedAtMillis,
    currentIndex = 0,
    notes = notes,
)

internal fun SetLogDto.toEntity(sessionId: Long) = SetLogEntity(
    sessionId = sessionId,
    stepIndex = stepIndex,
    exerciseId = exerciseId,
    exerciseName = exerciseName,
    blockIndex = blockIndex,
    setNumber = setNumber,
    round = round,
    side = side,
    reps = reps,
    seconds = seconds,
    weightKg = weightKg,
    distanceMeters = distanceMeters,
    skipped = skipped,
    completedAtMillis = completedAtMillis,
    startedAtMillis = startedAtMillis,
    moreJson = SetParts.encode(more),
)

internal fun ExerciseVariationEntity.toDto() =
    VariationDto(exerciseId, name, kind, difference, equipment, category, why, level, modelId, atMillis, addedExerciseId)

internal fun VariationDto.toEntity(exerciseId: Long = this.exerciseId, addedExerciseId: Long? = this.addedExerciseId) =
    ExerciseVariationEntity(
        exerciseId = exerciseId,
        name = name,
        kind = kind,
        difference = difference,
        equipment = equipment,
        category = category,
        why = why,
        level = level,
        modelId = modelId,
        atMillis = atMillis,
        addedExerciseId = addedExerciseId,
    )

@kotlinx.serialization.Serializable
private data class StoredSource(val title: String, val url: String)

internal fun ExerciseAiNoteEntity.toDto(json: Json) = AiNoteDto(
    exerciseId = exerciseId,
    atMillis = atMillis,
    modelId = modelId,
    summary = summary,
    applied = json.decodeFromString(strings, appliedJson),
    sources = json.decodeFromString(ListSerializer(StoredSource.serializer()), sourcesJson).map { SourceDto(it.title, it.url) },
)

internal fun AiNoteDto.toEntity(json: Json, exerciseId: Long = this.exerciseId) = ExerciseAiNoteEntity(
    exerciseId = exerciseId,
    atMillis = atMillis,
    modelId = modelId,
    summary = summary,
    appliedJson = json.encodeToString(strings, applied),
    sourcesJson = json.encodeToString(ListSerializer(StoredSource.serializer()), sources.map { StoredSource(it.title, it.url) }),
)

internal fun AiUsageEntity.toDto() =
    AiUsageDto(atMillis, feature, modelId, inputTokens, outputTokens, webSearches, costUsd, subject, durationMs, outcome, detail)

internal fun AiUsageDto.toEntity() = AiUsageEntity(
    atMillis = atMillis,
    feature = feature,
    modelId = modelId,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    webSearches = webSearches,
    costUsd = costUsd,
    subject = subject,
    durationMs = durationMs,
    outcome = outcome,
    detail = detail.take(200),
)

/** What importing a shared file will do, worked out before anything is written. */
data class SharePlan(
    /** Exercises to add, with their names made unique where they clash. */
    val insert: List<ExerciseDto>,
    /** The file's exercise id → the user's own exercise with the same name, used instead. */
    val reuse: Map<Long, Long>,
    val workouts: List<WorkoutDto>,
)

object BundleMerge {

    /**
     * Checks everything a file says before any of it is used: names the app knows, ids that are
     * unique, and references that point inside the file. Throws [BundleException.Invalid].
     */
    fun validate(data: BundleData, kind: BundleKind, json: Json) {
        fun fail(detail: String): Nothing = throw BundleException.Invalid(detail)
        fun <T> check(what: String, block: () -> T): T = try {
            block()
        } catch (e: IllegalArgumentException) {
            fail("$what: ${e.message}")
        } catch (e: SerializationException) {
            fail("$what: ${e.message}")
        }

        val exerciseIds = data.exercises.map { it.id }
        if (exerciseIds.size != exerciseIds.toSet().size) fail("an exercise appears twice")
        if (data.workouts.map { it.id }.let { it.size != it.toSet().size }) fail("a workout appears twice")
        if (data.sessions.map { it.id }.let { it.size != it.toSet().size }) fail("a session appears twice")
        val ids = exerciseIds.toSet()

        data.exercises.forEach { e ->
            if (e.name.isBlank()) fail("an exercise without a name")
            if (e.level != null && e.level !in ExerciseLevel.RANGE) fail("exercise '${e.name}': level ${e.level}")
            check("exercise '${e.name}'") { e.toEntity(json).toDomain(json) }
        }
        if (data.activities.map { it.id }.let { it.size != it.toSet().size }) fail("an activity appears twice")
        data.activities.forEach { a -> check("activity on ${a.startedAtMillis}") { a.toEntity(json).toDomain(json) } }
        data.plans.forEach { p -> check("plan on ${p.date}") { p.toEntity(json).toDomain(json) } }
        data.weights.forEach { w ->
            if (w.kg !in com.yonizilber.flownics.domain.body.Weights.RANGE) fail("a weigh-in of ${w.kg} kg")
            check("weigh-in on ${w.atMillis}") { com.yonizilber.flownics.domain.body.WeightSource.valueOf(w.source) }
        }
        if (data.weights.mapNotNull { it.externalKey }.let { it.size != it.toSet().size }) fail("a scale weigh-in appears twice")
        if (data.coachConversations.map { it.id }.let { it.size != it.toSet().size }) fail("a coach conversation appears twice")
        data.coachConversations.flatMap { it.messages }.forEach { m -> check("coach message") { CoachEntryKind.valueOf(m.kind) } }
        data.workouts.forEach { w ->
            if (w.name.isBlank()) fail("a workout without a name")
            check("workout '${w.name}'") { w.blocks.map { it.toDomain() } }
            // A shared workout brings every exercise it uses; a backup may keep a deleted one's id.
            if (kind == BundleKind.SHARE) {
                referencedExercises(w.blocks).firstOrNull { it !in ids }?.let { fail("workout '${w.name}' uses a missing exercise") }
            }
        }
        data.variations.forEach { v ->
            if (v.exerciseId !in ids) fail("a variation of a missing exercise")
            check("variation '${v.name}'") {
                VariationKind.valueOf(v.kind)
                if (v.category.isNotEmpty()) ExerciseCategory.valueOf(v.category)
            }
        }
        data.aiNotes.forEach { if (it.exerciseId !in ids) fail("a note on a missing exercise") }
        if (kind == BundleKind.SHARE && (data.sessions.isNotEmpty() || data.aiUsage.isNotEmpty())) fail("history in a shared file")
        data.sessions.forEach { s ->
            check("session '${s.workoutName}'") {
                s.plan.steps.map { it.toDomain() }
                s.logs.forEach { log -> log.side?.let(Side::valueOf) }
            }
            if (s.logs.map { it.stepIndex }.let { it.size != it.toSet().size }) fail("session '${s.workoutName}' logs a step twice")
        }
        data.aiUsage.forEach { u -> check("AI log") { AiOutcome.valueOf(u.outcome) } }
    }

    /** Every exercise id a workout's items and active rests point to. */
    fun referencedExercises(blocks: List<BlockJson>): Set<Long> = buildSet {
        fun rest(r: ActiveRestJson) {
            if (r is ActiveRestJson.Specific) add(r.exerciseId)
        }
        blocks.forEach { block ->
            rest(block.roundActiveRest)
            block.items.forEach { item ->
                add(item.exerciseId)
                rest(item.activeRest)
            }
        }
    }

    /**
     * [mine] is the user's library (id to name). With [reuseExisting], an exercise whose name the
     * user already has is not added again and workouts use the user's own; otherwise it is added
     * as a copy under a new name. Workouts are always added, renamed if the name is taken.
     */
    fun planShare(data: BundleData, mine: List<Pair<Long, String>>, myWorkoutNames: Collection<String>, reuseExisting: Boolean): SharePlan {
        val byName = mine.associate { (id, name) -> key(name) to id }
        val reuse = if (reuseExisting) {
            data.exercises.mapNotNull { e -> byName[key(e.name)]?.let { e.id to it } }.toMap()
        } else {
            emptyMap()
        }
        val taken = mine.map { key(it.second) }.toMutableSet()
        val insert = data.exercises.filter { it.id !in reuse }.map { it.copy(name = uniqueName(it.name, taken)) }
        val takenWorkouts = myWorkoutNames.map(::key).toMutableSet()
        val workouts = data.workouts.map { it.copy(name = uniqueName(it.name, takenWorkouts)) }
        return SharePlan(insert, reuse, workouts)
    }

    /** Exercises in the file whose name the user already has. */
    fun clashes(data: BundleData, mine: List<Pair<Long, String>>): List<String> {
        val names = mine.map { key(it.second) }.toSet()
        return data.exercises.filter { key(it.name) in names }.map { it.name }
    }

    /** [name], or "name (2)", "name (3)"… — the first not in [taken], which it is then added to. */
    fun uniqueName(name: String, taken: MutableSet<String>): String {
        val base = name.trim()
        var candidate = base
        var n = 2
        while (key(candidate) in taken) candidate = "$base (${n++})"
        taken += key(candidate)
        return candidate
    }

    /** The same blocks with every exercise id replaced through [ids]; every id must be in it. */
    fun remap(blocks: List<BlockJson>, ids: Map<Long, Long>): List<BlockJson> {
        fun id(old: Long) = ids[old] ?: throw BundleException.Invalid("exercise $old has no match")
        fun rest(r: ActiveRestJson) = if (r is ActiveRestJson.Specific) ActiveRestJson.Specific(id(r.exerciseId)) else r
        return blocks.map { block ->
            block.copy(
                roundActiveRest = rest(block.roundActiveRest),
                items = block.items.map { it.copy(exerciseId = id(it.exerciseId), activeRest = rest(it.activeRest)) },
            )
        }
    }

    private fun key(name: String) = name.trim().lowercase(Locale.ROOT)
}

internal fun ActivityEntity.toDto(json: Json) = ActivityDto(
    id = id,
    type = type,
    title = title,
    startedAtMillis = startedAtMillis,
    durationSeconds = durationSeconds,
    distanceMeters = distanceMeters,
    avgHeartRate = avgHeartRate,
    maxHeartRate = maxHeartRate,
    elevationGainMeters = elevationGainMeters,
    rpe = rpe,
    notes = notes,
    segments = json.decodeFromString(ListSerializer(SegmentJson.serializer()), segmentsJson),
    source = source,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    externalId = externalId,
    metricsJson = metricsJson,
    attachedSessionId = attachedSessionId,
    keepSeparate = keepSeparate,
)

internal fun ActivityDto.toEntity(json: Json) = ActivityEntity(
    id = id,
    type = type,
    title = title,
    startedAtMillis = startedAtMillis,
    durationSeconds = durationSeconds,
    distanceMeters = distanceMeters,
    avgHeartRate = avgHeartRate,
    maxHeartRate = maxHeartRate,
    elevationGainMeters = elevationGainMeters,
    rpe = rpe,
    notes = notes,
    segmentsJson = json.encodeToString(ListSerializer(SegmentJson.serializer()), segments),
    source = source,
    externalId = externalId,
    metricsJson = metricsJson,
    attachedSessionId = attachedSessionId,
    keepSeparate = keepSeparate,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

internal fun PlannedWorkoutEntity.toDto(json: Json) = PlanDto(
    id = id, date = date, time = time, kind = kind, workoutId = workoutId, activityType = activityType, title = title,
    durationMinutes = durationMinutes, notes = notes,
    steps = json.decodeFromString(ListSerializer(PlannedStepJson.serializer()), stepsJson),
    status = status, createdAtMillis = createdAtMillis, updatedAtMillis = updatedAtMillis,
)

internal fun PlanDto.toEntity(json: Json) = PlannedWorkoutEntity(
    id = id, date = date, time = time, kind = kind, workoutId = workoutId, activityType = activityType, title = title,
    durationMinutes = durationMinutes, notes = notes,
    stepsJson = json.encodeToString(ListSerializer(PlannedStepJson.serializer()), steps),
    status = status, createdAtMillis = createdAtMillis, updatedAtMillis = updatedAtMillis,
)
