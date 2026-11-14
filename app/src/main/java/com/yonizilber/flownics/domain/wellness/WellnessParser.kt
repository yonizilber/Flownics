package com.yonizilber.flownics.domain.wellness

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/** One saved answer from Garmin: what was asked, when, and the JSON that came back. */
data class RawAnswer(val endpoint: GarminEndpoint, val start: LocalDate, val end: LocalDate, val fetchedAtMillis: Long, val root: JsonElement)

/**
 * Reads Garmin's all-day data into [WellnessDay]s. Garmin documents the endpoints but not the
 * shape of their answers, so each value is looked for by its field name wherever it is in the
 * answer, and what was and wasn't found is reported ([Reading.found], [Reading.missing]) rather
 * than guessed. Pure and deterministic: the same saved answers always give the same days, so
 * the days can be rebuilt from them whenever this reading improves.
 */
object WellnessParser {

    /** What one answer gave: its days, the fields found in it, and those looked for but not there. */
    data class Reading(
        val days: List<WellnessDay>,
        val found: List<String>,
        val missing: List<String>,
        /** Body Battery through the day, by date: (time, level). */
        val bodyBattery: Map<LocalDate, List<Pair<Long, Int>>> = emptyMap(),
    )

    /** Later sources fill in over earlier ones: the dedicated endpoint wins over a day summary. */
    private val ORDER = listOf(
        GarminEndpoint.DAILY_SUMMARIES, GarminEndpoint.SLEEP_SUMMARIES, GarminEndpoint.SLEEP, GarminEndpoint.STRESS,
        GarminEndpoint.HEART_RATE, GarminEndpoint.HRV_SUMMARIES, GarminEndpoint.HRV, GarminEndpoint.BODY_BATTERY,
        GarminEndpoint.SPO2, GarminEndpoint.VO2_MAX,
    )

    /** Field names each endpoint is read for, to say which weren't there. */
    private val EXPECTED: Map<GarminEndpoint, List<String>> = mapOf(
        GarminEndpoint.SLEEP to listOf("sleepTimeSeconds", "deepSleepSeconds", "lightSleepSeconds", "remSleepSeconds", "awakeSleepSeconds", "sleepStartTimestampGMT", "sleepEndTimestampGMT", "sleepScores.overall.value", "sleepLevels", "avgSleepStress", "averageRespirationValue"),
        GarminEndpoint.SLEEP_SUMMARIES to listOf("sleepTimeSeconds", "deepSleepSeconds", "lightSleepSeconds", "remSleepSeconds", "awakeSleepSeconds"),
        GarminEndpoint.HRV to listOf("lastNightAvg", "weeklyAvg", "status", "balancedLow", "balancedUpper"),
        GarminEndpoint.HRV_SUMMARIES to listOf("lastNightAvg", "weeklyAvg", "status"),
        GarminEndpoint.HEART_RATE to listOf("restingHeartRate", "minHeartRate", "maxHeartRate"),
        GarminEndpoint.BODY_BATTERY to listOf("charged", "drained", "bodyBatteryValuesArray"),
        GarminEndpoint.STRESS to listOf("overallStressLevel"),
        GarminEndpoint.DAILY_SUMMARIES to listOf("steps", "restingValue"),
        GarminEndpoint.SPO2 to listOf("averageSpO2", "lowestSpO2"),
        GarminEndpoint.VO2_MAX to listOf("vo2MaxPreciseValue"),
    )

    fun reads(endpoint: GarminEndpoint): Boolean = endpoint in EXPECTED

    /** Every saved answer into days, oldest first; answers to the same thing fetched later win. */
    fun build(answers: List<RawAnswer>): List<WellnessDay> {
        val days = sortedMapOf<LocalDate, WellnessDay>()
        val battery = mutableMapOf<LocalDate, List<Pair<Long, Int>>>()
        answers
            .filter { it.endpoint in ORDER }
            .sortedWith(compareBy({ ORDER.indexOf(it.endpoint) }, { it.fetchedAtMillis }))
            .forEach { a ->
                val r = read(a.endpoint, a.start, a.end, a.root)
                r.days.forEach { d -> days[d.date] = days[d.date]?.let { merge(it, d) } ?: d }
                battery.putAll(r.bodyBattery)
            }
        return days.values.map { d -> d.copy(bodyBatteryAtWake = d.sleepEndMillis?.let { atWake(battery[d.date], it) } ?: d.bodyBatteryAtWake) }
    }

