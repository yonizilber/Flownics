package com.yonizilber.flownics.domain.fit

import com.yonizilber.flownics.domain.model.Activity
import com.yonizilber.flownics.domain.model.ActivityMetrics
import com.yonizilber.flownics.domain.model.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HeartZonesTest {

    @Test
    fun zonesByShareOfMaxOrByReserve() {
        assertEquals(listOf(95, 114, 133, 152, 171, 190), HeartZones(190).bounds)
        // Karvonen: resting 50, max 190 → reserve 140.
        assertEquals(listOf(120, 134, 148, 162, 176, 190), HeartZones(190, resting = 50).bounds)
        assertEquals(0, HeartZones(190).zoneOf(80))
        assertEquals(2, HeartZones(190).zoneOf(120))
        assertEquals(5, HeartZones(190).zoneOf(185))
    }

    @Test
    fun histogramCountsSecondsAndCapsGaps() {
        val points = listOf(HeartPoint(0, 120), HeartPoint(1_000, 121), HeartPoint(61_000, 150), HeartPoint(62_000, 150))
        val h = HeartZones.histogram(points)
        // 1 s at 120 (bucket 120–124), then a 60 s gap counted as 10 s at 121, then 1 s at 150.
        assertEquals(11, h[(120 - HeartZones.HISTOGRAM_FROM) / HeartZones.HISTOGRAM_STEP])
        assertEquals(1, h[(150 - HeartZones.HISTOGRAM_FROM) / HeartZones.HISTOGRAM_STEP])
        assertEquals(12, h.sum())
    }

    @Test
    fun trimpWeighsTimeByZone() {
        val zones = HeartZones(200)
        // 60 min at 125 (zone 2) and 20 min at 185 (zone 5): 60×2 + 20×5 = 220.
        val h = MutableList(HeartZones.HISTOGRAM_BUCKETS) { 0 }
        h[(125 - 40) / 5] = 3600
        h[(185 - 40) / 5] = 1200
        assertEquals(220, zones.trimp(h))
    }

    @Test
    fun loadComparesTheLastWeekWithTheMonth() {
        val zone = ZoneId.of("UTC")
        val today = LocalDate.parse("2026-11-28")
        val h = MutableList(HeartZones.HISTOGRAM_BUCKETS) { 0 }.also { it[(125 - 40) / 5] = 3000 } // 50 min in zone 2 → 100
        fun run(daysAgo: Long) = Activity(
            type = ActivityType.RUN, title = "", durationSeconds = 3000,
            startedAtMillis = today.minusDays(daysAgo).atTime(18, 0).atZone(zone).toInstant().toEpochMilli(),
            metrics = ActivityMetrics(hrHistogram = h),
        )
        val noHr = Activity(type = ActivityType.RUN, title = "", durationSeconds = 1800, startedAtMillis = today.atTime(7, 0).atZone(zone).toInstant().toEpochMilli())
        val s = TrainingLoad.summary(listOf(run(0), run(2), run(10), run(20), noHr), HeartZones(200), today, zone)
        assertEquals(200, s.last7)
        assertEquals(100, s.weeklyAverage28)
        assertEquals(2.0, s.ratio!!, 1e-9)
        assertEquals(4, s.counted)
        assertEquals(1, s.withoutHeartRate)
        assertEquals(28, s.daily.size)
        assertTrue(s.daily.last().first == today)
        // Only this week: no earlier weeks to compare with.
        assertNull(TrainingLoad.summary(listOf(run(1)), HeartZones(200), today, zone).ratio)
    }
}
