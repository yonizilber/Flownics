package com.yonizilber.flownics.domain.player

import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.SetLog
import com.yonizilber.flownics.domain.model.SetTarget
import com.yonizilber.flownics.domain.workout.RestStep
import com.yonizilber.flownics.domain.workout.Side
import com.yonizilber.flownics.domain.workout.WorkStep
import com.yonizilber.flownics.domain.workout.WorkoutStep

/**
 * All times are monotonic milliseconds (SystemClock.elapsedRealtime on the device): they keep
 * counting while the screen is off and don't jump when the wall clock changes. Remaining time is
 * always derived from a stored deadline, never by counting ticks, so a late tick can't drift it.
 */
sealed interface Phase {
    /** "Get ready" before a timed step that doesn't follow a rest: first step, after a manual set, switching sides. */
    data class LeadIn(val endsAtMs: Long) : Phase

    /** [endsAtMs] is null for open-ended steps (a set of reps): they wait for the user. */
    data class Running(val startedAtMs: Long, val endsAtMs: Long?) : Phase

    data class Paused(val frozen: Phase, val pausedAtMs: Long) : Phase

    data object Finished : Phase
}

/** [index] is the current step; when finished it is where the workout ended (steps.size if completed). */
data class PlayerState(val index: Int, val phase: Phase)

/** The player state machine. Pure: every function takes the current time and returns a new state. */
object WorkoutPlayer {
    const val LEAD_IN_MS = 5_000L
    private const val MIN_LEFT_AFTER_ADJUST_MS = 1_000L

    fun start(steps: List<WorkoutStep>, nowMs: Long): PlayerState = enter(steps, 0, nowMs, afterRest = false)

    /** After the app was closed mid-workout: back at the same step, from its beginning, paused. */
    fun restore(steps: List<WorkoutStep>, index: Int, nowMs: Long): PlayerState =
        pause(enter(steps, index.coerceIn(0, steps.size), nowMs, afterRest = false), nowMs)

    /** Runs every deadline passed by [nowMs]; each next step starts exactly when the previous one ended. */
    fun tick(state: PlayerState, steps: List<WorkoutStep>, nowMs: Long): PlayerState {
        var s = state
        while (true) {
            val phase = s.phase
            s = when {
                phase is Phase.LeadIn && nowMs >= phase.endsAtMs -> {
                    val duration = checkNotNull(steps[s.index].durationSeconds) * 1000L
                    s.copy(phase = Phase.Running(phase.endsAtMs, phase.endsAtMs + duration))
                }
                phase is Phase.Running && phase.endsAtMs != null && nowMs >= phase.endsAtMs ->
                    enter(steps, s.index + 1, phase.endsAtMs, afterRest = steps[s.index] is RestStep)
                else -> return s
            }
        }
    }

    /** The user finished or skipped the current step. A paused player stays paused on the next step. */
    fun advance(state: PlayerState, steps: List<WorkoutStep>, nowMs: Long): PlayerState {
        if (state.phase is Phase.Finished) return state
        val next = enter(steps, state.index + 1, nowMs, afterRest = steps[state.index] is RestStep)
        return if (state.phase is Phase.Paused) pause(next, nowMs) else next
    }

    /** One step back. From the end screen, returns to where the workout was ended. */
    fun previous(state: PlayerState, steps: List<WorkoutStep>, nowMs: Long): PlayerState {
        if (steps.isEmpty()) return state
        val target = when {
            state.phase is Phase.Finished -> state.index.coerceAtMost(steps.lastIndex)
            else -> (state.index - 1).coerceAtLeast(0)
        }
        val entered = enter(steps, target, nowMs, afterRest = false)
        return if (state.phase is Phase.Paused) pause(entered, nowMs) else entered
    }

    /** Ends the workout here; logged sets stay, the rest is simply not done. */
    fun end(state: PlayerState): PlayerState = state.copy(phase = Phase.Finished)

    fun pause(state: PlayerState, nowMs: Long): PlayerState = when (val p = state.phase) {
        is Phase.LeadIn, is Phase.Running -> state.copy(phase = Phase.Paused(p, nowMs))
        else -> state
    }

    fun resume(state: PlayerState, nowMs: Long): PlayerState {
        val paused = state.phase as? Phase.Paused ?: return state
        val shift = nowMs - paused.pausedAtMs
        val resumed = when (val f = paused.frozen) {
            is Phase.LeadIn -> f.copy(endsAtMs = f.endsAtMs + shift)
            is Phase.Running -> Phase.Running(f.startedAtMs + shift, f.endsAtMs?.plus(shift))
            else -> f
        }
        return state.copy(phase = resumed)
    }

