package com.yonizilber.flownics.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateParseTest {
    @Test
    fun eightAndSixteenBitReadings() {
        assertEquals(72, HeartRateMonitor.parse(byteArrayOf(0x00, 72)))
        // Flag bit 0 set: little-endian 16-bit value (here 0x00B4 = 180), extra fields ignored.
        assertEquals(180, HeartRateMonitor.parse(byteArrayOf(0x01, 0xB4.toByte(), 0x00, 0x10)))
        assertNull(HeartRateMonitor.parse(byteArrayOf(0x00)))
        assertNull(HeartRateMonitor.parse(byteArrayOf(0x00, 0)))
    }
}
