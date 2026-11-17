package com.yonizilber.flownics.demo

import com.yonizilber.flownics.demo.FitWriter.Companion.ENUM
import com.yonizilber.flownics.demo.FitWriter.Companion.FIT_EPOCH
import com.yonizilber.flownics.demo.FitWriter.Companion.UINT16
import com.yonizilber.flownics.demo.FitWriter.Companion.UINT32
import com.yonizilber.flownics.demo.FitWriter.Companion.UINT32Z
import com.yonizilber.flownics.demo.FitWriter.Companion.UINT8
import com.yonizilber.flownics.demo.FitWriter.Field
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random

/**
 * Debug builds only: made-up runs, second by second, written as FIT recordings. Heart rate
 * follows the pace with first-order kinetics and a slow drift, as a real heart does, so the run
 * analysis has something realistic (and fictional) to work on.
 */
object DemoRuns {
    /** One stretch of a run: pace moving linearly from [paceStart] to [paceEnd] seconds per km. */
    data class Part(val seconds: Int, val paceStart: Int, val paceEnd: Int = paceStart, val intensity: Int = 0)

    data class Lap(val intensity: Int, val seconds: Int, val meters: Double, val avgHr: Int, val maxHr: Int, val endHr: Int)

    class Sim(val bytes: ByteArray, val laps: List<Lap>, val seconds: Int, val meters: Double)

    // FIT lap intensity values.
    const val ACTIVE = 0
    const val WARMUP = 2
    const val COOLDOWN = 3
    const val RECOVERY = 4
    const val INTERVAL = 5

    fun intervals(reps: Int, repSeconds: Int, repPaces: List<Int>, recoverySeconds: Int): List<Part> = buildList {
        add(Part(15 * 60, 375, 355, WARMUP))
        repeat(reps) { i ->
            add(Part(repSeconds, repPaces[i], repPaces[i], INTERVAL))
            // A recovery after every rep, the last too; the cool-down is that much shorter.
            add(Part(recoverySeconds, 400, 395, RECOVERY))
        }
        add(Part(10 * 60 - recoverySeconds, 380, 395, COOLDOWN))
    }

    /**
     * [autoLapMeters]: laps every so many metres (a plain run); otherwise each part is a lap
     * (a structured workout). [hills]: a rolling course.
     */
    fun simulate(startUnixSeconds: Long, serial: Long, parts: List<Part>, autoLapMeters: Int?, hills: Boolean, seed: Int): Sim {
        val rnd = Random(seed)
        val w = FitWriter()
        val start = startUnixSeconds - FIT_EPOCH
        w.define(0, 0, listOf(Field(0, 1, ENUM), Field(1, 2, UINT16), Field(2, 2, UINT16), Field(3, 4, UINT32Z), Field(4, 4, UINT32)))
        w.write(0, 4, 255, 1, serial, start)
        w.define(1, 20, listOf(Field(253, 4, UINT32), Field(5, 4, UINT32), Field(6, 2, UINT16), Field(3, 1, UINT8), Field(4, 1, UINT8), Field(2, 2, UINT16)))
        w.define(2, 19, listOf(
            Field(253, 4, UINT32), Field(2, 4, UINT32), Field(7, 4, UINT32), Field(8, 4, UINT32), Field(9, 4, UINT32),
            Field(15, 1, UINT8), Field(16, 1, UINT8), Field(23, 1, ENUM), Field(24, 1, ENUM), Field(254, 2, UINT16),
        ))

        val laps = mutableListOf<Lap>()
        var lapStart = 0
        var lapDist = 0.0
        var lapHr = 0.0
        var lapMax = 0
        var lapIndex = 0
        var t = 0
        var dist = 0.0
        var hr = 92.0
        var noise = 0.0
        var climb = 0.0
        var lastAlt: Double? = null
        var allHr = 0.0
        var allMax = 0

        fun closeLap(intensity: Int, trigger: Int) {
            val secs = t - lapStart
            if (secs <= 0) return
            val avg = (lapHr / secs).roundToInt()
            w.write(2, start + t, start + lapStart, secs * 1000L, secs * 1000L, (lapDist * 100).roundToLong(), avg.toLong(), lapMax.toLong(), intensity.toLong(), trigger.toLong(), lapIndex.toLong())
            laps += Lap(intensity, secs, lapDist, avg, lapMax, hr.roundToInt())
            lapIndex++
            lapStart = t
            lapDist = 0.0
            lapHr = 0.0
            lapMax = 0
        }

        parts.forEachIndexed { pi, part ->
            for (s in 0 until part.seconds) {
                val frac = s.toDouble() / part.seconds
                val pace = part.paceStart + (part.paceEnd - part.paceStart) * frac
                noise = 0.85 * noise + 0.15 * rnd.nextDouble(-1.0, 1.0)
                val alt = if (hills) 40 + 14 * sin(2 * PI * t / 900.0) else 30.0
                val grade = lastAlt?.let { (alt - it) / (1000.0 / pace) } ?: 0.0
                lastAlt?.let { if (alt > it) climb += alt - it }
                lastAlt = alt
                // Uphill slows the runner a little at the same effort.
                val v = (1000.0 / pace) * (1 + 0.025 * noise) * (1 - 2.0 * grade)
                val target = 58 + 29 * (1000.0 / pace) + 0.12 * t / 60.0
                val tau = if (target > hr) 30.0 else 45.0
                hr += (target - hr) * (1 - exp(-1 / tau))
                val shown = (hr + rnd.nextDouble(-1.2, 1.2)).roundToInt()
                dist += v
                lapDist += v
                lapHr += shown
                allHr += shown
                lapMax = maxOf(lapMax, shown)
                allMax = maxOf(allMax, shown)
                val strides = ((150 + 8.5 * v) / 2 + rnd.nextDouble(-0.6, 0.6)).roundToInt()
                w.write(1, start + t, (dist * 100).roundToLong(), (v * 1000).roundToLong(), shown.toLong(), strides.toLong(), ((alt + 500) * 5).roundToLong())
                t++
                if (autoLapMeters != null && lapDist >= autoLapMeters) closeLap(ACTIVE, 2)
            }
            if (autoLapMeters == null) closeLap(part.intensity, if (pi == parts.lastIndex) 7 else 1)
        }
        if (autoLapMeters != null) closeLap(ACTIVE, 7)

        w.define(3, 18, listOf(
            Field(253, 4, UINT32), Field(2, 4, UINT32), Field(5, 1, ENUM), Field(7, 4, UINT32), Field(8, 4, UINT32),
            Field(9, 4, UINT32), Field(16, 1, UINT8), Field(17, 1, UINT8), Field(22, 2, UINT16),
        ))
        w.write(3, start + t, start, 1, t * 1000L, t * 1000L, (dist * 100).roundToLong(), (allHr / t).roundToLong(), allMax.toLong(), climb.roundToLong())
        return Sim(w.bytes(), laps, t, dist)
    }
}
