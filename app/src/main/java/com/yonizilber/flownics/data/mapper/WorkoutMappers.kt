package com.yonizilber.flownics.data.mapper

import com.yonizilber.flownics.domain.model.RestFiller
import com.yonizilber.flownics.data.local.db.entity.ActiveRestJson
import com.yonizilber.flownics.data.local.db.entity.BlockJson
import com.yonizilber.flownics.data.local.db.entity.ItemJson
import com.yonizilber.flownics.data.local.db.entity.WorkoutTemplateEntity
import com.yonizilber.flownics.domain.model.ActiveRest
import com.yonizilber.flownics.domain.model.BlockStructure
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.PoolFocus
import com.yonizilber.flownics.domain.model.SetTarget
import com.yonizilber.flownics.domain.model.WorkoutBlock
import com.yonizilber.flownics.domain.model.WorkoutItem
import com.yonizilber.flownics.domain.model.WorkoutTemplate
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val blocksSerializer = ListSerializer(BlockJson.serializer())

fun WorkoutTemplateEntity.toDomain(json: Json): WorkoutTemplate = WorkoutTemplate(
    id = id,
    name = name,
    description = description,
    blocks = json.decodeFromString(blocksSerializer, blocksJson).map { it.toDomain() },
    restFiller = restFillerWorkoutId?.let { RestFiller(it, restFillerLeftovers, restFillerRepeatSets) },
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun WorkoutTemplate.toEntity(json: Json): WorkoutTemplateEntity = WorkoutTemplateEntity(
    id = id,
    name = name,
    description = description,
    blocksJson = json.encodeToString(blocksSerializer, blocks.map { it.toJson() }),
    restFillerWorkoutId = restFiller?.workoutId,
    restFillerLeftovers = restFiller?.leftoversAtEnd ?: true,
    restFillerRepeatSets = restFiller?.repeatSets ?: false,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun BlockJson.toDomain() = WorkoutBlock(
    title = title,
    structure = BlockStructure.valueOf(structure),
    rounds = rounds,
    roundRestSeconds = roundRestSeconds,
    roundActiveRest = roundActiveRest.toDomain(),
    items = items.map { it.toDomain() },
)

fun WorkoutBlock.toJson() = BlockJson(
    title = title,
    structure = structure.name,
    rounds = rounds,
    roundRestSeconds = roundRestSeconds,
    roundActiveRest = roundActiveRest.toJson(),
    items = items.map { it.toJson() },
)

private fun ItemJson.toDomain() = WorkoutItem(
    exerciseId = exerciseId,
    sets = sets,
    target = SetTarget(reps, seconds, weightKg, distanceMeters),
    restSeconds = restSeconds,
    activeRest = activeRest.toDomain(),
)

private fun WorkoutItem.toJson() = ItemJson(
    exerciseId = exerciseId,
    sets = sets,
    reps = target.reps,
    seconds = target.seconds,
    weightKg = target.weightKg,
    distanceMeters = target.distanceMeters,
    restSeconds = restSeconds,
    activeRest = activeRest.toJson(),
)

private fun ActiveRestJson.toDomain(): ActiveRest = when (this) {
    ActiveRestJson.None -> ActiveRest.None
    is ActiveRestJson.Specific -> ActiveRest.Specific(exerciseId)
    is ActiveRestJson.Pool -> ActiveRest.Pool(
        categories = categories.map { ExerciseCategory.valueOf(it) }.toSet(),
        focus = PoolFocus.valueOf(focus),
    )
}

private fun ActiveRest.toJson(): ActiveRestJson = when (this) {
    ActiveRest.None -> ActiveRestJson.None
    is ActiveRest.Specific -> ActiveRestJson.Specific(exerciseId)
    is ActiveRest.Pool -> ActiveRestJson.Pool(categories.sortedBy { it.ordinal }.map { it.name }, focus.name)
}
