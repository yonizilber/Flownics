package com.yonizilber.flownics.ui.component

import com.yonizilber.flownics.domain.history.ProgressPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressChartTest {

    private fun assertTicks(expected: List<Double>, actual: List<Double>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, 1e-9) }
    }

    @Test
    fun ticksAreRoundAndCoverTheData() {
        assertTicks(listOf(40.0, 50.0, 60.0, 70.0), niceTicks(40.0, 62.5))
        assertTicks(listOf(0.0, 5.0, 10.0, 15.0), niceTicks(1.0, 12.0))
    }

    @Test
    fun aFlatSeriesGetsRoomAndNeverGoesNegative() {
        val flat = niceTicks(60.0, 60.0)
        assertTrue(flat.first() < 60.0 && flat.last() > 60.0)
        assertTicks(listOf(0.0, 1.0), niceTicks(0.0, 0.0))
    }

    @Test
    fun pointsArePlacedByDate() {
        val points = listOf(ProgressPoint(1, 0, 1.0), ProgressPoint(2, 30, 1.0), ProgressPoint(3, 100, 1.0))
        assertEquals(listOf(0f, 0.3f, 1f), xFractions(points))
        assertEquals(listOf(0.5f), xFractions(listOf(ProgressPoint(1, 5, 1.0))))
    }
}
