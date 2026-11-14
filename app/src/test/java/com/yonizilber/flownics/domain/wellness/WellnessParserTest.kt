package com.yonizilber.flownics.domain.wellness

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Answers shaped as in the fixtures of an open-source client of the same API (heart rate, Body
 * Battery, day summaries) and, for sleep and HRV, as Garmin Connect's own pages are known to send
 * them. The shape of those two isn't documented: the reader looks for fields by name and says
 * what it couldn't find, which these tests check too.
 */
class WellnessParserTest {
    private val json = Json
    private val day = LocalDate.of(2026, 10, 20)
    private fun ms(h: Int, m: Int, date: LocalDate = day) = date.atTime(h, m).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val sleep = """
        {"dailySleepDTO": {"calendarDate": "2026-10-20", "sleepTimeSeconds": 25200,
          "sleepStartTimestampGMT": ${ms(22, 0, day.minusDays(1))}, "sleepEndTimestampGMT": ${ms(5, 30)},
          "deepSleepSeconds": 5400, "lightSleepSeconds": 14400, "remSleepSeconds": 5400, "awakeSleepSeconds": 1800,
          "avgSleepStress": 14.5, "averageRespirationValue": 13.2,
          "sleepScores": {"overall": {"value": 81, "qualifierKey": "GOOD"}, "remPercentage": {"value": 21}}},
         "sleepLevels": [
          {"startGMT": "2026-10-19T22:00:00.0", "endGMT": "2026-10-20T00:00:00.0", "activityLevel": 1.0},
          {"startGMT": "2026-10-20T00:00:00.0", "endGMT": "2026-10-20T01:30:00.0", "activityLevel": 0.0},
          {"startGMT": "2026-10-20T01:30:00.0", "endGMT": "2026-10-20T03:00:00.0", "activityLevel": 2.0},
          {"startGMT": "2026-10-20T03:00:00.0", "endGMT": "2026-10-20T03:30:00.0", "activityLevel": 3.0},
          {"startGMT": "2026-10-20T03:30:00.0", "endGMT": "2026-10-20T05:30:00.0", "activityLevel": 1.0}
         ],
         "avgOvernightHrv": 50.0, "restingHeartRate": 49}
    """.trimIndent()

    private val heart = """
        {"calendarDate": "2026-10-20", "maxHeartRate": 151, "minHeartRate": 47, "restingHeartRate": 52,
         "heartRateValueDescriptors": [{"key": "timestamp", "index": 0}, {"key": "heartrate", "index": 1}],
         "heartRateValues": [[1792483200000, 58], [1792483320000, 57]]}
    """.trimIndent()

    private val battery = """
        [{"date": "2026-10-20", "charged": 61, "drained": 58,
          "bodyBatteryValuesArray": [[${ms(5, 0)}, 70], [${ms(5, 33)}, 82], [${ms(12, 0)}, 55], [${ms(23, 0)}, 31]]},
         {"date": "2026-10-21", "charged": 40, "drained": 50, "bodyBatteryValuesArray": [[${ms(8, 0, day.plusDays(1))}, 60]]}]
    """.trimIndent()

    private val summaries = """
        {"data": [{"calendarDate": "2026-10-20", "source": "DEVICE", "movement": {"steps": 9120, "distanceMeters": 7034},
                   "heartRate": {"restingValue": 53, "minValue": 47, "maxValue": 151}},
                  {"calendarDate": "2026-10-25", "movement": {"steps": 1}}]}
    """.trimIndent()

    private val hrv = """
        {"hrvSummary": {"calendarDate": "2026-10-20", "weeklyAvg": 55, "lastNightAvg": 51, "status": "BALANCED",
                        "baseline": {"lowUpper": 45, "balancedLow": 48, "balancedUpper": 62}},
         "hrvReadings": [{"hrvValue": 47, "readingTimeGMT": "2026-10-19T22:05:00.0"}]}
    """.trimIndent()

    private fun answer(e: GarminEndpoint, body: String, start: LocalDate = day, end: LocalDate = day, at: Long = 1) =
        RawAnswer(e, start, end, at, json.parseToJsonElement(body))

