package com.yonizilber.flownics.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityFormatTest {

    @Test
    fun durationsAsPeopleTypeThem() {
        assertEquals(2700, ActivityFormat.parseDuration("45"))
        assertEquals(2730, ActivityFormat.parseDuration("45:30"))
        assertEquals(3930, ActivityFormat.parseDuration("1:05:30"))
        assertNull(ActivityFormat.parseDuration("45:75"))
        assertNull(ActivityFormat.parseDuration("4.5"))
        assertNull(ActivityFormat.parseDuration(""))
        assertEquals("45:30", ActivityFormat.formatDuration(2730))
        assertEquals("1:05:30", ActivityFormat.formatDuration(3930))
    }

    @Test
    fun distancesAndPace() {
        assertEquals(8200, ActivityFormat.parseKm("8.2"))
        assertEquals(8200, ActivityFormat.parseKm("8,2"))
        assertNull(ActivityFormat.parseKm("-1"))
        assertEquals("8.2", ActivityFormat.formatKm(8200))
        assertEquals("0.4", ActivityFormat.formatKm(400))
        assertEquals("10", ActivityFormat.formatKm(10_000))
        assertEquals("5:08", ActivityFormat.formatPace(308))
        assertEquals(308, Activity(type = ActivityType.RUN, title = "", startedAtMillis = 0, durationSeconds = 2526, distanceMeters = 8200).paceSecondsPerKm)
    }
}
