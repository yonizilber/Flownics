package com.yonizilber.flownics.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// Cues, stages and media are stored as JSON columns: an exercise is always loaded and saved as one
// unit, and nothing queries inside those lists. Muscles are stored as ",A,B," so a LIKE '%,A,%'
// query stays possible later without false matches.
@Entity(tableName = "exercises")
data class ExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: String,
    // Added in v8, stored like the muscles (",A,B,").
    @ColumnInfo(defaultValue = "") val extraCategories: String = "",
    val primaryMuscles: String,
    val secondaryMuscles: String,
    val equipment: String,
    val cuesJson: String,
    // Added in v5; the default lets the migration fill existing rows.
    @ColumnInfo(defaultValue = "[]") val instructionsJson: String,
    val stagesJson: String,
    val mediaJson: String,
    val measureMode: String,
    val unilateral: Boolean,
    // Added in v5.
    val level: Int?,
    val variationOf: Long?,
    val notes: String,
    // Added in v18: free tags with a note each ([{"name","note"}]), general steps, quick-added ones.
    @ColumnInfo(defaultValue = "[]") val tagsJson: String = "[]",
    @ColumnInfo(defaultValue = "0") val general: Boolean = false,
    @ColumnInfo(defaultValue = "0") val quickAdded: Boolean = false,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Serializable
data class TagJson(val name: String, val note: String = "")

/** What a tag is for. Keyed by the tag's lower-case name, so "Side split" and "side split" are one. */
@Entity(tableName = "tag_infos")
data class TagInfoEntity(
    @PrimaryKey val key: String,
    val name: String,
    val description: String,
)

@Serializable
data class StageJson(
    val name: String,
    val description: String = "",
    val durationSeconds: Int? = null,
)

@Serializable
data class MediaJson(
    val kind: String,
    val location: String,
)