    /** One answer: for a single-day endpoint, the day asked for; for a range, each day found in it. */
    fun read(endpoint: GarminEndpoint, start: LocalDate, end: LocalDate, root: JsonElement): Reading {
        val found = linkedSetOf<String>()
        val battery = mutableMapOf<LocalDate, List<Pair<Long, Int>>>()
        val scopes: List<Pair<LocalDate, JsonElement>> = if (endpoint.maxDays == 1) {
            listOf(start to root)
        } else {
            dayObjects(root).filter { (d, _) -> !d.isBefore(start) && !d.isAfter(end) }
        }
        val days = scopes.map { (date, scope) ->
            val f = Finder(scope, found)
            when (endpoint) {
                GarminEndpoint.SLEEP, GarminEndpoint.SLEEP_SUMMARIES -> sleep(date, f)
                GarminEndpoint.HRV, GarminEndpoint.HRV_SUMMARIES -> {
                    val summary = f.obj("hrvSummary")?.let { Finder(it, found) } ?: f
                    WellnessDay(
                        date,
                        hrvLastNight = summary.int("lastNightAvg"),
                        hrvWeeklyAvg = summary.int("weeklyAvg"),
                        hrvStatus = summary.str("status"),
                        hrvBaselineLow = summary.int("balancedLow"),
                        hrvBaselineHigh = summary.int("balancedUpper"),
                    )
                }
                GarminEndpoint.HEART_RATE -> WellnessDay(date, restingHr = f.int("restingHeartRate"), minHr = f.int("minHeartRate"), maxHr = f.int("maxHeartRate"))
                GarminEndpoint.BODY_BATTERY -> {
                    val values = f.arr("bodyBatteryValuesArray")?.let(::pairs).orEmpty()
                    if (values.isNotEmpty()) battery[date] = values
                    WellnessDay(
                        date,
                        bodyBatteryCharged = f.int("charged"),
                        bodyBatteryDrained = f.int("drained"),
                        bodyBatteryHigh = values.maxOfOrNull { it.second },
                        bodyBatteryLow = values.minOfOrNull { it.second },
                    )
                }
                GarminEndpoint.STRESS -> WellnessDay(date, stressAvg = f.int("overallStressLevel") ?: f.int("averageStressLevel"))
                GarminEndpoint.DAILY_SUMMARIES -> {
                    val heart = f.obj("heartRate")?.let { Finder(it, found) }
                    WellnessDay(
                        date,
                        steps = f.int("steps") ?: f.int("totalSteps"),
                        restingHr = heart?.int("restingValue") ?: f.int("restingHeartRate"),
                        minHr = heart?.int("minValue"),
                        maxHr = heart?.int("maxValue"),
                        stressAvg = f.int("averageStressLevel"),
                    )
                }
                GarminEndpoint.SPO2 -> WellnessDay(date, spo2Avg = f.num("averageSpO2"), spo2Lowest = f.int("lowestSpO2"))
                GarminEndpoint.VO2_MAX -> WellnessDay(date, vo2Max = f.num("vo2MaxPreciseValue") ?: f.num("vo2MaxValue"))
                else -> WellnessDay(date)
            }
        }
        val expected = EXPECTED[endpoint].orEmpty()
        return Reading(days, found.toList(), expected.filter { it !in found }, battery)
    }

