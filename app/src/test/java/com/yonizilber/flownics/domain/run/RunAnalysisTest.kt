package com.yonizilber.flownics.domain.run

import com.yonizilber.flownics.domain.fit.FitDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp

/**
 * Synthetic runs whose structure is known (with watch-like noise from a fixed seed, so every run
 * of the tests is the same), and real recordings, which must give a sensible, repeatable result.
 */
class RunAnalysisTest {

    /** One reading a second, speed with noise; heart rate follows the speed with a lag of [tau] seconds and drifts. */
    private fun run(
        speedAt: (Int) -> Double,
        seconds: Int,
        gradeAt: (Int) -> Double = { 0.0 },
        hrFor: (Double) -> Double = { 60 + 27 * it },
        tau: Double = 40.0,
        drift: Double = 0.0,
        seed: Long = 7,
        skip: IntRange? = null,
    ): List<RunSample> {
        val rnd = Random(seed)
        var dist = 0.0
        var alt = 100.0
        var hr = 90.0
        return (0 until seconds).mapNotNull { t ->
            val v = speedAt(t)
            val noisy = v + rnd.nextGaussian() * 0.15
            dist += v
            alt += v * gradeAt(t)
            hr += (hrFor(v) + drift * t / 60 - hr) * (1 - exp(-1 / tau))
            if (skip != null && t in skip) return@mapNotNull null
            RunSample(t.toLong(), dist, noisy, (hr + rnd.nextGaussian()).toInt(), alt, 170.0)
        }
    }

    @Test
    fun peltFindsAStepExactly() {
        val y = DoubleArray(40) { if (it < 20) 1.0 else 3.0 }
        val x = DoubleArray(40) { it.toDouble() }
        assertEquals(listOf(0, 20), RunAnalysis.pelt(x, y, beta = 0.5, minLen = 4))
        // A straight line is one segment: the cost fits a slope.
        val ramp = DoubleArray(40) { 1 + it * 0.05 }
        assertEquals(listOf(0), RunAnalysis.pelt(x, ramp, beta = 0.5, minLen = 4))
    }

    @Test
    fun anEvenRunIsSteadyAndItsHeartRateSettles() {
        val s = checkNotNull(RunAnalysis.analyze(run({ 3.0 }, 30 * 60, drift = 0.3)))
        assertEquals(RunType.STEADY, s.type)
        assertTrue("segments: ${s.segments.size}", s.segments.size <= 2)
        val main = s.segments.maxBy { it.endS - it.startS }
        assertEquals(333.3, RunAnalysis.paceOf(main.speedMean), 6.0)
        // 60 + 27 × 3 = 141, drifting 0.3 a minute: settled soon after the start.
        assertNotNull(main.hrSettled)
        assertEquals(142.0, main.hrSettled!!.toDouble(), 4.0)
        assertEquals(0.3, main.hrDriftPerMin!!, 0.15)
        assertTrue(s.paceErrorRms < 15)
    }

    @Test
    fun aBuildUpIsOneSlopedSegmentNotSteps() {
        val s = checkNotNull(RunAnalysis.analyze(run({ 2.8 + 0.8 * it / 2400.0 }, 2400)))
        assertEquals(RunType.PROGRESSIVE, s.type)
        assertTrue("segments: ${s.segments.size}", s.segments.size <= 3)
        val first = s.segments.first()
        assertTrue(first.speedEnd > first.speedStart)
    }

    @Test
    fun intervalsAreFoundRepByRep() {
        // 10 min easy, 8 × (90 s fast, 60 s easy), 10 min easy.
        val speed = { t: Int ->
            val k = t - 600
            if (k < 0 || k >= 8 * 150) 2.8 else if (k % 150 < 90) 4.5 else 2.5
        }
        val s = checkNotNull(RunAnalysis.analyze(run(speed, 600 + 8 * 150 + 600)))
        assertEquals(RunType.INTERVALS, s.type)
        val r = s.repeats.single()
        assertEquals(2, r.period)
        assertTrue("reps: ${r.count}", r.count >= 7)
        val reps = s.segments.subList(r.first, r.first + r.period * r.count).filter { it.level == s.levels.lastIndex }
        reps.forEach { rep ->
            assertEquals(90.0, (rep.endS - rep.startS).toDouble(), 15.0)
            assertEquals(222.2, RunAnalysis.paceOf(rep.speedMean), 10.0)
            // Too short for the heart rate to settle: no settled level, but its end and peak.
            assertNull(rep.hrSettled)
            assertNotNull(rep.hrEnd)
        }
    }

    @Test
    fun aHillAtEvenEffortIsSteadyByGradeAdjustedPace() {
        // Up 6 % for 8 minutes in the middle, slower by Minetti's factor: the same effort.
        val grade = { t: Int -> if (t in 900..1380) 0.06 else 0.0 }
        val s = checkNotNull(RunAnalysis.analyze(run({ t -> 3.2 / RunAnalysis.minettiFactor(grade(t)) }, 2400, grade)))
        assertEquals(RunType.STEADY, s.type)
        assertTrue("segments: ${s.segments.size}", s.segments.size <= 3)
        // About 67 m up, and the effort was told by grade-adjusted pace, not slowed-down pace.
        assertEquals(67.0, s.segments.sumOf { it.climbM }.toDouble(), 15.0)
    }

    @Test
    fun aLongStopSplitsTheRun() {
        val s = checkNotNull(RunAnalysis.analyze(run({ 3.0 }, 1800, skip = 900..1020)))
        assertTrue(s.segments.size >= 2)
        assertTrue(s.segments.none { it.startS < 900 && it.endS > 1020 })
        assertEquals((1800 - 121).toDouble(), s.movingS.toDouble(), 10.0)
    }

    @Test
    fun theSameRecordingAlwaysGivesTheSameStructure() {
        val samples = run({ t -> if (t % 600 < 300) 3.0 else 3.6 }, 3000)
        assertEquals(RunAnalysis.analyze(samples), RunAnalysis.analyze(samples))
    }

    @Test
    fun realRecordingsGiveASensibleStructure() {
        listOf("activity-small-fenix2-run.fit", "garmin-fenix-5-run.fit").forEach { name ->
            val bytes = checkNotNull(javaClass.getResourceAsStream("/fit/$name")).readBytes()
            val messages = FitDecoder.decode(bytes)
            val s = RunAnalysis.analyze(messages) ?: return@forEach
            assertTrue(name, s.segments.isNotEmpty())
            assertTrue(name, s.segments.zipWithNext().all { (a, b) -> a.endS <= b.startS })
            assertTrue(name, s.paceErrorRms.isFinite())
            assertEquals(name, s, RunAnalysis.analyze(messages))
            println("$name: ${s.type}, ${s.segments.size} segments, ${s.distanceM} m, error ±${s.paceErrorRms} s/km")
            println(RunFormat.structure(s, null))
        }
    }

    @Test
    fun minettiIsOneOnTheFlatAndMoreUphill() {
        assertEquals(1.0, RunAnalysis.minettiFactor(0.0), 1e-9)
        assertTrue(RunAnalysis.minettiFactor(0.1) > 1.5)
        assertTrue(abs(RunAnalysis.minettiFactor(-0.1) - 1) < 0.5)
    }
}
