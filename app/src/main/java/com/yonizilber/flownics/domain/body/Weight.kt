package com.yonizilber.flownics.domain.body

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class WeightSource { SCALE, MANUAL }

/**
 * One weigh-in. [key] identifies a reading from the scale (its address and the minute it was
 * taken by the scale's clock), so reading the scale again never adds the same weigh-in twice.
 */
data class WeightEntry(
    val id: Long = 0,
    val atMillis: Long,
    val kg: Double,
    val source: WeightSource,
    val key: String? = null,
)

object Weights {
    /** Plausible body weights; a reading outside is a misread, not a person. */
    val RANGE = 10.0..300.0

    /** The last weigh-in of each day: what a chart of days shows. */
    fun daily(entries: List<WeightEntry>, zone: ZoneId): Map<LocalDate, Double> =
        entries.sortedBy { it.atMillis }
            .associate { Instant.ofEpochMilli(it.atMillis).atZone(zone).toLocalDate() to it.kg }

    /**
     * A short line for the coach: the latest weigh-in and how it compares with about a month
     * before; null with no weigh-ins.
     */
    fun summary(entries: List<WeightEntry>, nowMillis: Long, zone: ZoneId): String? {
        val sorted = entries.sortedBy { it.atMillis }
        val last = sorted.lastOrNull() ?: return null
        fun day(e: WeightEntry) = Instant.ofEpochMilli(e.atMillis).atZone(zone).toLocalDate()
        fun kg(v: Double) = "%.1f kg".format(java.util.Locale.ROOT, v)
        val monthAgo = nowMillis - 30L * 24 * 60 * 60 * 1000
        val before = sorted.lastOrNull { it.atMillis <= monthAgo }
        val count90 = sorted.count { it.atMillis > nowMillis - 90L * 24 * 60 * 60 * 1000 }
        return "Body weight: ${kg(last.kg)} on ${day(last)} (${last.source.name.lowercase()})" +
            (before?.let { "; ${kg(it.kg)} on ${day(it)}" } ?: "; no weigh-in from a month or more before") +
            "; $count90 weigh-ins in the last 90 days."
    }
}
