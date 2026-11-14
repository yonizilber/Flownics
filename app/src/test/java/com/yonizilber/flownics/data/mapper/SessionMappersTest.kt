package com.yonizilber.flownics.data.mapper

import com.yonizilber.flownics.domain.model.SessionStatus
import com.yonizilber.flownics.domain.model.SetLog
import com.yonizilber.flownics.domain.model.SetTarget
import com.yonizilber.flownics.domain.model.WorkoutSession
import com.yonizilber.flownics.domain.workout.RestPart
import com.yonizilber.flownics.domain.workout.RestStep
import com.yonizilber.flownics.domain.workout.Side
import com.yonizilber.flownics.domain.workout.WorkStep
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionMappersTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun sessionPlanRoundTrips() {
        val session = WorkoutSession(
            id = 7,
            templateId = 3,
            workoutName = "Legs",
            blockTitles = listOf("Warm-up", ""),
            steps = listOf(
                WorkStep(1, 0, 11, 1, 3, 1, 1, SetTarget(reps = 8, weightKg = 62.5), null, null),
                RestStep(1, 90, listOf(RestPart(12, SetTarget(seconds = 30)), RestPart(13, SetTarget(reps = 8)))),
                WorkStep(1, 1, 13, 1, 1, 2, 3, SetTarget(seconds = 30), Side.LEFT, 30),
                RestStep(1, 60),
                WorkStep(2, 0, 13, 1, 1, 1, 1, SetTarget(seconds = 30), null, 30, fromRests = true, restPart = 1),
            ),
            status = SessionStatus.IN_PROGRESS,
            startedAtMillis = 1_000,
            currentIndex = 2,
            notes = "",
        )
        assertEquals(session, session.toEntity(json).toDomain(json))
    }

    @Test
    fun setLogRoundTrips() {
        val log = SetLog(
            sessionId = 7, stepIndex = 2, exerciseId = 13, exerciseName = "Side plank", blockIndex = 1,
            set = 1, round = 2, side = Side.RIGHT, actual = SetTarget(seconds = 28), skipped = false, completedAtMillis = 5,
        )
        assertEquals(log, log.toEntity().toDomain())
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownStatusFailsLoudly() {
        val entity = WorkoutSession(
            templateId = null, workoutName = "X", blockTitles = emptyList(), steps = emptyList(),
            status = SessionStatus.COMPLETED, startedAtMillis = 0,
        ).toEntity(json).copy(status = "ARCHIVED")
        entity.toDomain(json)
    }
}