    /** Adds (or removes, when negative) time on the current countdown, leaving at least a second. */
    fun adjust(state: PlayerState, deltaSeconds: Int, nowMs: Long): PlayerState {
        fun shifted(endsAt: Long) = maxOf(endsAt + deltaSeconds * 1000L, nowMs + MIN_LEFT_AFTER_ADJUST_MS)
        return when (val p = state.phase) {
            is Phase.LeadIn -> state.copy(phase = p.copy(endsAtMs = shifted(p.endsAtMs)))
            is Phase.Running -> p.endsAtMs?.let { state.copy(phase = p.copy(endsAtMs = shifted(it))) } ?: state
            is Phase.Paused -> {
                val frozen = adjust(PlayerState(state.index, p.frozen), deltaSeconds, p.pausedAtMs).phase
                state.copy(phase = p.copy(frozen = frozen))
            }
            Phase.Finished -> state
        }
    }

    /** The next moment something happens on its own, or null when the player waits for the user. */
    fun nextDeadline(phase: Phase): Long? = when (phase) {
        is Phase.LeadIn -> phase.endsAtMs
        is Phase.Running -> phase.endsAtMs
        else -> null
    }

    /** Countdown left, or null for an open-ended step. */
    fun remainingMs(phase: Phase, nowMs: Long): Long? = when (phase) {
        is Phase.LeadIn -> (phase.endsAtMs - nowMs).coerceAtLeast(0)
        is Phase.Running -> phase.endsAtMs?.let { (it - nowMs).coerceAtLeast(0) }
        is Phase.Paused -> remainingMs(phase.frozen, phase.pausedAtMs)
        Phase.Finished -> null
    }

    /** Time spent in the current step (0 during the lead-in). */
    fun elapsedMs(phase: Phase, nowMs: Long): Long = when (phase) {
        is Phase.Running -> (nowMs - phase.startedAtMs).coerceAtLeast(0)
        is Phase.Paused -> elapsedMs(phase.frozen, phase.pausedAtMs)
        else -> 0
    }

    /** Full length of the current countdown, for progress rings; null when open-ended. */
    fun totalMs(phase: Phase): Long? = when (phase) {
        is Phase.LeadIn -> LEAD_IN_MS
        is Phase.Running -> phase.endsAtMs?.let { it - phase.startedAtMs }
        is Phase.Paused -> totalMs(phase.frozen)
        Phase.Finished -> null
    }

    private fun enter(steps: List<WorkoutStep>, index: Int, nowMs: Long, afterRest: Boolean): PlayerState {
        val step = steps.getOrNull(index) ?: return PlayerState(steps.size, Phase.Finished)
        val durationMs = step.durationSeconds?.let { it * 1000L }
        return if (step is WorkStep && durationMs != null && !afterRest) {
            PlayerState(index, Phase.LeadIn(nowMs + LEAD_IN_MS))
        } else {
            PlayerState(index, Phase.Running(nowMs, durationMs?.let { nowMs + it }))
        }
    }
}

/** One timed stage of an exercise, placed on the step's clock. */
data class StageSegment(val stageIndex: Int, val side: Side?, val startSeconds: Int, val durationSeconds: Int)

object StageTimeline {
    /**
     * The exercise's timed stages in order. With [bothSides] (a one-sided exercise done as active
     * rest) all stages run on the left, then again on the right. Untimed stages are left out.
     */
    fun of(exercise: Exercise, bothSides: Boolean): List<StageSegment> {
        val sides = if (bothSides && exercise.unilateral) listOf(Side.LEFT, Side.RIGHT) else listOf(null)
        var start = 0
        return sides.flatMap { side ->
            exercise.stages.mapIndexedNotNull { index, stage ->
                val duration = stage.durationSeconds?.takeIf { it > 0 } ?: return@mapIndexedNotNull null
                StageSegment(index, side, start, duration).also { start += duration }
            }
        }
    }

    /** The segment running at [elapsedSeconds], or null before the first or after the last. */
    fun at(timeline: List<StageSegment>, elapsedSeconds: Int): StageSegment? =
        timeline.firstOrNull { elapsedSeconds >= it.startSeconds && elapsedSeconds < it.startSeconds + it.durationSeconds }
}

object SetDefaults {
    /**
     * What the log fields show before the user touches them: what was already logged for this step
     * (when going back), otherwise the plan, with the weight carried over from an earlier set of the
     * same exercise today (so a change on set 1 sticks for set 2), then from the last workout.
     */
    fun forStep(step: WorkStep, stepIndex: Int, logs: Map<Int, SetLog>, lastTime: SetLog?): SetTarget {
        logs[stepIndex]?.takeUnless { it.skipped }?.let { return it.actual }
        val earlier = logs.values
            .filter { it.exerciseId == step.exerciseId && !it.skipped && it.stepIndex < stepIndex }
            .maxByOrNull { it.stepIndex }
            ?.actual
        val last = lastTime?.actual
        val target = step.target
        return SetTarget(
            reps = target.reps ?: earlier?.reps ?: last?.reps,
            seconds = target.seconds,
            weightKg = earlier?.weightKg ?: target.weightKg ?: last?.weightKg,
            distanceMeters = target.distanceMeters ?: earlier?.distanceMeters ?: last?.distanceMeters,
        )
    }
}
