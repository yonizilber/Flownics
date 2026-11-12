package com.yonizilber.flownics.domain.plan

import com.yonizilber.flownics.domain.model.Activity
import com.yonizilber.flownics.domain.model.ActivitySegment
import com.yonizilber.flownics.domain.model.PlanKind
import com.yonizilber.flownics.domain.model.PlannedStep
import com.yonizilber.flownics.domain.model.PlannedWorkout
import com.yonizilber.flownics.domain.model.SessionHeader
import java.time.Instant
import java.time.ZoneId

/** Something done that fulfils a plan: a workout from the player, or a logged activity. */
sealed interface DoneItem {
    val atMillis: Long

    data class Workout(val session: SessionHeader) : DoneItem {
        override val atMillis get() = session.startedAtMillis
    }

    data class Logged(val activity: Activity) : DoneItem {
        override val atMillis get() = activity.startedAtMillis
    }
}

/** How one planned part went. [OK] means within its targets, as far as they could be checked. */
enum class StepVerdict { OK, FASTER, SLOWER, HR_HIGH, NOT_DONE, NO_TARGET }

data class StepComparison(val planned: PlannedStep, val actual: ActivitySegment?, val verdict: StepVerdict)

/**
 * Which plans were done: on the same day, a session of the planned workout, or an activity of the
 * planned type. Nothing is stored: a plan counts as done as soon as the matching thing is in the
 * history, and stops if it is deleted. Pure, so it is unit-tested.
 */
object PlanMatching {

    fun match(plans: List<PlannedWorkout>, sessions: List<SessionHeader>, activities: List<Activity>, zone: ZoneId): Map<Long, DoneItem> {
        val done = (sessions.map { DoneItem.Workout(it) } + activities.map { DoneItem.Logged(it) }).sortedBy { it.atMillis }
        val used = mutableSetOf<DoneItem>()
        val result = mutableMapOf<Long, DoneItem>()
        plans.sortedWith(compareBy({ it.date }, { it.time })).forEach { plan ->
            val found = done.firstOrNull { d ->
                d !in used && Instant.ofEpochMilli(d.atMillis).atZone(zone).toLocalDate() == plan.date && fits(plan, d)
            }
            if (found != null) {
                used += found
                result[plan.id] = found
            }
        }
        return result
    }

    private fun fits(plan: PlannedWorkout, done: DoneItem) = when (plan.kind) {
        PlanKind.WORKOUT -> done is DoneItem.Workout && plan.workoutId != null && done.session.templateId == plan.workoutId
        PlanKind.ACTIVITY -> done is DoneItem.Logged && done.activity.type == plan.activityType
    }

    /**
     * The plan's parts beside what was done, kind by kind in order: the 1st planned repeat with the
     * 1st repeat done, and so on, so a missing or extra repeat doesn't shift the rest. A pace
     * within [PACE_SLACK_SECONDS] of the range counts as on target.
     */
    fun compare(steps: List<PlannedStep>, segments: List<ActivitySegment>): List<StepComparison> {
        val byKind = segments.groupBy { it.kind }.mapValues { it.value.toMutableList() }
        return steps.map { step ->
            val actual = byKind[step.kind]?.removeFirstOrNull()
            StepComparison(step, actual, verdict(step, actual))
        }
    }

    private fun verdict(step: PlannedStep, actual: ActivitySegment?): StepVerdict {
        if (actual == null) return StepVerdict.NOT_DONE
        val hr = actual.avgHeartRate
        if (step.maxHeartRate != null && hr != null && hr > step.maxHeartRate) return StepVerdict.HR_HIGH
        val pace = actual.paceSecondsPerKm
        val fast = step.paceFastSecondsPerKm
        val slow = step.paceSlowSecondsPerKm
        if (pace == null || (fast == null && slow == null)) return if (step.maxHeartRate != null && hr != null) StepVerdict.OK else StepVerdict.NO_TARGET
        if (fast != null && pace < fast - PACE_SLACK_SECONDS) return StepVerdict.FASTER
        if (slow != null && pace > slow + PACE_SLACK_SECONDS) return StepVerdict.SLOWER
        return StepVerdict.OK
    }

    const val PACE_SLACK_SECONDS = 3
}
