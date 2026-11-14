package com.yonizilber.flownics.domain.wellness

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDate

/** A sleep stage as Garmin scores it. */
enum class SleepLevel { DEEP, LIGHT, REM, AWAKE }

/** One stretch of the night in one stage. */
@Serializable
data class SleepStage(val startMillis: Long, val endMillis: Long, val level: SleepLevel)

/**
 * One day of the watch's all-day data, as Garmin works it out (sleep is dated by the morning it
 * ends). Every field is null when Garmin didn't give it; nothing is filled in by guesswork.
 */
@Serializable
data class WellnessDay(
    @Serializable(with = IsoDate::class) val date: LocalDate,
    // Sleep (the night ending this morning).
    val sleepStartMillis: Long? = null,
    val sleepEndMillis: Long? = null,
    val sleepSeconds: Int? = null,
    val deepSeconds: Int? = null,
    val lightSeconds: Int? = null,
    val remSeconds: Int? = null,
    val awakeSeconds: Int? = null,
    val sleepScore: Int? = null,
    /** The night stage by stage, when Garmin sent the timeline. */
    val stages: List<SleepStage> = emptyList(),
    /**
     * The timeline adds up to Garmin's own totals for each stage (within a minute or two), which
     * also confirms how its stage codes were read. False: shown, but marked unconfirmed.
     */
    val stagesConfirmed: Boolean = false,
    val sleepAvgStress: Double? = null,
    val sleepAvgRespiration: Double? = null,
    // HRV (overnight).
    val hrvLastNight: Int? = null,
    val hrvWeeklyAvg: Int? = null,
    val hrvStatus: String? = null,
    val hrvBaselineLow: Int? = null,
    val hrvBaselineHigh: Int? = null,
    // Heart rate over the day.
    val restingHr: Int? = null,
    val minHr: Int? = null,
    val maxHr: Int? = null,
    // Body Battery.
    val bodyBatteryHigh: Int? = null,
    val bodyBatteryLow: Int? = null,
    val bodyBatteryCharged: Int? = null,
    val bodyBatteryDrained: Int? = null,
    /** The level at the end of the night's sleep, from the day's readings. */
    val bodyBatteryAtWake: Int? = null,
    // The rest of the day.
    val stressAvg: Int? = null,
    val steps: Int? = null,
    val spo2Avg: Double? = null,
    val spo2Lowest: Int? = null,
    val vo2Max: Double? = null,
) {
    val hasSleep: Boolean get() = sleepSeconds != null || stages.isNotEmpty()
}

/** A date as ISO text (2026-11-14), for storing a [WellnessDay] as JSON. */
object IsoDate : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("IsoDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}
