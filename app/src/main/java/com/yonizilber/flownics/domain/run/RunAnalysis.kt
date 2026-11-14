package com.yonizilber.flownics.domain.run

import com.yonizilber.flownics.domain.fit.FitMessage
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One reading of the recording, [t] seconds from its start. */
data class RunSample(
    val t: Long,
    val distanceM: Double? = null,
    val speed: Double? = null,
    val hr: Int? = null,
    val altitudeM: Double? = null,
    /** Steps per minute (both feet). */
    val cadence: Double? = null,
    val power: Int? = null,
)

/**
 * A recording turned into its structure, by standard methods put together (deterministic: the
 * same recording always gives the same structure):
 *
 * 1. Readings are put in 5-second bins; empty or standing bins are pauses. Grade comes from the
 *    smoothed altitude over at least 20 m, and grade-adjusted speed from Minetti et al. (2002),
 *    the energy cost of running on slopes.
 * 2. Laps the user or a structured workout made are kept as boundaries; inside them change points
 *    are found by PELT (Killick, Fearnhead & Eckley 2012), the exact optimal segmentation for a
 *    penalised cost, here a straight line fitted to the grade-adjusted speed: so a build-up is one
 *    segment with a slope, not ten steps. The penalty is the noise level (median absolute
 *    difference) squared × ln n; neighbours whose lines stay within 8 s/km of one line are merged.
 * 3. Each segment's pace is told by its line and how far the data is from it; a segment too uneven
 *    for a line is marked variable and told by percentiles. Heart rate is fitted with first-order
 *    kinetics (HR = A + B·e^(−t/τ) + D·t), as in the heart-rate dynamics studies (Hunt et al. 2015:
 *    τ about a minute), so a long segment gets the level it settled at and its drift; a short rep,
 *    where the heart rate never settles, gets its end and peak instead of a misleading mean.
 * 4. Levels of effort by gaps between the segments' paces, repeats by a search for a pattern of
 *    two to four segments done at least three times, and the run's type from those.
 */
object RunAnalysis {
    /** Raise whenever the result for the same recording changes, so stored structures are made again. */
    const val VERSION = 1
    const val BIN = 5

    private const val RECORD = 20
    private const val LAP = 19
    private const val MOVING_MPS = 0.6
    private const val PAUSE_BINS = 6
    private const val MIN_POINTS = 4
    private const val MERGE_PACE = 10.0
    /** A segment shorter than this joins a neighbour unless its pace is a level apart from both. */
    private const val SHORT_BINS = 12
    /** Only a segment this long (3 min) has a slope worth keeping apart from a neighbour's. */
    private const val SLOPE_BINS = 36
    private const val VARIABLE_PACE = 15.0
    private const val LEVEL_GAP_PACE = 20.0
    /** A new level of effort where one segment is this much faster than the next slower one (8 %: about 20 s/km at 4:00). */
    private const val LEVEL_RATIO = 1.08
    private const val LEVEL_SPAN = 1.15
    private val TAUS = intArrayOf(15, 20, 30, 45, 60, 90)
    private const val MAX_DRIFT = 3.0

    /** From a FIT file's messages; null when it has no speed to work with. */
    fun analyze(messages: List<FitMessage>): RunStructure? {
        val (samples, cuts) = fromFit(messages)
        return analyze(samples, cuts)
    }

