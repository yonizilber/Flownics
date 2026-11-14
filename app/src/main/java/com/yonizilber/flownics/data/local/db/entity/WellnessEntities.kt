package com.yonizilber.flownics.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One answer from Garmin as it came (gzipped JSON): the source the days are built from, so they
 * can be rebuilt when the reading improves without asking Garmin again. A newer answer to the
 * same request replaces the older one.
 */
@Entity(tableName = "garmin_raw", primaryKeys = ["endpoint", "startDate"])
class GarminRawEntity(
    val endpoint: String,
    val startDate: String,
    val endDate: String,
    val fetchedAtMillis: Long,
    val gzip: ByteArray,
)

/** What was asked and when, without the answer itself. */
data class GarminRawIndexRow(val endpoint: String, val startDate: String, val endDate: String, val fetchedAtMillis: Long)

/** A day of all-day data, built from [GarminRawEntity] answers (WellnessDay as JSON). */
@Entity(tableName = "wellness_days")
data class WellnessDayEntity(
    @PrimaryKey val date: String,
    val dataJson: String,
)
