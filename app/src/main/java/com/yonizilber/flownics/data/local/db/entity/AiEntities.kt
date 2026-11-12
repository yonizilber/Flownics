package com.yonizilber.flownics.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One AI call, how it ended and what it is estimated to have cost. Kept locally so the user can see
 * their spend and the monthly cap can be enforced. Only a short label of what it was about is kept,
 * never the question or the answer.
 */
@Entity(tableName = "ai_usage", indices = [Index("atMillis")])
data class AiUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMillis: Long,
    val feature: String,
    val modelId: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val webSearches: Long,
    val costUsd: Double,
    /** What it was about, e.g. the exercise's name (v5). */
    @ColumnInfo(defaultValue = "") val subject: String,
    /** How long the user waited (v5). */
    @ColumnInfo(defaultValue = "0") val durationMs: Long,
    /** [com.yonizilber.flownics.domain.ai.AiOutcome] name (v5). */
    @ColumnInfo(defaultValue = "OK") val outcome: String,
    /** Why a call didn't answer, e.g. "HTTP 503: …" or "Stalled" (v6). Never holds a key. */
    @ColumnInfo(defaultValue = "") val detail: String = "",
)

/**
 * Where AI-suggested details of an exercise came from: saved only when the user applies a suggestion
 * and saves the exercise, and shown on the exercise page so the information is never anonymous.
 */
@Entity(
    tableName = "exercise_ai_notes",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("exerciseId")],
)
data class ExerciseAiNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: Long,
    val atMillis: Long,
    val modelId: String,
    val summary: String,
    /** Which fields the user took from the suggestion, e.g. ["muscles","cues"]. */
    val appliedJson: String,
    /** [{"title":…,"url":…}], only pages the search really returned. */
    val sourcesJson: String,
)

data class FeatureSpendRow(val feature: String, val costUsd: Double, val calls: Int)