    /** The readings of a FIT file, and the starts of laps the user or a workout made (seconds from the start). */
    fun fromFit(messages: List<FitMessage>): Pair<List<RunSample>, List<Long>> {
        val records = messages.filter { it.global == RECORD && it.timestamp != null }
        if (records.isEmpty()) return emptyList<RunSample>() to emptyList()
        val t0 = records.first().timestamp!!
        val samples = records.map { m ->
            val f = m.fields
            RunSample(
                t = m.timestamp!! - t0,
                distanceM = f[5]?.let { it / 100.0 },
                speed = (f[73] ?: f[6])?.let { it / 1000.0 },
                hr = f[3]?.toInt(),
                altitudeM = (f[78] ?: f[2])?.let { it / 5.0 - 500 },
                cadence = f[4]?.let { (it + (f[53] ?: 0) / 128.0) * 2 },
                power = f[7]?.toInt(),
            )
        }
        val laps = messages.filter { it.global == LAP }
        // Laps from a structured workout (marked warm-up, interval, rest…) or pressed by hand; not auto-laps.
        val structured = laps.dropLast(1).any { (it.fields[23] ?: 0L) != 0L || it.fields[24] == 0L }
        val cuts = if (!structured) emptyList() else laps.drop(1).mapNotNull { it.fields[2]?.minus(t0) }.filter { it > 0 }
        return samples to cuts
    }

    fun analyze(samples: List<RunSample>, lapCuts: List<Long> = emptyList()): RunStructure? {
        val bins = Bins.of(samples) ?: return null
        val moving = bins.indices.filter { bins.moving[it] }
        if (moving.size < 2 * MIN_POINTS) return null

        // Spans between pauses and lap boundaries; each is segmented on its own.
        val cutBins = lapCuts.map { (it / BIN).toInt() }.toSet()
        val spans = mutableListOf<MutableList<Int>>()
        var last = -100
        moving.forEach { b ->
            val cut = b - last > PAUSE_BINS || (last + 1..b).any { it in cutBins }
            if (spans.isEmpty() || cut) spans += mutableListOf(b) else spans.last() += b
            last = b
        }

        val gapY = bins.gap
        val sigma = robustSigma(moving.map { gapY[it] })
        val beta = 4 * sigma * sigma * ln(moving.size.toDouble())
        val pieces = spans.flatMap { span ->
            val x = DoubleArray(span.size) { span[it].toDouble() }
            val y = DoubleArray(span.size) { gapY[span[it]] }
            val starts = pelt(x, y, beta, MIN_POINTS)
            val bounds = starts + span.size
            absorbShort(merge((0 until starts.size).map { k -> span.subList(bounds[k], bounds[k + 1]) }, bins), bins)
        }

        var segments = pieces.map { describe(it, bins) }
        val levels = levels(segments)
        segments = segments.map { s -> s.copy(level = levels.indexOfLevel(paceOf(s.gapMean))) }
        val repeats = repeats(segments)
        val movingS = moving.size * BIN
        // The error of the lines, over the segments a line tells (the uneven ones are told by percentiles).
        val told = pieces.zip(segments).filter { !it.second.variable }.map { it.first }
        val rms = sqrt(told.sumOf { p -> paceResiduals(p, bins).sumOf { it * it } } / max(1, told.sumOf { it.size }))
        return RunStructure(
            version = VERSION,
            type = type(segments, repeats, movingS),
            source = if (cutBins.isEmpty()) SegmentSource.DETECTED else SegmentSource.LAPS_AND_DETECTED,
            binSeconds = BIN,
            durationS = bins.size * BIN,
            movingS = movingS,
            distanceM = bins.totalDistance().roundToInt(),
            segments = segments,
            repeats = repeats,
            levels = levels.map { round3(it.mean) },
            paceErrorRms = round1(rms),
            unevenS = segments.filter { it.variable }.sumOf { it.endS - it.startS },
        )
    }

    /** The readings from [fromS] to [toS] in steps of [stepS] seconds, for looking closer. */
    fun detail(samples: List<RunSample>, fromS: Int, toS: Int, stepS: Int): List<DetailRow> {
        val bins = Bins.of(samples) ?: return emptyList()
        val per = (stepS / BIN).coerceAtLeast(1)
        val first = (fromS / BIN).coerceIn(0, bins.size)
        val end = (toS / BIN).coerceIn(first, bins.size)
        return (first until end step per).mapNotNull { b ->
            val group = (b until minOf(b + per, end)).filter { bins.moving[it] }
            if (group.isEmpty()) return@mapNotNull DetailRow(b * BIN, null, null, null, null, null, null)
            fun avg(a: DoubleArray) = group.map { a[it] }.filter { !it.isNaN() }.takeIf { it.isNotEmpty() }?.average()
            DetailRow(
                b * BIN,
                avg(bins.speed),
                avg(bins.gap),
                avg(bins.hr)?.roundToInt(),
                avg(bins.cadence)?.roundToInt(),
                avg(bins.altitude)?.let { round1(it) },
                avg(bins.grade)?.let { round1(it * 100) },
            )
        }
    }

