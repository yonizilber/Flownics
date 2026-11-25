package com.yonizilber.flownics.domain.body

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class MiScaleProtocolTest {

    /** A reading as the scale sends it: status, weight (LE), year (LE), month, day, hour, minute, second. */
    private fun record(status: Int, raw: Int, t: LocalDateTime) = byteArrayOf(
        status.toByte(), raw.toByte(), (raw shr 8).toByte(),
        t.year.toByte(), (t.year shr 8).toByte(), t.monthValue.toByte(), t.dayOfMonth.toByte(), t.hour.toByte(), t.minute.toByte(), t.second.toByte(),
    )

    private val t = LocalDateTime.of(2026, 11, 25, 7, 31, 12)

    @Test
    fun aReadingInKilogramsPoundsOrCatties() {
        // 72.35 kg is sent in 5 g steps: 14470.
        val kg = MiScaleProtocol.reading(record(0x22, 14_470, t))!!
        assertEquals(72.35, kg.kg, 1e-9)
        assertEquals(t, kg.time)
        assertTrue(kg.stable)
        assertFalse(kg.removed)
        assertTrue(kg.final)
        // 159.5 lb, and 144.7 catties (jin, half a kilogram each).
        assertEquals(159.5 * 0.45359237, MiScaleProtocol.reading(record(0x23, 15_950, t))!!.kg, 1e-9)
        assertEquals(72.35, MiScaleProtocol.reading(record(0x32, 14_470, t))!!.kg, 1e-9)
    }

    @Test
    fun stillMovingOrSteppedOffIsNotAFinishedWeighing() {
        assertFalse(MiScaleProtocol.reading(record(0x02, 14_470, t))!!.final)
        val off = MiScaleProtocol.reading(record(0xA2, 14_470, t))!!
        assertTrue(off.removed)
        assertFalse(off.final)
        // Nothing on the scale reads as 0 kg: not a person.
        assertFalse(MiScaleProtocol.reading(record(0x22, 0, t))!!.final)
    }

    @Test
    fun anImpossibleDateIsNoDateAndTheWrongLengthIsNoReading() {
        val bad = record(0x22, 14_470, t).also { it[5] = 13 }
        assertNull(MiScaleProtocol.reading(bad)!!.time)
        assertNull(MiScaleProtocol.reading(ByteArray(9)))
        assertNull(MiScaleProtocol.reading(ByteArray(13)))
    }

    @Test
    fun commandsCarryTheAppsOwnIdOnTheScale() {
        assertArrayEquals(byteArrayOf(0x01, 0xFF.toByte(), 0xFF.toByte(), 0x12, 0x34), MiScaleProtocol.requestNew(0x1234))
        assertArrayEquals(byteArrayOf(0x04, 0xFF.toByte(), 0xFF.toByte(), 0x12, 0x34), MiScaleProtocol.confirm(0x1234))
        assertArrayEquals(byteArrayOf(0xEA.toByte(), 0x07, 11, 25, 7, 31, 12, 0, 0, 1), MiScaleProtocol.currentTime(t))
    }

    @Test
    fun theHistoryArrivesCountedSplitAndEnded() {
        val h = MiScaleProtocol.History()
        // The count, 3 (two bytes), with the request echoed.
        h.feed(byteArrayOf(0x01, 0x03, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x12, 0x34))
        assertEquals(3, h.announced)
        val a = record(0x22, 14_470, t.minusDays(2))
        val b = record(0x22, 14_400, t.minusDays(1))
        val c = record(0x22, 14_380, t)
        // Two in one notification, the third split over two.
        h.feed(a + b)
        h.feed(c.copyOfRange(0, 6))
        assertEquals(2, h.readings.size)
        h.feed(c.copyOfRange(6, 10))
        assertEquals(listOf(72.35, 72.0, 71.9), h.readings.map { it.kg })
        assertFalse(h.done)
        h.feed(byteArrayOf(0x03))
        assertTrue(h.done)
        assertTrue(h.notUnderstood.isEmpty())
    }

    @Test
    fun aPieceLeftWhenTheHistoryEndsIsShownNotDropped() {
        val h = MiScaleProtocol.History()
        h.feed(record(0x22, 14_470, t) + byteArrayOf(0x22, 0x10))
        h.feed(byteArrayOf(0x03))
        assertEquals(1, h.readings.size)
        assertEquals(1, h.notUnderstood.size)
        assertEquals(2, h.notUnderstood.single().size)
    }

    @Test
    fun theScaleIsKnownByItsName() {
        assertTrue(MiScaleProtocol.isScaleName("MI SCALE2"))
        assertTrue(MiScaleProtocol.isScaleName("MI_SCALE"))
        assertFalse(MiScaleProtocol.isScaleName("MIBFS"))
        assertFalse(MiScaleProtocol.isScaleName(null))
    }
}
