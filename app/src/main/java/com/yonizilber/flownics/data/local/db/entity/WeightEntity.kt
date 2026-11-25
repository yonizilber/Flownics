package com.yonizilber.flownics.data.local.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A weigh-in (v20): from the scale or typed in. [externalKey] is the scale reading's identity
 * (unique), so the same weigh-in read twice is stored once; null when typed in.
 */
@Entity(tableName = "body_weights", indices = [Index(value = ["externalKey"], unique = true)])
data class WeightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMillis: Long,
    val kg: Double,
    val source: String,
    val externalKey: String? = null,
)