    data class DetailRow(val t: Int, val speed: Double?, val gap: Double?, val hr: Int?, val cadence: Int?, val altitude: Double?, val gradePercent: Double?)

    // --- Bins ---

    internal class Bins(
        val size: Int,
        val moving: BooleanArray,
        val speed: DoubleArray,
        val gap: DoubleArray,
        val grade: DoubleArray,
        val altitude: DoubleArray,
        val distance: DoubleArray,
        val hr: DoubleArray,
        val cadence: DoubleArray,
        val power: DoubleArray,
    ) {
        val indices get() = 0 until size

        fun totalDistance(): Double {
            val d = distance.filter { !it.isNaN() }
            return if (d.size >= 2) d.last() - d.first() + speedAt(0) * BIN else indices.filter { moving[it] }.sumOf { speed[it] * BIN }
        }

        private fun speedAt(b: Int) = if (moving[b]) speed[b] else 0.0

        companion object {
            fun of(samples: List<RunSample>): Bins? {
                if (samples.isEmpty()) return null
                val sorted = samples.sortedBy { it.t }
                val n = (sorted.last().t / BIN + 1).toInt()
                fun nan() = DoubleArray(n) { Double.NaN }
                val speed = nan(); val hr = nan(); val alt = nan(); val dist = nan(); val cad = nan(); val pow = nan()
                sorted.groupBy { (it.t / BIN).toInt() }.forEach { (b, group) ->
                    group.mapNotNull { it.speed }.takeIf { it.isNotEmpty() }?.let { speed[b] = it.average() }
                    group.mapNotNull { it.hr }.takeIf { it.isNotEmpty() }?.let { hr[b] = it.average() }
                    group.mapNotNull { it.altitudeM }.takeIf { it.isNotEmpty() }?.let { alt[b] = it.average() }
                    group.mapNotNull { it.distanceM }.takeIf { it.isNotEmpty() }?.let { dist[b] = it.max() }
                    group.mapNotNull { it.cadence }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.let { cad[b] = it.average() }
                    group.mapNotNull { it.power }.takeIf { it.isNotEmpty() }?.let { pow[b] = it.average() }
                }
                // No speed field: from the distance covered.
                for (b in 1 until n) if (speed[b].isNaN() && !dist[b].isNaN()) {
                    val prev = (b - 1 downTo 0).firstOrNull { !dist[it].isNaN() } ?: continue
                    speed[b] = (dist[b] - dist[prev]) / ((b - prev) * BIN)
                }
                val moving = BooleanArray(n) { !speed[it].isNaN() && speed[it] > MOVING_MPS }
                if (moving.none { it }) return null
                val smooth = movingAverage(alt, 5)
                val grade = DoubleArray(n) { b -> gradeAt(b, smooth, dist) }
                val gap = DoubleArray(n) { b -> if (speed[b].isNaN()) Double.NaN else speed[b] * minettiFactor(grade[b]) }
                return Bins(n, moving, speed, gap, grade, smooth, dist, hr, cad, pow)
            }

            /** Rise over run between bins at least 20 m apart around [b] (up to 30 s each side), or flat when unknown. */
            private fun gradeAt(b: Int, alt: DoubleArray, dist: DoubleArray): Double {
                for (w in 2..6) {
                    val i = b - w
                    val j = b + w
                    if (i < 0 || j >= alt.size) continue
                    if (alt[i].isNaN() || alt[j].isNaN() || dist[i].isNaN() || dist[j].isNaN()) continue
                    val run = dist[j] - dist[i]
                    if (run >= 20) return ((alt[j] - alt[i]) / run).coerceIn(-0.35, 0.35)
                }
                return 0.0
            }

            private fun movingAverage(a: DoubleArray, width: Int): DoubleArray = DoubleArray(a.size) { i ->
                val window = (i - width / 2..i + width / 2).filter { it in a.indices && !a[it].isNaN() }
                if (a[i].isNaN() || window.isEmpty()) Double.NaN else window.sumOf { a[it] } / window.size
            }
        }
    }

