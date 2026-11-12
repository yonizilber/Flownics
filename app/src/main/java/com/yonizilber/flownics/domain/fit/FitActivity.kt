package com.yonizilber.flownics.domain.fit

import com.yonizilber.flownics.domain.model.Activity
import com.yonizilber.flownics.domain.model.ActivityMetrics
import com.yonizilber.flownics.domain.model.ActivitySegment
import com.yonizilber.flownics.domain.model.ActivitySource
import com.yonizilber.flownics.domain.model.ActivityType
import com.yonizilber.flownics.domain.model.KmSplit
import com.yonizilber.flownics.domain.model.SegmentKind
import kotlin.math.roundToInt

/** An activity read from a FIT file, with [externalId] identifying the recording itself (same file, same id). */
data class FitResult(val activity: Activity, val externalId: String, val samples: Int)

/**
 * Turns a FIT recording into an activity. The watch's laps become the parts (each with its own
 * pace, heart rate, cadence and step length), so intervals keep their shape; a steady run with
 * auto-laps gets its splits. Everything else is computed from the second-by-second samples:
 * kilometre splits, cadence, step length and how much the heart rate drifted against the pace.
 * Pure, so it is unit-tested.
 */
object FitActivity {

    // Message numbers and field numbers from the FIT profile.
    private const val FILE_ID = 0
    private const val SESSION = 18
    private const val LAP = 19
    private const val RECORD = 20

    fun read(bytes: ByteArray): FitResult = fromMessages(FitDecoder.decode(bytes))

    /** Every heart-rate reading in the recording, on the wall clock. */
    fun heart(bytes: ByteArray): List<HeartPoint> = FitDecoder.decode(bytes)
        .filter { it.global == RECORD && it.timestamp != null }
        .mapNotNull { m -> m.fields[3]?.toInt()?.let { HeartPoint(unix(m.timestamp!!) * 1000, it) } }

    fun fromMessages(messages: List<FitMessage>): FitResult {
        val fileId = messages.firstOrNull { it.global == FILE_ID }
        val session = messages.firstOrNull { it.global == SESSION }
        val laps = messages.filter { it.global == LAP }
        val records = messages.filter { it.global == RECORD && it.timestamp != null }.map(::sample)
        if (session == null && records.isEmpty()) throw FitException("no activity in the file (no session and no samples)")

        val sport = session?.fields?.get(5)
        val type = when (sport) {
            1L -> ActivityType.RUN
            2L -> ActivityType.RIDE
            5L -> ActivityType.SWIM
            11L -> ActivityType.WALK
            17L -> ActivityType.HIKE
            else -> ActivityType.OTHER
        }
        val steps = type == ActivityType.RUN || type == ActivityType.WALK || type == ActivityType.HIKE
        val startSeconds = session?.fields?.get(2)?.let(::unix) ?: records.first().time
        val timer = session?.fields?.get(8)?.let { (it / 1000.0).roundToInt() }
            ?: session?.fields?.get(7)?.let { (it / 1000.0).roundToInt() }
            ?: (records.last().time - records.first().time).toInt()
        val distance = session?.fields?.get(9)?.let { (it / 100.0).roundToInt() }
            ?: records.mapNotNull { it.distance }.maxOrNull()?.roundToInt()

        val lapSpans = laps.mapNotNull { lap ->
            val start = lap.fields[2]?.let(::unix) ?: return@mapNotNull null
            val elapsed = lap.fields[7]?.let { it / 1000.0 } ?: return@mapNotNull null
            lap to (start..(start + elapsed.toLong()))
        }
        val intensities = lapSpans.mapNotNull { it.first.fields[23] }
        // A structured workout marks its laps (warm-up, rest, interval…); plain auto-laps are all "active".
        val structured = intensities.any { it != 0L }
        val segments = if (lapSpans.size < 2) emptyList() else lapSpans.map { (lap, span) ->
            val inLap = records.filter { it.time in span }
            ActivitySegment(
                kind = when (lap.fields[23]) {
                    2L -> SegmentKind.WARMUP
                    3L -> SegmentKind.COOLDOWN
                    1L, 4L -> SegmentKind.RECOVERY
                    5L -> SegmentKind.WORK
                    else -> if (structured) SegmentKind.WORK else SegmentKind.STEADY
                },
                durationSeconds = (lap.fields[8] ?: lap.fields[7])?.let { (it / 1000.0).roundToInt() },
                distanceMeters = lap.fields[9]?.let { (it / 100.0).roundToInt() }?.takeIf { it > 0 },
                avgHeartRate = lap.fields[15]?.toInt() ?: average(inLap.mapNotNull { it.hr }),
                cadenceSpm = if (steps) stepCadence(inLap) else null,
                stepLengthCm = if (steps) stepLength(inLap) else null,
            )
        }

        val moving = records.filter { (it.speed ?: 0.0) > MOVING_MPS }
        val metrics = ActivityMetrics(
            cadence = if (steps) stepCadence(records) else average(moving.mapNotNull { it.cadence?.toDouble() }),
            cadenceIsSteps = steps,
            stepLengthCm = if (steps) stepLength(records) else null,
            stepLengthMeasured = steps && moving.any { it.stepLengthMm != null },
            verticalOscillationMm = average(moving.mapNotNull { it.verticalOscillationMm }),
            groundContactMs = average(moving.mapNotNull { it.stanceMs }),
            avgPowerW = average(moving.mapNotNull { it.power?.toDouble() }),
            decouplingPercent = decoupling(moving),
            splits = splits(records, steps),
            samples = records.size,
            hrHistogram = HeartZones.histogram(records.mapNotNull { s -> s.hr?.let { HeartPoint(s.time * 1000, it) } }),
        )
        val serial = fileId?.fields?.get(3)
        val created = fileId?.fields?.get(4)
        return FitResult(
            activity = Activity(
                type = type,
                title = "",
                startedAtMillis = startSeconds * 1000,
                durationSeconds = timer.coerceAtLeast(1),
                distanceMeters = distance?.takeIf { it > 0 },
                avgHeartRate = session?.fields?.get(16)?.toInt() ?: average(records.mapNotNull { it.hr }),
                maxHeartRate = session?.fields?.get(17)?.toInt() ?: records.mapNotNull { it.hr }.maxOrNull(),
                elevationGainMeters = session?.fields?.get(22)?.toInt(),
                segments = segments,
                source = ActivitySource.FIT,
                metrics = metrics,
            ),
            externalId = "fit:${serial ?: 0}-${created ?: startSeconds}",
            samples = records.size,
        ).let { it.copy(activity = it.activity.copy(externalId = it.externalId)) }
    }

