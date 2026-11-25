package com.yonizilber.flownics.domain.body

import com.yonizilber.flownics.domain.me.BodyTrends
import com.yonizilber.flownics.domain.me.Metric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class WeightsTest {
    private val zone = ZoneOffset.UTC
    private fun at(d: Int, h: Int) = LocalDateTime.of(2026, 11, d, h, 0).toInstant(zone).toEpochMilli()

    @Test
    fun aDayShowsItsLastWeighIn() {
        val entries = listOf(
            WeightEntry(atMillis = at(24, 21), kg = 73.0, source = WeightSource.MANUAL),
            WeightEntry(atMillis = at(24, 7), kg = 72.4, source = WeightSource.SCALE),
            WeightEntry(atMillis = at(25, 7), kg = 72.1, source = WeightSource.SCALE),
        )
        assertEquals(mapOf(LocalDate.of(2026, 11, 24) to 73.0, LocalDate.of(2026, 11, 25) to 72.1), Weights.daily(entries, zone))
        // On the Me screen: one point a day with a weigh-in, none filled in.
        val trend = BodyTrends.of(emptyList(), emptyMap(), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 25), Weights.daily(entries, zone))
            .single { it.metric == Metric.WEIGHT }
        assertEquals(2, trend.points.size)
        assertEquals(72.1, trend.latest!!.second, 1e-9)
    }

    @Test
    fun theCoachGetsTheLatestAndAMonthBefore() {
        val entries = listOf(
            WeightEntry(atMillis = LocalDateTime.of(2026, 10, 20, 7, 0).toInstant(zone).toEpochMilli(), kg = 74.0, source = WeightSource.SCALE),
            WeightEntry(atMillis = at(25, 7), kg = 72.1, source = WeightSource.SCALE),
        )
        assertEquals(
            "Body weight: 72.1 kg on 2026-11-25 (scale); 74.0 kg on 2026-10-20; 2 weigh-ins in the last 90 days.",
            Weights.summary(entries, at(25, 12), zone),
        )
        assertNull(Weights.summary(emptyList(), at(25, 12), zone))
    }
}