    /**
     * Minetti et al. (2002): energy cost of running at grade i (J/kg/m), against 3.6 on the flat.
     * The grade-adjusted speed is the speed times this ratio.
     */
    fun minettiFactor(i: Double): Double {
        val g = i.coerceIn(-0.45, 0.45)
        val c = 155.4 * g.pow(5) - 30.4 * g.pow(4) - 43.3 * g.pow(3) + 46.3 * g * g + 19.5 * g + 3.6
        return c / 3.6
    }

    private fun Double.pow(n: Int): Double = Math.pow(this, n.toDouble())

    // --- Segmentation ---

    /**
     * σ of the noise: the median absolute distance from a 45-second running median, × 1.4826
     * (robust to the changes themselves, and to GPS noise that carries over from bin to bin, which
     * differences of neighbours would understate). At least 0.05 m/s.
     */
    private fun robustSigma(y: List<Double>): Double {
        if (y.size < 3) return 0.05
        val d = y.indices.map { i ->
            val window = y.subList(maxOf(0, i - 4), minOf(y.size, i + 5)).sorted()
            abs(y[i] - window[window.size / 2])
        }.sorted()
        return max(0.05, 1.4826 * d[d.size / 2])
    }

    /**
     * PELT: the starts of the segments minimising the sum of each segment's line-fit error plus
     * [beta] per segment, each at least [minLen] points. Exact, and the same input always gives
     * the same answer (on a tie the earlier start wins).
     */
    fun pelt(x: DoubleArray, y: DoubleArray, beta: Double, minLen: Int): List<Int> {
        val n = y.size
        if (n < 2 * minLen) return listOf(0)
        val fit = LineCost(x, y)
        val f = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
        val from = IntArray(n + 1) { -1 }
        f[0] = -beta
        var candidates = mutableListOf<Int>()
        for (t in minLen..n) {
            val newcomer = t - minLen
            if (newcomer == 0 || newcomer >= minLen) candidates.add(newcomer)
            var best = Double.POSITIVE_INFINITY
            var arg = -1
            for (s in candidates) {
                if (f[s] == Double.POSITIVE_INFINITY) continue
                val v = f[s] + fit.cost(s, t) + beta
                if (v < best) {
                    best = v
                    arg = s
                }
            }
            f[t] = best
            from[t] = arg
            val ft = best
            candidates = candidates.filterTo(mutableListOf()) { s -> f[s] == Double.POSITIVE_INFINITY || f[s] + fit.cost(s, t) <= ft }
        }
        val starts = mutableListOf<Int>()
        var t = n
        while (t > 0) {
            val s = from[t]
            if (s < 0) break
            starts += s
            t = s
        }
        return starts.reversed().ifEmpty { listOf(0) }
    }

    /** Sum of squared errors of the least-squares line over points [s, t), from prefix sums. */
    private class LineCost(x: DoubleArray, y: DoubleArray) {
        private val n = y.size
        private val sx = DoubleArray(n + 1)
        private val sy = DoubleArray(n + 1)
        private val sxx = DoubleArray(n + 1)
        private val sxy = DoubleArray(n + 1)
        private val syy = DoubleArray(n + 1)

