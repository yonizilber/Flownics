package com.yonizilber.flownics.data.local.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A variation suggested for an exercise, kept under it (not as an exercise of its own) until the
 * user adds it to the library. Goes when the exercise goes.
 */
@Entity(
    tableName = "exercise_variations",
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
data class ExerciseVariationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: Long,
    val name: String,
    val kind: String,
    val difference: String,
    val equipment: String,
    /** ExerciseCategory name, or "" when the suggestion had none the app knows. */
    val category: String,
    val why: String,
    val level: Int?,
    val modelId: String,
    val atMillis: Long,
    /** The library exercise made from it, once the user added it. Not a foreign key. */
    val addedExerciseId: Long?,
)