    private fun sleep(date: LocalDate, f: Finder): WellnessDay {
        val deep = f.int("deepSleepSeconds")
        val light = f.int("lightSleepSeconds")
        val rem = f.int("remSleepSeconds")
        val awake = f.int("awakeSleepSeconds")
        val score = f.obj("sleepScores")?.let { Finder(it, mutableSetOf()) }?.obj("overall")?.let { Finder(it, mutableSetOf()) }?.int("value")
            ?.also { f.found += "sleepScores.overall.value" }
            ?: f.int("overallSleepScore")
        val stages = f.arr("sleepLevels")?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val s = time(o["startGMT"]) ?: return@mapNotNull null
            val t = time(o["endGMT"]) ?: return@mapNotNull null
            val level = when ((o["activityLevel"] as? JsonPrimitive)?.doubleOrNull) {
                0.0 -> SleepLevel.DEEP
                1.0 -> SleepLevel.LIGHT
                2.0 -> SleepLevel.REM
                3.0 -> SleepLevel.AWAKE
                else -> return@mapNotNull null
            }
            if (t > s) SleepStage(s, t, level) else null
        }.orEmpty().sortedBy { it.startMillis }
        return WellnessDay(
            date,
            sleepStartMillis = f.time("sleepStartTimestampGMT"),
            sleepEndMillis = f.time("sleepEndTimestampGMT"),
            sleepSeconds = f.int("sleepTimeSeconds"),
            deepSeconds = deep,
            lightSeconds = light,
            remSeconds = rem,
            awakeSeconds = awake,
            sleepScore = score,
            stages = stages,
            stagesConfirmed = stages.isNotEmpty() && confirms(stages, mapOf(SleepLevel.DEEP to deep, SleepLevel.LIGHT to light, SleepLevel.REM to rem, SleepLevel.AWAKE to awake)),
            sleepAvgStress = f.num("avgSleepStress"),
            sleepAvgRespiration = f.num("averageRespirationValue"),
            hrvLastNight = f.int("avgOvernightHrv"),
        )
    }

    /**
     * The timeline's time in each stage matches Garmin's own total for it, within two minutes or
     * 3 %: then the stage codes were read right. Light and deep must have totals to compare with.
     */
    fun confirms(stages: List<SleepStage>, totals: Map<SleepLevel, Int?>): Boolean {
        if (totals[SleepLevel.DEEP] == null || totals[SleepLevel.LIGHT] == null) return false
        return SleepLevel.entries.all { level ->
            val timeline = stages.filter { it.level == level }.sumOf { it.endMillis - it.startMillis } / 1000
            val total = totals[level] ?: 0
            kotlin.math.abs(timeline - total) <= maxOf(120.0, total * 0.03)
        }
    }

    /** Body Battery at waking: the reading nearest the end of sleep, within 20 minutes. */
    private fun atWake(values: List<Pair<Long, Int>>?, end: Long): Int? =
        values?.filter { kotlin.math.abs(it.first - end) <= 20 * 60_000 }?.minByOrNull { kotlin.math.abs(it.first - end) }?.second

    /** Later values fill in over earlier ones; a field the later one lacks keeps the earlier one. */
    fun merge(a: WellnessDay, b: WellnessDay): WellnessDay = WellnessDay(
        date = a.date,
        sleepStartMillis = b.sleepStartMillis ?: a.sleepStartMillis,
        sleepEndMillis = b.sleepEndMillis ?: a.sleepEndMillis,
        sleepSeconds = b.sleepSeconds ?: a.sleepSeconds,
        deepSeconds = b.deepSeconds ?: a.deepSeconds,
        lightSeconds = b.lightSeconds ?: a.lightSeconds,
        remSeconds = b.remSeconds ?: a.remSeconds,
        awakeSeconds = b.awakeSeconds ?: a.awakeSeconds,
        sleepScore = b.sleepScore ?: a.sleepScore,
        stages = b.stages.ifEmpty { a.stages },
        stagesConfirmed = if (b.stages.isNotEmpty()) b.stagesConfirmed else a.stagesConfirmed,
        sleepAvgStress = b.sleepAvgStress ?: a.sleepAvgStress,
        sleepAvgRespiration = b.sleepAvgRespiration ?: a.sleepAvgRespiration,
        hrvLastNight = b.hrvLastNight ?: a.hrvLastNight,
        hrvWeeklyAvg = b.hrvWeeklyAvg ?: a.hrvWeeklyAvg,
        hrvStatus = b.hrvStatus ?: a.hrvStatus,
        hrvBaselineLow = b.hrvBaselineLow ?: a.hrvBaselineLow,
        hrvBaselineHigh = b.hrvBaselineHigh ?: a.hrvBaselineHigh,
        restingHr = b.restingHr ?: a.restingHr,
        minHr = b.minHr ?: a.minHr,
        maxHr = b.maxHr ?: a.maxHr,
        bodyBatteryHigh = b.bodyBatteryHigh ?: a.bodyBatteryHigh,
        bodyBatteryLow = b.bodyBatteryLow ?: a.bodyBatteryLow,
        bodyBatteryCharged = b.bodyBatteryCharged ?: a.bodyBatteryCharged,
        bodyBatteryDrained = b.bodyBatteryDrained ?: a.bodyBatteryDrained,
        bodyBatteryAtWake = b.bodyBatteryAtWake ?: a.bodyBatteryAtWake,
        stressAvg = b.stressAvg ?: a.stressAvg,
        steps = b.steps ?: a.steps,
        spo2Avg = b.spo2Avg ?: a.spo2Avg,
        spo2Lowest = b.spo2Lowest ?: a.spo2Lowest,
        vo2Max = b.vo2Max ?: a.vo2Max,
    )

    /** Objects in a range answer that stand for one day: they carry a `calendarDate` (or `date`). */
    private fun dayObjects(root: JsonElement): List<Pair<LocalDate, JsonObject>> {
        val out = mutableListOf<Pair<LocalDate, JsonObject>>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> {
                    val d = (e["calendarDate"] ?: e["date"])?.let { (it as? JsonPrimitive)?.contentOrNull }?.let(::dateOrNull)
                    if (d != null) out += d to e else e.values.forEach(::walk)
                }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return out
    }

    private fun pairs(array: JsonArray): List<Pair<Long, Int>> = array.mapNotNull { e ->
        val p = e as? JsonArray ?: return@mapNotNull null
        val t = (p.getOrNull(0) as? JsonPrimitive)?.doubleOrNull?.toLong() ?: return@mapNotNull null
        val v = (p.getOrNull(1) as? JsonPrimitive)?.doubleOrNull?.toInt() ?: return@mapNotNull null
        t to v
    }.sortedBy { it.first }

    private fun dateOrNull(s: String): LocalDate? = try {
        LocalDate.parse(s.take(10))
    } catch (_: DateTimeParseException) {
        null
    }

    /** Garmin gives times as epoch milliseconds or as GMT text ("2026-10-20T22:10:00.0"). */
    internal fun time(e: JsonElement?): Long? {
        val p = e as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        p.doubleOrNull?.let { return it.toLong() }
        val s = p.contentOrNull ?: return null
        return try {
            LocalDateTime.parse(s.removeSuffix("Z")).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /**
     * Finds a field by name anywhere under [root] (nearest to the top first), noting the names it
     * finds in [found].
     */
    private class Finder(private val root: JsonElement, val found: MutableSet<String>) {
        fun element(key: String): JsonElement? {
            var level = listOf(root)
            while (level.isNotEmpty()) {
                level.forEach { e -> (e as? JsonObject)?.get(key)?.takeIf { it !is JsonNull }?.let { return it } }
                level = level.flatMap { e ->
                    when (e) {
                        is JsonObject -> e.values.filter { it is JsonObject || it is JsonArray }
                        is JsonArray -> e.filter { it is JsonObject || it is JsonArray }
                        else -> emptyList()
                    }
                }
            }
            return null
        }

        fun num(key: String): Double? = (element(key) as? JsonPrimitive)?.doubleOrNull?.also { found += key }
        fun int(key: String): Int? = num(key)?.let { kotlin.math.round(it).toInt() }
        fun str(key: String): String? = (element(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.also { found += key }
        fun obj(key: String): JsonObject? = element(key) as? JsonObject
        fun arr(key: String): JsonArray? = (element(key) as? JsonArray)?.also { found += key }
        fun time(key: String): Long? = time(element(key))?.also { found += key }
    }
}