        init {
            // Centred on the first x, for precision.
            val x0 = if (n > 0) x[0] else 0.0
            for (i in 0 until n) {
                val xi = x[i] - x0
                sx[i + 1] = sx[i] + xi
                sy[i + 1] = sy[i] + y[i]
                sxx[i + 1] = sxx[i] + xi * xi
                sxy[i + 1] = sxy[i] + xi * y[i]
                syy[i + 1] = syy[i] + y[i] * y[i]
            }
        }

        fun cost(s: Int, t: Int): Double {
            val m = (t - s).toDouble()
            val ax = sx[t] - sx[s]
            val ay = sy[t] - sy[s]
            val vxx = sxx[t] - sxx[s] - ax * ax / m
            val vxy = sxy[t] - sxy[s] - ax * ay / m
            val vyy = syy[t] - syy[s] - ay * ay / m
            return max(0.0, if (vxx <= 1e-9) vyy else vyy - vxy * vxy / vxx)
        }
    }

    /** Least-squares line through (x, y): intercept at x = 0 and slope. */
    private fun line(x: List<Double>, y: List<Double>): Pair<Double, Double> {
        val mx = x.average()
        val my = y.average()
        val vxx = x.sumOf { (it - mx) * (it - mx) }
        val slope = if (vxx <= 1e-9) 0.0 else x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / vxx
        return (my - slope * mx) to slope
    }

    /**
     * Neighbours whose lines stay within [MERGE_PACE] s/km of the one line through both (at each
     * one's ends) are joined, closest pair first, until no pair is that close.
     */
    private fun merge(pieces: List<List<Int>>, bins: Bins): List<List<Int>> {
        val out = pieces.toMutableList()
        while (out.size > 1) {
            var best = -1
            var bestDev = Double.POSITIVE_INFINITY
            for (k in 0 until out.size - 1) {
                val dev = mergeDeviation(out[k], out[k + 1], bins)
                if (dev < bestDev) {
                    bestDev = dev
                    best = k
                }
            }
            if (bestDev >= MERGE_PACE) break
            out[best] = out[best] + out[best + 1]
            out.removeAt(best + 1)
        }
        return out
    }

    /**
     * How far the one line through both would be from each one's own pace: at each one's middle,
     * the joint line against its mean, and at each one's ends, against its own line (so a build-up
     * and a steady part aren't joined). In s/km; the larger of them.
     */
    private fun mergeDeviation(a: List<Int>, b: List<Int>, bins: Bins): Double {
        fun fitOf(p: List<Int>) = line(p.map { it.toDouble() }, p.map { bins.gap[it] })
        val (im, sm) = fitOf(a + b)
        fun joint(x: Double) = paceOf(im + sm * x)
        return listOf(a, b).maxOf { p ->
            val mid = (p.first() + p.last()) / 2.0
            val mean = paceOf(p.map { bins.gap[it] }.average())
            val atMiddle = abs(joint(mid) - mean)
            // The ends only count for a segment long enough for its own slope to mean something.
            val atEnds = if (p.size >= SLOPE_BINS) {
                val (i0, s0) = fitOf(p)
                listOf(p.first(), p.last()).maxOf { x -> abs(joint(x.toDouble()) - paceOf(i0 + s0 * x)) }
            } else {
                0.0
            }
            max(atMiddle, atEnds)
        }
    }

    /**
     * A segment under [SHORT_BINS] bins (a minute) is told apart only when its pace is a level away
     * from both neighbours (a stride, a short rep); otherwise it joins the nearer one in pace.
     */
    private fun absorbShort(pieces: List<List<Int>>, bins: Bins): List<List<Int>> {
        val out = pieces.toMutableList()
        fun pace(p: List<Int>) = paceOf(p.map { bins.gap[it] }.average())
        while (out.size > 1) {
            val candidates = out.indices.filter { out[it].size < SHORT_BINS }.sortedBy { out[it].size }
            val k = candidates.firstOrNull { i ->
                listOfNotNull(out.getOrNull(i - 1), out.getOrNull(i + 1)).any { n -> abs(pace(n) - pace(out[i])) < LEVEL_GAP_PACE }
            } ?: break
            val left = out.getOrNull(k - 1)
            val right = out.getOrNull(k + 1)
            val intoLeft = right == null || (left != null && abs(pace(left) - pace(out[k])) <= abs(pace(right) - pace(out[k])))
            if (intoLeft) {
                out[k - 1] = out[k - 1] + out[k]
            } else {
                out[k + 1] = out[k] + out[k + 1]
            }
            out.removeAt(k)
        }
        return out
    }

