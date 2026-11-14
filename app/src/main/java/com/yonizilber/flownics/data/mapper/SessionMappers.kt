package com.yonizilber.flownics.data.mapper

import com.yonizilber.flownics.data.local.db.entity.SessionHeaderRow
import com.yonizilber.flownics.data.local.db.entity.SessionPlanJson
import com.yonizilber.flownics.data.local.db.entity.SetLogEntity
import com.yonizilber.flownics.data.local.db.entity.StepJson
import com.yonizilber.flownics.data.local.db.entity.WorkoutSessionEntity
import com.yonizilber.flownics.domain.model.SessionHeader
import com.yonizilber.flownics.domain.model.SessionStatus
import com.yonizilber.flownics.domain.model.SetLog
import com.yonizilber.flownics.domain.model.SetTarget
import com.yonizilber.flownics.domain.model.WorkoutSession
import com.yonizilber.flownics.domain.workout.RestStep
import com.yonizilber.flownics.domain.workout.Side
import com.yonizilber.flownics.domain.workout.WorkStep
import com.yonizilber.flownics.domain.workout.WorkoutStep
import kotlinx.serialization.json.Json

fun WorkoutSessionEntity.toDomain(json: Json): WorkoutSession {
    val plan = json.decodeFromString(SessionPlanJson.serializer(), planJson)
    return WorkoutSession(
        id = id,
        templateId = templateId,
        workoutName = workoutName,
        blockTitles = plan.blockTitles,
        steps = plan.steps.map { it.toDomain() },
        status = SessionStatus.valueOf(status),
        startedAtMillis = startedAtMillis,
        endedAtMillis = endedAtMillis,
        currentIndex = currentIndex,
        notes = notes,
    )
}

fun WorkoutSession.toEntity(json: Json): WorkoutSessionEntity = WorkoutSessionEntity(
    id = id,
    templateId = templateId,
    workoutName = workoutName,
    planJson = json.encodeToString(SessionPlanJson.serializer(), SessionPlanJson(blockTitles, steps.map { it.toJson() })),
    status = status.name,
    startedAtMillis = startedAtMillis,
    endedAtMillis = endedAtMillis,
    currentIndex = currentIndex,
    notes = notes,
)

fun StepJson.toDomain(): WorkoutStep = when (this) {
    is StepJson.Work -> WorkStep(
        blockIndex = blockIndex,
        itemIndex = itemIndex,
        exerciseId = exerciseId,
        set = set,
        totalSets = totalSets,
        round = round,
        totalRounds = totalRounds,
        target = SetTarget(reps, seconds, weightKg, distanceMeters),
        side = side?.let(Side::valueOf),
        durationSeconds = durationSeconds,
        holdAtEnd = holdAtEnd,
        fromRests = fromRests,
    )
    is StepJson.Rest -> RestStep(
        blockIndex, durationSeconds, activeExerciseId, holdAtEnd, slot,
        activeTarget = if (listOf(activeReps, activeSeconds, activeWeightKg, activeDistanceMeters).all { it == null }) null else SetTarget(activeReps, activeSeconds, activeWeightKg, activeDistanceMeters),
    )
}

fun WorkoutStep.toJson(): StepJson = when (this) {
    is WorkStep -> StepJson.Work(
        blockIndex = blockIndex,
        itemIndex = itemIndex,
        exerciseId = exerciseId,
        set = set,
        totalSets = totalSets,
        round = round,
        totalRounds = totalRounds,
        reps = target.reps,
        seconds = target.seconds,
        weightKg = target.weightKg,
        distanceMeters = target.distanceMeters,
        side = side?.name,
        durationSeconds = durationSeconds,
        holdAtEnd = holdAtEnd,
        fromRests = fromRests,
    )
    is RestStep -> StepJson.Rest(
        blockIndex, durationSeconds, activeExerciseId, holdAtEnd, slot,
        activeTarget?.reps, activeTarget?.seconds, activeTarget?.weightKg, activeTarget?.distanceMeters,
    )
}

fun SetLogEntity.toDomain(): SetLog = SetLog(
    sessionId = sessionId,
    stepIndex = stepIndex,
    exerciseId = exerciseId,
    exerciseName = exerciseName,
    blockIndex = blockIndex,
    set = setNumber,
    round = round,
    side = side?.let(Side::valueOf),
    actual = SetTarget(reps, seconds, weightKg, distanceMeters),
    skipped = skipped,
    completedAtMillis = completedAtMillis,
    startedAtMillis = startedAtMillis,
)

fun SetLog.toEntity(): SetLogEntity = SetLogEntity(
    sessionId = sessionId,
    stepIndex = stepIndex,
    exerciseId = exerciseId,
    exerciseName = exerciseName,
    blockIndex = blockIndex,
    setNumber = set,
    round = round,
    side = side?.name,
    reps = actual.reps,
    seconds = actual.seconds,
    weightKg = actual.weightKg,
    distanceMeters = actual.distanceMeters,
    skipped = skipped,
    completedAtMillis = completedAtMillis,
    startedAtMillis = startedAtMillis,
)

// Only completed sessions are read as headers, and completing always sets the end time.
fun SessionHeaderRow.toDomain(): SessionHeader = SessionHeader(
    id = id,
    templateId = templateId,
    workoutName = workoutName,
    startedAtMillis = startedAtMillis,
    endedAtMillis = checkNotNull(endedAtMillis) { "Completed session $id has no end time" },
    notes = notes,
)
