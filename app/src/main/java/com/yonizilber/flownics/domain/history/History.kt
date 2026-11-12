package com.yonizilber.flownics.domain.history

import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.Muscle
import com.yonizilber.flownics.domain.model.SessionHeader
import com.yonizilber.flownics.domain.model.SetLog
import com.yonizilber.flownics.domain.workout.CompiledWorkout
import com.yonizilber.flownics.domain.workout.Side
import com.yonizilber.flownics.domain.workout.WorkStep

/** Σ reps × kg over the sets that were done. Sets missing either number add nothing. */
fun Collection<SetLog>.volumeKg(): Double =
    filterNot { it.skipped }.sumOf { (it.actual.reps ?: 0) * (it.actual.weightKg ?: 0.0) }

/** What one exercise's progress can be charted by. Each is a per-workout figure. */
enum class ProgressMetric {
    /** Heaviest set. */
    WEIGHT,

    /** Σ reps × kg in the workout. */
    VOLUME,

    /** Most reps in one set. */
    REPS,

    /** Longest set. */
    SECONDS,

    /** Longest distance in one set. */
    DISTANCE,
    ;

    /** The workout's figure, from its done sets; null when no set recorded this measure. */
    fun of(sets: List<SetLog>): Double? {
        val done = sets.filterNot { it.skipped }.map { it.actual }
        return when (this) {
            WEIGHT -> done.mapNotNull { it.weightKg }.maxOrNull()
            VOLUME -> done.sumOf { (it.reps ?: 0) * (it.weightKg ?: 0.0) }.takeIf { v -> v > 0 }
            REPS -> done.mapNotNull { it.reps }.maxOrNull()?.toDouble()
            SECONDS -> done.mapNotNull { it.seconds }.maxOrNull()?.toDouble()
            DISTANCE -> done.mapNotNull { it.distanceMeters }.maxOrNull()?.toDouble()
        }
    }
}

/** The sets of one exercise done in one finished workout. */
data class ExerciseSession(val sessionId: Long, val atMillis: Long, val sets: List<SetLog>)

data class ProgressPoint(val sessionId: Long, val atMillis: Long, val value: Double)

/** The best a metric has ever been, and where. [set] is the set itself for per-set metrics. */
data class Record(val metric: ProgressMetric, val value: Double, val sessionId: Long, val atMillis: Long, val set: SetLog?)

object ExerciseProgress {

    /** Groups one exercise's logs by workout, oldest first. A workout is dated by its first set. */
    fun bySession(logs: List<SetLog>): List<ExerciseSession> =
        logs.groupBy { it.sessionId }
            .map { (id, sets) -> ExerciseSession(id, sets.minOf { it.completedAtMillis }, sets.sortedBy { it.stepIndex }) }
            .sortedWith(compareBy({ it.atMillis }, { it.sessionId }))

    fun series(sessions: List<ExerciseSession>, metric: ProgressMetric): List<ProgressPoint> =
        sessions.mapNotNull { s -> metric.of(s.sets)?.let { ProgressPoint(s.sessionId, s.atMillis, it) } }

    /** Metrics with at least one value, in [ProgressMetric] order. */
    fun availableMetrics(sessions: List<ExerciseSession>): List<ProgressMetric> =
        ProgressMetric.entries.filter { metric -> sessions.any { metric.of(it.sets) != null } }

    /** The best of each available metric. Ties go to the earlier workout: that is when it was first reached. */
    fun records(sessions: List<ExerciseSession>): List<Record> = availableMetrics(sessions).map { metric ->
        val best = series(sessions, metric).reduce { best, p -> if (p.value > best.value) p else best }
        Record(metric, best.value, best.sessionId, best.atMillis, bestSet(sessions.first { it.sessionId == best.sessionId }, metric))
    }

    /**
     * Metrics on which [sessionId] beat every earlier workout. A first-ever value isn't a record:
     * there was nothing to beat, and calling it one would make every new exercise look like progress.
     */
    fun newRecords(sessions: List<ExerciseSession>, sessionId: Long): Set<ProgressMetric> {
        val index = sessions.indexOfFirst { it.sessionId == sessionId }
        if (index <= 0) return emptySet()
        val earlier = sessions.subList(0, index)
        val current = sessions[index]
        return ProgressMetric.entries.filterTo(mutableSetOf()) { metric ->
            val value = metric.of(current.sets) ?: return@filterTo false
            val previousBest = earlier.mapNotNull { metric.of(it.sets) }.maxOrNull() ?: return@filterTo false
            value > previousBest
        }
    }

