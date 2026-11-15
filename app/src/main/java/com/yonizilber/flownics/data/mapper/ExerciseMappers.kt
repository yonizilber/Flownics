package com.yonizilber.flownics.data.mapper

import com.yonizilber.flownics.data.local.db.entity.ExerciseEntity
import com.yonizilber.flownics.data.local.db.entity.MediaJson
import com.yonizilber.flownics.data.local.db.entity.StageJson
import com.yonizilber.flownics.data.local.db.entity.TagJson
import com.yonizilber.flownics.domain.model.ExerciseTag
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.ExerciseLevel
import com.yonizilber.flownics.domain.model.ExerciseMedia
import com.yonizilber.flownics.domain.model.ExerciseStage
import com.yonizilber.flownics.domain.model.MeasureMode
import com.yonizilber.flownics.domain.model.MediaKind
import com.yonizilber.flownics.domain.model.Muscle
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

// Unknown enum names (e.g. written by a newer app version) are an error, not something to guess
// around: valueOf throws, so a mismatch surfaces instead of silently dropping data.
fun ExerciseEntity.toDomain(json: Json): Exercise = Exercise(
    id = id,
    name = name,
    category = ExerciseCategory.valueOf(category),
    extraCategories = decodeCategories(extraCategories),
    primaryMuscles = decodeMuscles(primaryMuscles),
    secondaryMuscles = decodeMuscles(secondaryMuscles),
    equipment = equipment,
    instructions = json.decodeFromString(ListSerializer(String.serializer()), instructionsJson),
    cues = json.decodeFromString(ListSerializer(String.serializer()), cuesJson),
    stages = json.decodeFromString(ListSerializer(StageJson.serializer()), stagesJson)
        .map { ExerciseStage(it.name, it.description, it.durationSeconds) },
    media = decodeMedia(json, mediaJson),
    measureMode = MeasureMode.valueOf(measureMode),
    unilateral = unilateral,
    level = level,
    variationOf = variationOf,
    notes = notes,
    tags = json.decodeFromString(ListSerializer(TagJson.serializer()), tagsJson).map { ExerciseTag(it.name, it.note) },
    general = general,
    quickAdded = quickAdded,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun Exercise.toEntity(json: Json): ExerciseEntity = ExerciseEntity(
    id = id,
    name = name,
    category = category.name,
    extraCategories = encodeCategories(extraCategories - category),
    primaryMuscles = encodeMuscles(primaryMuscles),
    secondaryMuscles = encodeMuscles(secondaryMuscles),
    equipment = equipment,
    cuesJson = json.encodeToString(ListSerializer(String.serializer()), cues),
    instructionsJson = json.encodeToString(ListSerializer(String.serializer()), instructions),
    stagesJson = json.encodeToString(
        ListSerializer(StageJson.serializer()),
        stages.map { StageJson(it.name, it.description, it.durationSeconds) },
    ),
    mediaJson = json.encodeToString(
        ListSerializer(MediaJson.serializer()),
        media.map { MediaJson(it.kind.name, it.location) },
    ),
    measureMode = measureMode.name,
    unilateral = unilateral,
    level = level?.coerceIn(ExerciseLevel.RANGE),
    variationOf = variationOf,
    notes = notes,
    tagsJson = json.encodeToString(
        ListSerializer(TagJson.serializer()),
        tags.filter { it.name.isNotBlank() }.distinctBy { it.name.trim().lowercase() }.map { TagJson(it.name.trim(), it.note.trim()) },
    ),
    general = general,
    quickAdded = quickAdded,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun decodeMedia(json: Json, mediaJson: String): List<ExerciseMedia> =
    json.decodeFromString(ListSerializer(MediaJson.serializer()), mediaJson)
        .map { ExerciseMedia(MediaKind.valueOf(it.kind), it.location) }

internal fun encodeMuscles(muscles: Set<Muscle>): String =
    if (muscles.isEmpty()) "" else muscles.sortedBy { it.ordinal }.joinToString(",", prefix = ",", postfix = ",") { it.name }

internal fun decodeMuscles(encoded: String): Set<Muscle> =
    encoded.split(',').filter { it.isNotEmpty() }.map { Muscle.valueOf(it) }.toSet()

fun encodeCategories(categories: Set<ExerciseCategory>): String =
    if (categories.isEmpty()) "" else categories.sortedBy { it.ordinal }.joinToString(",", prefix = ",", postfix = ",") { it.name }

fun decodeCategories(value: String): Set<ExerciseCategory> =
    value.split(',').filter { it.isNotBlank() }.map { ExerciseCategory.valueOf(it) }.toSet()
