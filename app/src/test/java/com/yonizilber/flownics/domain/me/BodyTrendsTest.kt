package com.yonizilber.flownics.domain.me

import com.yonizilber.flownics.domain.wellness.WellnessDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class BodyTrendsTest {
    private val to = LocalDate.of(2026, 11, 15)

    @Test
    fun missingDaysStayMissingAndAveragesUseOnlyRealValues() {
        val days = listOf(
            WellnessDay(to.minusDays(30), restingHr = 60),
            WellnessDay(to.minusDays(10), restingHr = 50),
            WellnessDay(to.minusDays(2), restingHr = 48),
            WellnessDay(to, restingHr = 52, hrvLastNight = 70, hrvBaselineLow = 60, hrvBaselineHigh = 80),
        )
        val trends = BodyTrends.of(days, emptyMap(), to.minusDays(40), to).associateBy { it.metric }
        val rhr = trends.getValue(Metric.RESTING_HR)
        assertEquals(4, rhr.points.size)
        assertEquals(50.0, rhr.mean7!!, 1e-9)
        assertEquals(50.0, rhr.mean28!!, 1e-9)
        assertEquals(to to 52.0, rhr.latest)
        val hrv = trends.getValue(Metric.HRV)
        assertEquals(60.0, hrv.usualLow)
        // No steps that day: none made up.
        assertNull(trends.getValue(Metric.STEPS).latest)
    }

    @Test
    fun loadCountsRestDaysAsZero() {
        val load = BodyTrends.of(emptyList(), mapOf(to to 70), to.minusDays(6), to).single { it.metric == Metric.LOAD }
        assertEquals(7, load.points.size)
        assertEquals(10.0, load.mean7!!, 1e-9)
    }
}
