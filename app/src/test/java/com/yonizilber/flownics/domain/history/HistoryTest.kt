package com.yonizilber.flownics.domain.history

import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.Muscle
import com.yonizilber.flownics.domain.model.SessionHeader
import com.yonizilber.flownics.domain.model.SetLog
import com.yonizilber.flownics.domain.model.SetTarget
import com.yonizilber.flownics.domain.model.SetPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryTest {

    private var nextStep = 0

    private fun log(
        session: Long,
        reps: Int? = null,
        kg: Double? = null,
        seconds: Int? = null,
        skipped: Boolean = false,
        exerciseId: Long = 1,
        at: Long = session * 1_000,
    ) = SetLog(
        sessionId = session, stepIndex = nextStep++, exerciseId = exerciseId, exerciseName = "Squat", blockIndex = 0,
        set = 1, round = 1, side = null, actual = SetTarget(reps = reps, seconds = seconds, weightKg = kg),
        skipped = skipped, completedAtMillis = at,
    )

    @Test
    fun volumeCountsOnlyDoneSetsWithBothNumbers() {
        val logs = listOf(log(1, reps = 10, kg = 40.0), log(1, reps = 8, kg = 42.5), log(1, reps = 10), log(1, reps = 5, kg = 100.0, skipped = true))
        assertEquals(740.0, logs.volumeKg(), 1e-9)
    }

    @Test
    fun aDropSetAddsItsWeightedPartsToVolumeButRecordsUseTheMainPart() {
        val drop = log(1, reps = 8, kg = 20.0).let { it.copy(actual = it.actual.copy(more = listOf(SetPart(6, 12.0), SetPart(5)))) }
        // 8 × 20 + 6 × 12 + 5 × nothing.
        assertEquals(232.0, listOf(drop).volumeKg(), 1e-9)
        assertEquals(232.0, ProgressMetric.VOLUME.of(listOf(drop))!!, 1e-9)
        assertEquals(20.0, ProgressMetric.WEIGHT.of(listOf(drop))!!, 1e-9)
        assertEquals(8.0, ProgressMetric.REPS.of(listOf(drop))!!, 1e-9)
    }

    @Test
    fun metricsTakeEachWorkoutsBestAndIgnoreSkippedSets() {
        val sets = listOf(log(1, reps = 12, kg = 40.0), log(1, reps = 6, kg = 50.0), log(1, reps = 20, kg = 90.0, skipped = true))
        assertEquals(50.0, ProgressMetric.WEIGHT.of(sets)!!, 1e-9)
        assertEquals(780.0, ProgressMetric.VOLUME.of(sets)!!, 1e-9)
        assertEquals(12.0, ProgressMetric.REPS.of(sets)!!, 1e-9)
        assertNull(ProgressMetric.SECONDS.of(sets))
        assertNull(ProgressMetric.VOLUME.of(listOf(log(1, reps = 10))))
    }

    @Test
    fun sessionsAreOrderedByWhenTheyWereDone() {
        val logs = listOf(log(2, reps = 5, at = 50), log(1, reps = 5, at = 90), log(2, reps = 6, at = 60))
        val sessions = ExerciseProgress.bySession(logs)
        assertEquals(listOf(2L, 1L), sessions.map { it.sessionId })
        assertEquals(50L, sessions.first().atMillis)
        assertEquals(listOf(ProgressMetric.REPS), ExerciseProgress.availableMetrics(sessions))
    }

    @Test
    fun aRecordNeedsSomethingEarlierToBeat() {
        val sessions = ExerciseProgress.bySession(
            listOf(
                log(1, reps = 8, kg = 40.0),
                log(2, reps = 8, kg = 40.0),
                log(3, reps = 5, kg = 45.0),
                log(4, reps = 12, kg = 30.0),
            ),
        )
        // The first time is not a record, and matching the best isn't either.
        assertEquals(emptySet<ProgressMetric>(), ExerciseProgress.newRecords(sessions, 1))
        assertEquals(emptySet<ProgressMetric>(), ExerciseProgress.newRecords(sessions, 2))
        assertEquals(setOf(ProgressMetric.WEIGHT), ExerciseProgress.newRecords(sessions, 3))
        assertEquals(setOf(ProgressMetric.REPS, ProgressMetric.VOLUME), ExerciseProgress.newRecords(sessions, 4))
        assertEquals(emptySet<ProgressMetric>(), ExerciseProgress.newRecords(sessions, 99))
    }

    @Test
    fun recordsPointAtTheFirstTimeAndTheBestSet() {
        val sessions = ExerciseProgress.bySession(
            listOf(log(1, reps = 3, kg = 60.0), log(1, reps = 5, kg = 60.0), log(2, reps = 8, kg = 60.0)),
        )
        val weight = ExerciseProgress.records(sessions).first { it.metric == ProgressMetric.WEIGHT }
        assertEquals(1L, weight.sessionId)
        assertEquals(5, weight.set!!.actual.reps)
        val reps = ExerciseProgress.records(sessions).first { it.metric == ProgressMetric.REPS }
        assertEquals(2L, reps.sessionId)
    }

    @Test
    fun muscleLoadWeighsSecondaryHalfAndReportsWhatItCannotCount() {
        val squat = Exercise(
            id = 1, name = "Squat", category = ExerciseCategory.STRENGTH,
            primaryMuscles = setOf(Muscle.QUADS, Muscle.GLUTES), secondaryMuscles = setOf(Muscle.HAMSTRINGS, Muscle.GLUTES),
        )
        val untagged = Exercise(id = 2, name = "Mystery", category = ExerciseCategory.STRENGTH)
        val logs = listOf(
            log(1, reps = 5, exerciseId = 1),
            log(1, reps = 5, exerciseId = 1),
            log(1, reps = 5, exerciseId = 1, skipped = true),
            log(1, reps = 5, exerciseId = 2),
            log(1, reps = 5, exerciseId = 3),
        )
        val load = MuscleLoad.of(logs, mapOf(1L to squat, 2L to untagged))
        assertEquals(mapOf(Muscle.QUADS to 2.0, Muscle.GLUTES to 2.0, Muscle.HAMSTRINGS to 1.0), load.sets)
        assertEquals(2, load.uncountedSets)
        assertEquals(listOf(Muscle.GLUTES, Muscle.QUADS, Muscle.HAMSTRINGS), load.ranked.map { it.first })
        assertEquals(Muscle.entries.size - 3, load.untouched.size)
    }

    @Test
    fun periodTotalsOnlyUseTheGivenSessions() {
        val headers = listOf(SessionHeader(1, null, "A", startedAtMillis = 0, endedAtMillis = 1_800_000, notes = ""))
        val logs = listOf(log(1, reps = 10, kg = 20.0), log(1, reps = 10, skipped = true), log(2, reps = 10, kg = 99.0))
        assertEquals(PeriodTotals(workouts = 1, seconds = 1_800, setsDone = 1, volumeKg = 200.0), PeriodTotals.of(headers, logs))
    }
}
