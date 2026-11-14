package com.yonizilber.flownics.domain.wellness

import java.time.LocalDate
import java.time.ZoneId

/** A request already answered: for [endpoint], the days [start] to [end], fetched at [fetchedAtMillis]. */
data class Fetched(val endpoint: GarminEndpoint, val start: LocalDate, val end: LocalDate, val fetchedAtMillis: Long)

/**
 * Which requests a sync makes. A day counts as fetched once an answer covering it came after the
 * day was over (two hours into the next, so the night and late uploads are in); today and any
 * day fetched while still under way are asked again. So a sync that stops halfway (no network,
 * Garmin limiting requests) goes on from where it was the next time. Pure, so it is tested.
 */
object WellnessFetchPlan {

    fun requests(
        endpoints: List<GarminEndpoint>,
        done: List<Fetched>,
        from: LocalDate,
        today: LocalDate,
        zone: ZoneId,
    ): List<Pair<GarminEndpoint, ClosedRange<LocalDate>>> = endpoints.flatMap { e ->
        val complete = done.filter { it.endpoint == e }
        val needed = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.filter { d ->
            val over = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + 2 * 3_600_000
            complete.none { f -> !d.isBefore(f.start) && !d.isAfter(f.end) && f.fetchedAtMillis >= over }
        }.toList()
        runs(needed, e.maxDays.coerceAtLeast(1)).map { e to it }
    }

    /** Days in a row, cut into pieces of at most [max] days. */
    private fun runs(days: List<LocalDate>, max: Int): List<ClosedRange<LocalDate>> {
        val out = mutableListOf<ClosedRange<LocalDate>>()
        var start: LocalDate? = null
        var last: LocalDate? = null
        fun close() {
            val s = start ?: return
            out += s..checkNotNull(last)
        }
        days.forEach { d ->
            val s = start
            if (s != null && last!!.plusDays(1) == d && java.time.temporal.ChronoUnit.DAYS.between(s, d) < max) {
                last = d
            } else {
                close()
                start = d
                last = d
            }
        }
        close()
        return out
    }
}