    private fun bestSet(session: ExerciseSession, metric: ProgressMetric): SetLog? {
        val done = session.sets.filterNot { it.skipped }
        return when (metric) {
            // Heaviest, and of those the one with the most reps: "60 kg × 5" beats "60 kg × 3".
            ProgressMetric.WEIGHT -> done.filter { it.actual.weightKg != null }
                .maxWithOrNull(compareBy({ it.actual.weightKg }, { it.actual.reps ?: 0 }))
            ProgressMetric.REPS -> done.filter { it.actual.reps != null }.maxByOrNull { it.actual.reps!! }
            ProgressMetric.SECONDS -> done.filter { it.actual.seconds != null }.maxByOrNull { it.actual.seconds!! }
            ProgressMetric.DISTANCE -> done.filter { it.actual.distanceMeters != null }.maxByOrNull { it.actual.distanceMeters!! }
            ProgressMetric.VOLUME -> null
        }
    }
}

/**
 * Sets per muscle. A done set counts 1 for each of its exercise's primary muscles and 0.5 for each
 * secondary one. [uncountedSets] are done sets whose exercise was deleted or has no muscles tagged:
 * they are reported, not silently dropped.
 */
data class MuscleLoad(val sets: Map<Muscle, Double>, val uncountedSets: Int) {
    /** Busiest first; ties in body order. */
    val ranked: List<Pair<Muscle, Double>> get() =
        sets.entries.sortedWith(compareByDescending<Map.Entry<Muscle, Double>> { it.value }.thenBy { it.key.ordinal })
            .map { it.key to it.value }

    val untouched: List<Muscle> get() = Muscle.entries.filter { it !in sets }

    companion object {
        const val SECONDARY_WEIGHT = 0.5

        fun of(logs: List<SetLog>, library: Map<Long, Exercise>): MuscleLoad =
            ofSets(logs.filterNot { it.skipped }.map { it.exerciseId }, library)

        /** What a planned workout will train: one entry per set (both sides of a unilateral set count once). */
        fun of(workout: CompiledWorkout, library: Map<Long, Exercise>): MuscleLoad =
            ofSets(workout.steps.filterIsInstance<WorkStep>().filter { it.side != Side.RIGHT }.map { it.exerciseId }, library)

        /** [exerciseIds] has one entry per set done or planned. */
        fun ofSets(exerciseIds: List<Long>, library: Map<Long, Exercise>): MuscleLoad {
            val sets = mutableMapOf<Muscle, Double>()
            var uncounted = 0
            exerciseIds.forEach { exerciseId ->
                val exercise = library[exerciseId]
                if (exercise == null || (exercise.primaryMuscles.isEmpty() && exercise.secondaryMuscles.isEmpty())) {
                    uncounted++
                    return@forEach
                }
                exercise.primaryMuscles.forEach { sets.merge(it, 1.0, Double::plus) }
                // A muscle tagged as both counts once, as primary.
                (exercise.secondaryMuscles - exercise.primaryMuscles).forEach { sets.merge(it, SECONDARY_WEIGHT, Double::plus) }
            }
            return MuscleLoad(sets, uncounted)
        }
    }
}

/** Totals over a stretch of time (e.g. the last 7 days). */
data class PeriodTotals(val workouts: Int, val seconds: Long, val setsDone: Int, val volumeKg: Double) {
    companion object {
        fun of(sessions: List<SessionHeader>, logs: List<SetLog>): PeriodTotals {
            val ids = sessions.map { it.id }.toSet()
            val inPeriod = logs.filter { it.sessionId in ids }
            return PeriodTotals(
                workouts = sessions.size,
                seconds = sessions.sumOf { it.durationSeconds },
                setsDone = inPeriod.count { !it.skipped },
                volumeKg = inPeriod.volumeKg(),
            )
        }
    }
}