    // --- Describing a segment ---

    private fun describe(p: List<Int>, bins: Bins): RunSegment {
        val x = p.map { it.toDouble() }
        val (i0, slope) = line(x, p.map { bins.speed[it] })
        val residuals = paceResiduals(p, bins)
        val rms = sqrt(residuals.sumOf { it * it } / residuals.size.coerceAtLeast(1))
        val abs90 = percentile(residuals.map { abs(it) }, 0.9)
        val paces = p.map { paceOf(bins.speed[it]) }
        val variable = rms > VARIABLE_PACE
        val distance = distanceOf(p, bins)
        val time = p.size * BIN
        val alts = p.map { bins.altitude[it] }.filter { !it.isNaN() }
        val climb = alts.zipWithNext { a, b -> max(0.0, b - a) }.sum()
        val descent = alts.zipWithNext { a, b -> max(0.0, a - b) }.sum()
        val hr = p.map { bins.hr[it] }
        val hrKnown = hr.filter { !it.isNaN() }
        val kinetics = kinetics(p, bins)
        return RunSegment(
            startS = p.first() * BIN,
            endS = (p.last() + 1) * BIN,
            distanceM = distance.roundToInt(),
            speedStart = round3(i0 + slope * p.first()),
            speedEnd = round3(i0 + slope * p.last()),
            speedMean = round3(distance / time),
            gapMean = round3(p.map { bins.gap[it] }.average()),
            paceErrorRms = round1(rms),
            paceError90 = round1(abs90),
            variable = variable,
            pace10 = if (variable) round1(percentile(paces, 0.1)) else null,
            pace50 = if (variable) round1(percentile(paces, 0.5)) else null,
            pace90 = if (variable) round1(percentile(paces, 0.9)) else null,
            gradePercent = if (alts.size >= 2 && distance > 0) round1((alts.last() - alts.first()) / distance * 100) else 0.0,
            climbM = climb.roundToInt(),
            descentM = descent.roundToInt(),
            hrMean = hrKnown.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            hrMax = hrKnown.maxOrNull()?.roundToInt(),
            hrStart = hr.firstOrNull { !it.isNaN() }?.roundToInt(),
            hrEnd = hr.lastOrNull { !it.isNaN() }?.roundToInt(),
            hrSettled = kinetics?.settled,
            hrDriftPerMin = kinetics?.drift,
            hrTauS = kinetics?.tau,
            hrFitRms = kinetics?.rms,
            cadence = p.map { bins.cadence[it] }.filter { !it.isNaN() }.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            powerW = p.map { bins.power[it] }.filter { !it.isNaN() }.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
        )
    }

    private fun distanceOf(p: List<Int>, bins: Bins): Double {
        val end = bins.distance[p.last()]
        val before = (p.first() - 1 downTo 0).firstOrNull { !bins.distance[it].isNaN() }?.let { bins.distance[it] }
        return if (!end.isNaN() && before != null && end > before) end - before else p.sumOf { bins.speed[it] * BIN }
    }

    /** Each bin's pace against its segment's line, s/km. */
    private fun paceResiduals(p: List<Int>, bins: Bins): List<Double> {
        val (i0, slope) = line(p.map { it.toDouble() }, p.map { bins.speed[it] })
        return p.map { paceOf(bins.speed[it]) - paceOf(i0 + slope * it) }
    }

