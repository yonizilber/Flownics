package com.yonizilber.flownics.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The block/item tree is one JSON column: a template is always loaded and saved whole. The same
// JSON shape is reused by the export file format, so field names here are a compatibility contract.
@Entity(tableName = "workout_templates")
data class WorkoutTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val blocksJson: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Serializable
data class BlockJson(
    val title: String = "",
    val structure: String,
    val rounds: Int = 1,
    val roundRestSeconds: Int = 0,
    val roundActiveRest: ActiveRestJson = ActiveRestJson.None,
    val items: List<ItemJson> = emptyList(),
)

@Serializable
data class ItemJson(
    val exerciseId: Long,
    val sets: Int = 1,
    val reps: Int? = null,
    val seconds: Int? = null,
    val weightKg: Double? = null,
    val distanceMeters: Int? = null,
    val restSeconds: Int = 0,
    val activeRest: ActiveRestJson = ActiveRestJson.None,
)

@Serializable
sealed interface ActiveRestJson {
    @Serializable
    @SerialName("none")
    data object None : ActiveRestJson

    @Serializable
    @SerialName("specific")
    data class Specific(val exerciseId: Long) : ActiveRestJson

    @Serializable
    @SerialName("pool")
    data class Pool(val categories: List<String>, val focus: String) : ActiveRestJson
}
