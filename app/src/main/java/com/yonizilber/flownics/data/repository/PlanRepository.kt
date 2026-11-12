package com.yonizilber.flownics.data.repository

import com.yonizilber.flownics.data.local.db.dao.PlanDao
import com.yonizilber.flownics.data.local.db.entity.PlannedStepJson
import com.yonizilber.flownics.data.local.db.entity.PlannedWorkoutEntity
import com.yonizilber.flownics.domain.model.ActivityType
import com.yonizilber.flownics.domain.model.PlanKind
import com.yonizilber.flownics.domain.model.PlanStatus
import com.yonizilber.flownics.domain.model.PlannedStep
import com.yonizilber.flownics.domain.model.PlannedWorkout
import com.yonizilber.flownics.domain.model.SegmentKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/** The training plan: what is planned for which day. What was done comes from the history. */
@Singleton
class PlanRepository @Inject constructor(
    private val dao: PlanDao,
    private val json: Json,
) {
    fun observeAll(): Flow<List<PlannedWorkout>> = dao.observeAll().map { rows -> rows.map { it.toDomain(json) } }

    suspend fun get(id: Long): PlannedWorkout? = dao.get(id)?.toDomain(json)

    suspend fun between(from: LocalDate, to: LocalDate): List<PlannedWorkout> = dao.between(from.toString(), to.toString()).map { it.toDomain(json) }

    suspend fun save(plan: PlannedWorkout): Long {
        val now = System.currentTimeMillis()
        return if (plan.id == 0L) {
            dao.insert(plan.copy(createdAtMillis = now, updatedAtMillis = now).toEntity(json))
        } else {
            dao.update(plan.copy(updatedAtMillis = now).toEntity(json))
            plan.id
        }
    }

    suspend fun delete(id: Long) = dao.delete(id)
}

private val stepList = ListSerializer(PlannedStepJson.serializer())

fun PlannedWorkoutEntity.toDomain(json: Json) = PlannedWorkout(
    id = id,
    date = LocalDate.parse(date),
    time = time?.let(LocalTime::parse),
    kind = PlanKind.valueOf(kind),
    workoutId = workoutId,
    activityType = activityType?.let(ActivityType::valueOf),
    title = title,
    durationMinutes = durationMinutes,
    notes = notes,
    steps = json.decodeFromString(stepList, stepsJson).map {
        PlannedStep(SegmentKind.valueOf(it.kind), it.durationSeconds, it.distanceMeters, it.paceFastSecondsPerKm, it.paceSlowSecondsPerKm, it.maxHeartRate, it.note)
    },
    status = PlanStatus.valueOf(status),
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun PlannedWorkout.toEntity(json: Json) = PlannedWorkoutEntity(
    id = id,
    date = date.toString(),
    time = time?.withSecond(0)?.withNano(0)?.toString(),
    kind = kind.name,
    workoutId = workoutId,
    activityType = activityType?.name,
    title = title,
    durationMinutes = durationMinutes,
    notes = notes,
    stepsJson = json.encodeToString(
        stepList,
        steps.map { PlannedStepJson(it.kind.name, it.durationSeconds, it.distanceMeters, it.paceFastSecondsPerKm, it.paceSlowSecondsPerKm, it.maxHeartRate, it.note) },
    ),
    status = status.name,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)