    private class Sample(
        val time: Long,
        val distance: Double?,
        val speed: Double?,
        val hr: Int?,
        /** Strides (or pedal turns) per minute, as the watch records it. */
        val cadence: Double?,
        val stepLengthMm: Double?,
        val verticalOscillationMm: Double?,
        val stanceMs: Double?,
        val power: Int?,
    )

    private fun sample(m: FitMessage): Sample {
        val f = m.fields
        val cadence = f[4]?.let { it + (f[53] ?: 0) / 128.0 }
        return Sample(
            time = unix(m.timestamp!!),
            distance = f[5]?.let { it / 100.0 },
            speed = (f[73] ?: f[6])?.let { it / 1000.0 },
            hr = f[3]?.toInt(),
            cadence = cadence,
            stepLengthMm = f[85]?.let { it / 10.0 },
            verticalOscillationMm = f[39]?.let { it / 10.0 },
            stanceMs = f[41]?.let { it / 10.0 },
            power = f[7]?.toInt(),
        )
    }

    /** Steps per minute while moving: running watches record strides (one foot), so ×2. */
    private fun stepCadence(samples: List<Sample>): Int? =
        average(samples.filter { (it.speed ?: 0.0) > MOVING_MPS }.mapNotNull { it.cadence?.takeIf { c -> c > 0 }?.times(2) })

    /** Measured when the watch or a pod records it; otherwise speed ÷ step rate. */
    private fun stepLength(samples: List<Sample>): Int? {
        val moving = samples.filter { (it.speed ?: 0.0) > MOVING_MPS }
        val measured = moving.mapNotNull { it.stepLengthMm }
        if (measured.isNotEmpty()) return (measured.average() / 10).roundToInt()
        val derived = moving.mapNotNull { s ->
            val spm = s.cadence?.takeIf { it > 0 }?.times(2) ?: return@mapNotNull null
            s.speed!! / (spm / 60.0) * 100
        }
        return derived.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
    }

    /**
     * How much the heart rate rose against the pace from the first half to the second (moving
     * time): (speed/HR first half − speed/HR second half) ÷ first half, in percent. Only for 20
     * minutes or more with heart rate; meaningful for steady efforts, not for intervals.
     */
    private fun decoupling(moving: List<Sample>): Double? {
        val withHr = moving.filter { it.hr != null && it.speed != null }
        if (withHr.size < 2 || withHr.last().time - withHr.first().time < 20 * 60) return null
        val mid = withHr.first().time + (withHr.last().time - withHr.first().time) / 2
        fun ef(part: List<Sample>) = part.map { it.speed!! }.average() / part.map { it.hr!!.toDouble() }.average()
        val first = withHr.filter { it.time < mid }
        val second = withHr.filter { it.time >= mid }
        if (first.isEmpty() || second.isEmpty()) return null
        val a = ef(first)
        return ((a - ef(second)) / a * 100 * 10).roundToInt() / 10.0
    }

    /** A split for every whole kilometre: its time, heart rate and cadence. */
    private fun splits(samples: List<Sample>, steps: Boolean): List<KmSplit> {
        val withDistance = samples.filter { it.distance != null }
        if (withDistance.isEmpty()) return emptyList()
        val result = mutableListOf<KmSplit>()
        var startIndex = 0
        var nextKm = 1
        withDistance.forEachIndexed { i, s ->
            if (s.distance!! >= nextKm * 1000.0) {
                val part = withDistance.subList(startIndex, i + 1)
                result += KmSplit(
                    km = nextKm,
                    seconds = (s.time - withDistance[startIndex].time).toInt(),
                    avgHeartRate = average(part.mapNotNull { it.hr }),
                    cadence = if (steps) stepCadence(part) else null,
                )
                startIndex = i
                nextKm++
            }
        }
        return result
    }

    private fun unix(fitSeconds: Long) = fitSeconds + FitDecoder.FIT_EPOCH_SECONDS

    @JvmName("averageInts")
    private fun average(values: List<Int>): Int? = values.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
    private fun average(values: List<Double>): Int? = values.takeIf { it.isNotEmpty() }?.average()?.roundToInt()

    private const val MOVING_MPS = 0.5
}
