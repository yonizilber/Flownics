package com.yonizilber.flownics.player

import android.content.res.Resources
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.MeasureMode
import com.yonizilber.flownics.domain.model.SetTarget
import com.yonizilber.flownics.domain.player.Phase
import com.yonizilber.flownics.domain.workout.RestStep
import com.yonizilber.flownics.domain.workout.Side
import com.yonizilber.flownics.domain.workout.WorkStep
import com.yonizilber.flownics.ui.component.formatClock
import com.yonizilber.flownics.ui.component.formatWeight
import com.yonizilber.flownics.ui.component.progressText
import com.yonizilber.flownics.ui.component.targetText

/** Words for the notification and the voice, from the app's own (per-app language) resources. */
class PlayerTexts(private val res: Resources) {

    fun exerciseName(id: Long, library: Map<Long, Exercise>): String =
        library[id]?.name ?: res.getString(R.string.wedit_deleted_exercise)

    /** Notification title: what to do right now. */
    fun title(workout: ActiveWorkout, library: Map<Long, Exercise>): String {
        if (workout.player.phase is Phase.Finished) return res.getString(R.string.notif_finished)
        return when (val step = workout.step) {
            is WorkStep -> exerciseName(step.exerciseId, library) + sideSuffix(step.side)
            is RestStep -> step.activeExerciseId
                ?.let { res.getString(R.string.notif_active_rest, exerciseName(it, library)) }
                ?: res.getString(R.string.player_rest)
            null -> workout.session.workoutName
        }
    }

    /** Notification body: set and target for work, what comes next for rest. */
    fun detail(workout: ActiveWorkout, library: Map<Long, Exercise>): String {
        val phase = workout.player.phase
        if (phase is Phase.Finished) return res.getString(R.string.notif_finished_body)
        val text = when (val step = workout.step) {
            is WorkStep -> listOfNotNull(
                res.getString(R.string.player_get_ready).takeIf { phase is Phase.LeadIn },
                progressText(res, step),
                targetText(res, step.target, library[step.exerciseId]),
            ).joinToString(" · ")
            is RestStep -> workout.nextWork()
                ?.let { res.getString(R.string.notif_next, exerciseName(it.exerciseId, library)) }
                .orEmpty()
            null -> ""
        }
        return if (phase is Phase.Paused) res.getString(R.string.notif_paused, text) else text
    }

    /** Spoken when a step begins; null when there is nothing worth saying. */
    fun announcement(workout: ActiveWorkout, library: Map<Long, Exercise>): String? {
        if (workout.player.phase is Phase.Finished) {
            return res.getString(R.string.say_done).takeIf { workout.player.index >= workout.session.steps.size }
        }
        return when (val step = workout.step) {
            is WorkStep -> {
                val exercise = library[step.exerciseId]
                listOfNotNull(
                    res.getString(R.string.say_get_ready).takeIf { workout.player.phase is Phase.LeadIn },
                    exerciseName(step.exerciseId, library) + spokenSide(step.side) + ".",
                    res.getString(R.string.say_set, step.set, step.totalSets).takeIf { step.totalSets > 1 },
                    res.getString(R.string.say_round, step.round, step.totalRounds).takeIf { step.totalRounds > 1 },
                    spokenTarget(step.target, exercise),
                ).joinToString(" ")
            }
            is RestStep -> {
                val rest = step.activeExerciseId
                    ?.let { res.getString(R.string.say_active_rest, exerciseName(it, library)) }
                    ?: res.getString(R.string.say_rest, step.durationSeconds)
                val next = workout.nextWork()?.let { res.getString(R.string.say_next, exerciseName(it.exerciseId, library)) }
                listOfNotNull(rest, next).joinToString(" ")
            }
            null -> null
        }
    }

    private fun sideSuffix(side: Side?): String = when (side) {
        Side.LEFT -> " (${res.getString(R.string.side_left)})"
        Side.RIGHT -> " (${res.getString(R.string.side_right)})"
        null -> ""
    }

    private fun spokenSide(side: Side?): String = when (side) {
        Side.LEFT -> ", " + res.getString(R.string.say_left)
        Side.RIGHT -> ", " + res.getString(R.string.say_right)
        null -> ""
    }

    // Spelled out for the voice: "30 seconds" reads better than "0:30".
    private fun spokenTarget(target: SetTarget, exercise: Exercise?): String? {
        val parts = buildList {
            when (exercise?.measureMode ?: MeasureMode.REPS) {
                MeasureMode.REPS -> target.reps?.let { add(res.getString(R.string.say_reps, it)) }
                MeasureMode.REPS_WEIGHT -> {
                    target.reps?.let { add(res.getString(R.string.say_reps, it)) }
                    target.weightKg?.let { add(res.getString(R.string.say_weight, formatWeight(it))) }
                }
                MeasureMode.TIME -> target.seconds?.let {
                    add(if (it < 60) res.getString(R.string.say_seconds, it) else formatClock(it))
                }
                MeasureMode.DISTANCE -> target.distanceMeters?.let { add(res.getString(R.string.say_meters, it)) }
            }
        }
        return parts.joinToString(", ").ifEmpty { null }?.plus(".")
    }
}