    @Test
    fun aNightWithItsStagesAddsUpAndIsConfirmed() {
        val r = WellnessParser.read(GarminEndpoint.SLEEP, day, day, json.parseToJsonElement(sleep))
        val d = r.days.single()
        assertEquals(25200, d.sleepSeconds)
        assertEquals(81, d.sleepScore)
        assertEquals(5, d.stages.size)
        assertEquals(SleepLevel.DEEP, d.stages[1].level)
        assertTrue(d.stagesConfirmed)
        assertEquals(ms(5, 30), d.sleepEndMillis)
        assertEquals(14.5, d.sleepAvgStress!!, 0.001)
        assertTrue(r.missing.isEmpty())
    }

    @Test
    fun stagesThatDontAddUpAreShownButNotConfirmed() {
        // Say Garmin numbered the stages differently: deep and light swapped.
        val swapped = sleep.replace("\"activityLevel\": 1.0", "\"activityLevel\": 9").replace("\"activityLevel\": 0.0", "\"activityLevel\": 1.0").replace("\"activityLevel\": 9", "\"activityLevel\": 0.0")
        val d = WellnessParser.read(GarminEndpoint.SLEEP, day, day, json.parseToJsonElement(swapped)).days.single()
        assertEquals(5, d.stages.size)
        assertFalse(d.stagesConfirmed)
    }

    @Test
    fun whatIsntThereIsSaid() {
        val r = WellnessParser.read(GarminEndpoint.SLEEP, day, day, json.parseToJsonElement("""{"dailySleepDTO": {"sleepTimeSeconds": 100}}"""))
        assertEquals(100, r.days.single().sleepSeconds)
        assertTrue("deepSleepSeconds" in r.missing)
        assertTrue("sleepLevels" in r.missing)
        assertFalse("sleepTimeSeconds" in r.missing)
    }

    @Test
    fun aRangeAnswerGivesEachDayInRangeOnly() {
        val r = WellnessParser.read(GarminEndpoint.DAILY_SUMMARIES, day, day.plusDays(1), json.parseToJsonElement(summaries))
        assertEquals(listOf(day), r.days.map { it.date })
        assertEquals(9120, r.days.single().steps)
        assertEquals(53, r.days.single().restingHr)
    }

    @Test
    fun theDaysAreBuiltFromEveryAnswerTheDedicatedOneWinning() {
        val days = WellnessParser.build(
            listOf(
                answer(GarminEndpoint.BODY_BATTERY, battery, day, day.plusDays(1)),
                answer(GarminEndpoint.HRV, hrv),
                answer(GarminEndpoint.HEART_RATE, heart),
                answer(GarminEndpoint.SLEEP, sleep),
                answer(GarminEndpoint.DAILY_SUMMARIES, summaries, day, day.plusDays(5)),
            ),
        )
        assertEquals(listOf(day, day.plusDays(1), day.plusDays(5)), days.map { it.date })
        val d = days.first()
        // The heart-rate endpoint's resting value over the day summary's (52, not 53).
        assertEquals(52, d.restingHr)
        // HRV from its own endpoint over the night's average (51, not 50).
        assertEquals(51, d.hrvLastNight)
        assertEquals("BALANCED", d.hrvStatus)
        assertEquals(48, d.hrvBaselineLow)
        assertEquals(62, d.hrvBaselineHigh)
        assertEquals(82, d.bodyBatteryHigh)
        assertEquals(31, d.bodyBatteryLow)
        // The reading nearest the end of sleep (05:30): 82 at 05:33.
        assertEquals(82, d.bodyBatteryAtWake)
        assertEquals(9120, d.steps)
        assertNull(days[1].sleepSeconds)
    }

    @Test
    fun aLaterAnswerToTheSameRequestWins() {
        val days = WellnessParser.build(
            listOf(
                answer(GarminEndpoint.HEART_RATE, heart.replace("\"restingHeartRate\": 52", "\"restingHeartRate\": 60"), at = 2),
                answer(GarminEndpoint.HEART_RATE, heart, at = 1),
            ),
        )
        assertEquals(60, days.single().restingHr)
    }

    @Test
    fun theOutlineShowsAListOnceByItsLength() {
        val lines = JsonOutline.lines(json.parseToJsonElement(heart))
        val values = lines.single { it.path == "heartRateValues" }
        assertEquals("[2]", values.kind)
        assertTrue(lines.any { it.path == "heartRateValues[0][0]" && it.example == "1792483200000" })
        assertTrue(lines.none { it.path == "heartRateValues[1]" })
    }
}
