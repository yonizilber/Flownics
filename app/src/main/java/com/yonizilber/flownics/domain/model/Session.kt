package com.yonizilber.flownics.domain.model

import com.yonizilber.flownics.domain.workout.Side
import com.yonizilber.flownics.domain.workout.WorkoutStep

enum class SessionStatus { IN_PROGRESS, COMPLETED }

/**
 * One time a workout was (or is being) done. [steps] and [blockTitles] are a frozen copy of the
 * plan at start, so editing or deleting the template later never rewrites history.
 */
data class WorkoutSession(
    val id: Long = 0,
    val templateId: Long?,
    val workoutName: String,
    val blockTitles: List<String>,
    val steps: List<WorkoutStep>,
    val status: SessionStatus,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val currentIndex: Int = 0,
    val notes: String = "",
)

/** What was actually done in one work step. [actual] uses the same fields as the plan's target. */
data class SetLog(
    val sessionId: Long,
    val stepIndex: Int,
    val exerciseId: Long,
    /** Snapshot, so history still reads right after the exercise is renamed or deleted. */
    val exerciseName: String,
    val blockIndex: Int,
    val set: Int,
    val round: Int,
    val side: Side?,
    val actual: SetTarget,
    val skipped: Boolean,
    val completedAtMillis: Long,
    /** When the set began (v11; null for older logs). With [completedAtMillis], its exact time on a watch recording. */
    val startedAtMillis: Long? = null,
)

/** A finished session without its frozen plan: what history lists need. */
data class SessionHeader(
    val id: Long,
    val templateId: Long?,
    val workoutName: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val notes: String,
) {
    val durationSeconds: Long get() = ((endedAtMillis - startedAtMillis) / 1000).coerceAtLeast(0)
}