    private class Kinetics(val settled: Int, val drift: Double, val tau: Int, val rms: Double)

    /**
     * HR(t) = A + B·e^(−t/τ) + D·t over the segment, τ from a fixed grid, A, B, D by least squares.
     * Reported only when the segment lasts at least 2τ and two minutes (before that, the heart
     * rate is still on its way and there is no level to tell), and only when the fitted level lies
     * within the segment's own readings and the drift is under 3 a minute.
     */
    private fun kinetics(p: List<Int>, bins: Bins): Kinetics? {
        val points = p.mapNotNull { b -> bins.hr[b].takeIf { !it.isNaN() }?.let { ((b - p.first()) * BIN + BIN / 2.0) to it } }
        if (points.size < 12) return null
        val duration = p.size * BIN
        var best: Triple<DoubleArray, Int, Double>? = null
        for (tau in TAUS) {
            if (duration < max(2 * tau, 120)) continue
            val rows = points.map { (t, _) -> doubleArrayOf(1.0, exp(-t / tau), t / 60.0) }
            val coef = leastSquares(rows, points.map { it.second }) ?: continue
            val sse = points.indices.sumOf { k -> val e = points[k].second - rows[k].indices.sumOf { rows[k][it] * coef[it] }; e * e }
            if (best == null || sse < best.third) best = Triple(coef, tau, sse)
        }
        val (coef, tau, sse) = best ?: return null
        // The level reached once the change is over (at 3τ, or the end), and the drift after it.
        val tSettle = minOf(3.0 * tau, duration.toDouble())
        val settled = coef[0] + coef[1] * exp(-tSettle / tau) + coef[2] * tSettle / 60
        val lo = points.minOf { it.second }
        val hi = points.maxOf { it.second }
        // A fit can match the curve with numbers no heart does; then it isn't told at all.
        if (settled < lo - 3 || settled > hi + 3 || abs(coef[2]) > MAX_DRIFT) return null
        return Kinetics(settled.roundToInt(), round1(coef[2]), tau, round1(sqrt(sse / points.size)))
    }

    /** Normal equations for a few unknowns; null when they have no single answer. */
    private fun leastSquares(rows: List<DoubleArray>, y: List<Double>): DoubleArray? {
        val k = rows.first().size
        val a = Array(k) { DoubleArray(k + 1) }
        rows.forEachIndexed { r, row ->
            for (i in 0 until k) {
                for (j in 0 until k) a[i][j] += row[i] * row[j]
                a[i][k] += row[i] * y[r]
            }
        }
        for (c in 0 until k) {
            val pivot = (c until k).maxBy { abs(a[it][c]) }
            if (abs(a[pivot][c]) < 1e-9) return null
            val tmp = a[c]; a[c] = a[pivot]; a[pivot] = tmp
            for (r in 0 until k) if (r != c) {
                val factor = a[r][c] / a[c][c]
                for (j in c..k) a[r][j] -= factor * a[c][j]
            }
        }
        return DoubleArray(k) { a[it][k] / a[it][it] }
    }

    // --- Levels, repeats, type ---

    private class Level(val paces: List<Double>, val mean: Double)

    private fun List<Level>.indexOfLevel(pace: Double): Int = indexOfFirst { pace in it.paces.min()..it.paces.max() }.coerceAtLeast(0)

    /**
     * The segments' grade-adjusted speeds, slowest first, split wherever one is more than
     * [LEVEL_RATIO] times the one before. Each level's mean is weighted by time; level 0 is the easiest.
     */
    private fun levels(segments: List<RunSegment>): List<Level> {
        val sorted = segments.sortedByDescending { paceOf(it.gapMean) }
        val groups = mutableListOf(mutableListOf(sorted.first()))
        sorted.zipWithNext { a, b ->
            // Also a new one once the level would span more than 15 % (no chaining 6:00 to 4:00 by small steps).
            if (b.gapMean > a.gapMean * LEVEL_RATIO || b.gapMean > groups.last().first().gapMean * LEVEL_SPAN) groups += mutableListOf(b) else groups.last() += b
        }
        return groups.map { g ->
            val time = g.sumOf { it.endS - it.startS }.toDouble()
            Level(g.map { paceOf(it.gapMean) }, g.sumOf { it.gapMean * (it.endS - it.startS) } / time)
        }
    }

