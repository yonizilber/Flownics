package com.yonizilber.flownics.domain.wellness

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class WellnessFetchPlanTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 14)
    private fun endOf(d: LocalDate, hoursAfter: Int) = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + hoursAfter * 3_600_000L

    @Test
    fun theFirstTimeEveryDayIsAskedRangesInPieces() {
        val from = today.minusDays(39)
        val r = WellnessFetchPlan.requests(listOf(GarminEndpoint.SLEEP, GarminEndpoint.BODY_BATTERY), emptyList(), from, today, zone)
        assertEquals(40, r.count { it.first == GarminEndpoint.SLEEP })
        // 40 days of Body Battery in requests of at most 31.
        assertEquals(listOf(from..from.plusDays(30), from.plusDays(31)..today), r.filter { it.first == GarminEndpoint.BODY_BATTERY }.map { it.second })
    }

    @Test
    fun aDayFetchedAfterItWasOverIsntAskedAgainOneFetchedDuringItIs() {
        val from = today.minusDays(2)
        val done = listOf(
            Fetched(GarminEndpoint.SLEEP, from, from, endOf(from, 3)),
            // Fetched an hour into the next day: the night's late upload may not be in yet.
            Fetched(GarminEndpoint.SLEEP, from.plusDays(1), from.plusDays(1), endOf(from.plusDays(1), 1)),
        )
        val r = WellnessFetchPlan.requests(listOf(GarminEndpoint.SLEEP), done, from, today, zone)
        assertEquals(listOf(from.plusDays(1)..from.plusDays(1), today..today), r.map { it.second })
    }

    @Test
    fun aRangeOnlyAsksForTheDaysStillMissing() {
        val from = today.minusDays(9)
        val done = listOf(Fetched(GarminEndpoint.STRESS, from, today.minusDays(3), endOf(today.minusDays(2), 5)))
        val r = WellnessFetchPlan.requests(listOf(GarminEndpoint.STRESS), done, from, today, zone)
        assertEquals(listOf(today.minusDays(2)..today), r.map { it.second })
    }
}
