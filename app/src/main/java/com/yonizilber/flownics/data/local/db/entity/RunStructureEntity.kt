package com.yonizilber.flownics.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A run's structure (RunStructure as JSON), made from its kept FIT file by the algorithm at
 * [version]. [externalId] ties it to that recording: after a restore gives ids to other activities,
 * a row that no longer matches is made again rather than shown for the wrong run.
 */
@Entity(tableName = "run_structures")
data class RunStructureEntity(
    @PrimaryKey val activityId: Long,
    val externalId: String,
    val version: Int,
    val json: String,
)
