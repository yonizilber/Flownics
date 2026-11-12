package com.yonizilber.flownics.domain.fit

import com.yonizilber.flownics.domain.model.ActivityType
import org.junit.Test
import java.time.Instant

/**
 * Real recordings from several devices (sample files from python-fitparse, MIT): the decoder
 * must read each one and come out with plausible numbers. The printed summaries are for a human
 * reading the test log; the asserts are what must hold.
 */
class FitActivityTest {

    private fun file(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/fit/$name")) { "missing test file $name" }.readBytes()

    private fun read(name: String): FitResult = FitActivity.read(file(name)).also { r ->
        val a = r.activity
        println(
            "$name: ${a.type} ${Instant.ofEpochMilli(a.startedAtMillis)} ${a.distanceMeters} m in ${a.durationSeconds} s, " +
                "HR ${a.avgHeartRate}/${a.maxHeartRate}, climb ${a.elevationGainMeters}, ${a.segments.size} parts, ${r.samples} samples, metrics ${a.metrics}",
        )
    }

    private fun check(condition: Boolean, what: String) {
        if (!condition) throw AssertionError(what)
    }

    @Test
    fun aFenix5RunReadsAsARun() {
        val r = read("garmin-fenix-5-run.fit")
        val a = r.activity
        check(a.type == ActivityType.RUN, "type ${a.type}")
        check(a.durationSeconds in 30..36_000, "duration ${a.durationSeconds}")
        check((a.distanceMeters ?: 0) in 50..100_000, "distance ${a.distanceMeters}")
        check(a.avgHeartRate == null || a.avgHeartRate in 60..220, "hr ${a.avgHeartRate}")
        // 2017 or so, not 1989 or the far future: the FIT epoch is applied.
        check(Instant.ofEpochMilli(a.startedAtMillis).toString() > "2010", "start ${Instant.ofEpochMilli(a.startedAtMillis)}")
        check(r.externalId.startsWith("fit:"), r.externalId)
    }

    @Test
    fun aLongerRunHasSplitsAndCadence() {
        val a = read("activity-small-fenix2-run.fit").activity
        check(a.type == ActivityType.RUN, "type ${a.type}")
        val m = checkNotNull(a.metrics)
        check(m.samples > 100, "samples ${m.samples}")
        val km = (a.distanceMeters ?: 0) / 1000
        check(m.splits.size == km, "splits ${m.splits.size} for $km km")
        m.splits.forEach { check(it.seconds in 120..900, "split ${it.km}: ${it.seconds} s") }
        m.cadence?.let { check(it in 120..230, "cadence $it spm") }
        m.stepLengthCm?.let { check(it in 50..250, "step length $it cm") }
    }

    @Test
    fun aWalkAndARideAreTold() {
        check(read("garmin-fenix-5-walk.fit").activity.type == ActivityType.WALK, "walk")
        val ride = read("2019-02-17-062644-ELEMNT-297E-195-0.fit").activity
        check(ride.type == ActivityType.RIDE, "ride ${ride.type}")
        // A bike's cadence is pedal turns, never doubled into steps.
        ride.metrics?.let { check(!it.cadenceIsSteps && it.stepLengthCm == null, "ride metrics $it") }
    }

    @Test
    fun developerFieldsAreSkippedNotMisread() {
        val a = read("developer-types-sample.fit").activity
        check(a.durationSeconds > 0, "duration ${a.durationSeconds}")
    }

    @Test
    fun aCutOffFileIsReportedNotHalfRead() {
        val e = runCatching { FitActivity.read(file("activity-unexpected-eof.fit")) }.exceptionOrNull()
        check(e is FitException, "got $e")
        val notFit = runCatching { FitActivity.read(ByteArray(40) { 1 }) }.exceptionOrNull()
        check(notFit is FitException, "got $notFit")
    }
}
