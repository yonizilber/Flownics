package com.yonizilber.flownics.data.local.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The frozen plan (compiled steps + block titles) is one JSON column: it is written once at start
// and only ever read whole. Like the template JSON, its field names are part of the export format.
@Entity(tableName = "workout_sessions", indices = [Index("status")])
data class WorkoutSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateId: Long?,
    val workoutName: String,
    val planJson: String,
    val status: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val currentIndex: Int,
    val notes: String,
)

/** A finished session's columns without the plan JSON, for history lists. */
data class SessionHeaderRow(
    val id: Long,
    val templateId: Long?,
    val workoutName: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val notes: String,
)

// exerciseId has no foreign key on purpose: history must survive deleting the exercise, which is
// why the name is stored alongside it.
@Entity(
    tableName = "set_logs",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId", "stepIndex"], unique = true), Index("exerciseId")],
)
data class SetLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val stepIndex: Int,
    val exerciseId: Long,
    val exerciseName: String,
    val blockIndex: Int,
    val setNumber: Int,
    val round: Int,
    val side: String?,
    val reps: Int?,
    val seconds: Int?,
    val weightKg: Double?,
    val distanceMeters: Int?,
    val skipped: Boolean,
    val completedAtMillis: Long,
    // Added in v11.
    val startedAtMillis: Long? = null,
)

@Serializable
data class SessionPlanJson(
    val blockTitles: List<String>,
    val steps: List<StepJson>,
)

@Serializable
sealed interface StepJson {
    @Serializable
    @SerialName("work")
    data class Work(
        val blockIndex: Int,
        val itemIndex: Int,
        val exerciseId: Long,
        val set: Int,
        val totalSets: Int,
        val round: Int,
        val totalRounds: Int,
        val reps: Int? = null,
        val seconds: Int? = null,
        val weightKg: Double? = null,
        val distanceMeters: Int? = null,
        val side: String? = null,
        val durationSeconds: Int? = null,
        val holdAtEnd: Boolean = false,
        val fromRests: Boolean = false,
        /** Its place in a round of rest exercises done together (0 for the first, or alone). */
        val restPart: Int = 0,
    ) : StepJson

    @Serializable
    @SerialName("rest")
    data class Rest(
        val blockIndex: Int,
        val durationSeconds: Int,
        val activeExerciseId: Long? = null,
        val holdAtEnd: Boolean = false,
        /** Missing in plans saved before it existed: those count every rest as one between sets. */
        val slot: Boolean = true,
        val activeReps: Int? = null,
        val activeSeconds: Int? = null,
        val activeWeightKg: Double? = null,
        val activeDistanceMeters: Int? = null,
        /** After the first: the other exercises of a round done together in this rest. */
        val moreActive: List<RestPartJson> = emptyList(),
    ) : StepJson

    @Serializable
    data class RestPartJson(
        val exerciseId: Long,
        val reps: Int? = null,
        val seconds: Int? = null,
        val weightKg: Double? = null,
        val distanceMeters: Int? = null,
    )
}