    /**
     * A pattern of 2–4 segments at different levels done 3 or more times in a row, each segment of
     * a rep lasting (or covering) within ±40 % of the first rep's: the longest such run is taken,
     * from left to right.
     */
    fun repeats(segments: List<RunSegment>): List<RunRepeat> {
        val out = mutableListOf<RunRepeat>()
        var i = 0
        while (i < segments.size) {
            var best: RunRepeat? = null
            for (p in 2..4) {
                if (i + p * 3 > segments.size) continue
                val pattern = segments.subList(i, i + p)
                if (pattern.map { it.level }.distinct().size < 2) continue
                var r = 1
                while (i + (r + 1) * p <= segments.size) {
                    val rep = segments.subList(i + r * p, i + (r + 1) * p)
                    val same = rep.indices.all { j -> rep[j].level == pattern[j].level && similar(rep[j], pattern[j]) }
                    if (!same) break
                    r++
                }
                if (r >= 3 && (best == null || p * r > best.period * best.count)) best = RunRepeat(i, p, r)
            }
            if (best != null) {
                out += best
                i += best.period * best.count
            } else {
                i++
            }
        }
        return out
    }

    private fun similar(a: RunSegment, b: RunSegment): Boolean {
        fun near(x: Double, y: Double) = y > 0 && x / y in 0.6..1.67
        return near((a.endS - a.startS).toDouble(), (b.endS - b.startS).toDouble()) || near(a.distanceM.toDouble(), b.distanceM.toDouble())
    }

    private fun type(segments: List<RunSegment>, repeats: List<RunRepeat>, movingS: Int): RunType {
        fun dur(s: RunSegment) = s.endS - s.startS
        val inRepeats = repeats.sumOf { r -> segments.subList(r.first, r.first + r.period * r.count).sumOf(::dur) }
        if (repeats.isNotEmpty() && inRepeats >= 0.3 * movingS) return RunType.INTERVALS
        // A build-up: the main segments get faster one after another (or one long one has a slope), by 20 s/km or more.
        val main = segments.filter { dur(it) >= 60 }
        val mainTime = main.sumOf(::dur)
        if (main.isNotEmpty() && mainTime >= 0.7 * movingS) {
            val longest = main.maxBy(::dur)
            val oneSloped = dur(longest) >= 0.4 * movingS && paceOf(longest.speedStart) - paceOf(longest.speedEnd) >= LEVEL_GAP_PACE
            val rising = main.zipWithNext().all { (a, b) -> paceOf(b.gapMean) <= paceOf(a.gapMean) + 5 } &&
                paceOf(main.first().gapMean) - paceOf(main.last().gapMean) >= LEVEL_GAP_PACE
            if (oneSloped || (main.size >= 2 && rising)) return RunType.PROGRESSIVE
        }
        val byLevel = segments.groupBy { it.level }.mapValues { (_, g) -> g.sumOf(::dur) }
        if ((byLevel.values.maxOrNull() ?: 0) >= 0.85 * movingS) return RunType.STEADY
        return RunType.VARIABLE
    }

    // --- Numbers ---

    /** Seconds per km for a speed in m/s (a standstill counts as 0.5 m/s, so a pace stays finite). */
    fun paceOf(speed: Double): Double = 1000.0 / max(speed, 0.5)

    private fun percentile(values: List<Double>, q: Double): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        return s[((s.size - 1) * q).roundToInt()]
    }

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
    private fun round3(v: Double) = (v * 1000).roundToInt() / 1000.0
}
