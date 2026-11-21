package com.yonizilber.flownics.navigation

import androidx.compose.material.icons.outlined.CalendarMonth
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sports
import androidx.compose.ui.graphics.vector.ImageVector
import com.yonizilber.flownics.R

sealed class Destination(val route: String) {
    data object Workouts : Destination("workouts")
    data object Library : Destination("library")
    data object History : Destination("history")
    data object Settings : Destination("settings")
    data object Me : Destination("me")

    data object ExerciseDetail : Destination("exercise/{$ARG_EXERCISE_ID}") {
        fun createRoute(id: Long) = "exercise/$id"
    }

    // id 0 means "new exercise", which can start pre-filled (e.g. from an AI-suggested variation).
    data object ExerciseEdit : Destination(
        "exercise/edit/{$ARG_EXERCISE_ID}?$ARG_PREFILL_NAME={$ARG_PREFILL_NAME}&$ARG_PREFILL_NOTES={$ARG_PREFILL_NOTES}" +
            "&$ARG_PREFILL_EQUIPMENT={$ARG_PREFILL_EQUIPMENT}&$ARG_PREFILL_CATEGORY={$ARG_PREFILL_CATEGORY}" +
            "&$ARG_PREFILL_LEVEL={$ARG_PREFILL_LEVEL}&$ARG_VARIATION_OF={$ARG_VARIATION_OF}&$ARG_VARIATION_ID={$ARG_VARIATION_ID}" +
            "&$ARG_OPEN_AI={$ARG_OPEN_AI}",
    ) {
        fun createRoute(id: Long = 0) = "exercise/edit/$id"

        /** The editor of [id] with the AI sheet open, to refine the exercise. */
        fun refine(id: Long) = "exercise/edit/$id?$ARG_OPEN_AI=true"

        /** A new exercise made from a variation kept under [variationOf]. */
        fun fromVariation(
            name: String,
            notes: String,
            equipment: String,
            category: String?,
            level: Int?,
            variationOf: Long,
            variationId: Long,
        ) = "exercise/edit/0?$ARG_PREFILL_NAME=${Uri.encode(name)}&$ARG_PREFILL_NOTES=${Uri.encode(notes)}" +
            "&$ARG_PREFILL_EQUIPMENT=${Uri.encode(equipment)}&$ARG_PREFILL_CATEGORY=${Uri.encode(category.orEmpty())}" +
            "&$ARG_PREFILL_LEVEL=${level ?: 0}&$ARG_VARIATION_OF=$variationOf&$ARG_VARIATION_ID=$variationId"
    }

    // Every exercise carrying one tag, and what the tag is for.
    data object TagExercises : Destination("tag/{$ARG_TAG}") {
        fun createRoute(tag: String) = "tag/${Uri.encode(tag)}"
    }

    // Every exercise that works one muscle.
    data object MuscleExercises : Destination("muscle/{$ARG_MUSCLE}") {
        fun createRoute(muscle: String) = "muscle/$muscle"
    }

    data object WorkoutOverview : Destination("workout/{$ARG_WORKOUT_ID}") {
        fun createRoute(id: Long) = "workout/$id"
    }

    // id 0 means "new workout".
    data object WorkoutEdit : Destination("workout/edit/{$ARG_WORKOUT_ID}") {
        fun createRoute(id: Long = 0) = "workout/edit/$id"
    }

    // One finished workout from history.
    data object SessionDetail : Destination("history/{$ARG_SESSION_ID}") {
        fun createRoute(id: Long) = "history/$id"
    }

    // An activity logged by hand (a run, a ride…); id 0 means a new one.
    data object ActivityEdit : Destination("activity/{$ARG_ACTIVITY_ID}") {
        fun createRoute(id: Long = 0) = "activity/$id"
    }

    // One planned day; id 0 is a new plan, for the day given (epoch day) or today.
    data object PlanEdit : Destination("plan/{$ARG_PLAN_ID}?$ARG_EPOCH_DAY={$ARG_EPOCH_DAY}") {
        fun createRoute(id: Long) = "plan/$id"
        fun forDay(epochDay: Long) = "plan/0?$ARG_EPOCH_DAY=$epochDay"
    }

    data object CalendarSettings : Destination("settings/calendar")

    data object GarminSettings : Destination("settings/garmin")

    data object GarminExplorer : Destination("settings/garmin/explorer")

    data object Wellness : Destination("wellness")

    data object HeartRateSensor : Destination("settings/sensor")

    data object WorkoutDefaults : Destination("settings/defaults")

    // The player shows whichever workout is running; there is at most one.
    data object Player : Destination("player")

    data object AiSettings : Destination("settings/ai")

    // Backup, restore and sharing files with friends.
    data object Data : Destination("settings/data")

    // What to share; whatever it was opened from starts ticked.
    data object Share : Destination("share?$ARG_EXERCISE_ID={$ARG_EXERCISE_ID}&$ARG_WORKOUT_ID={$ARG_WORKOUT_ID}") {
        fun choose() = "share"
        fun exercise(id: Long) = "share?$ARG_EXERCISE_ID=$id"
        fun workout(id: Long) = "share?$ARG_WORKOUT_ID=$id"
    }

    data object AiActivity : Destination("settings/ai/activity")

    data object AiExerciseVariations : Destination("ai/variations/{$ARG_EXERCISE_ID}") {
        fun createRoute(exerciseId: Long) = "ai/variations/$exerciseId"
    }

    data object AiWorkoutReview : Destination("ai/review/{$ARG_WORKOUT_ID}") {
        fun createRoute(workoutId: Long) = "ai/review/$workoutId"
    }

    // workoutId 0: a new workout from scratch; otherwise a variation of that workout.
    data object AiWorkoutGenerate : Destination("ai/generate/{$ARG_WORKOUT_ID}") {
        fun createRoute(workoutId: Long = 0) = "ai/generate/$workoutId"
    }

    // The coach. From the tab: the latest conversation. With a topic (an exercise, a workout, a
    // finished session, the history): a new conversation about it.
    data object Coach : Destination(
        "coach?$ARG_EXERCISE_ID={$ARG_EXERCISE_ID}&$ARG_WORKOUT_ID={$ARG_WORKOUT_ID}&$ARG_SESSION_ID={$ARG_SESSION_ID}&$ARG_HISTORY={$ARG_HISTORY}",
    ) {
        const val TAB = "coach"
        fun aboutExercise(id: Long) = "coach?$ARG_EXERCISE_ID=$id"
        fun aboutWorkout(id: Long) = "coach?$ARG_WORKOUT_ID=$id"
        fun aboutSession(id: Long) = "coach?$ARG_SESSION_ID=$id"
        fun aboutHistory() = "coach?$ARG_HISTORY=true"
    }

    // What the coach knows: the user's profile and the coach's notes.
    data object CoachMemory : Destination("coach/memory")

    companion object {
        const val ARG_EXERCISE_ID = "exerciseId"
        const val ARG_WORKOUT_ID = "workoutId"
        const val ARG_SESSION_ID = "sessionId"
        const val ARG_ACTIVITY_ID = "activityId"
        const val ARG_PLAN_ID = "planId"
        const val ARG_EPOCH_DAY = "epochDay"
        const val ARG_HISTORY = "history"
        const val ARG_PREFILL_NAME = "name"
        const val ARG_PREFILL_NOTES = "notes"
        const val ARG_PREFILL_EQUIPMENT = "equipment"
        const val ARG_PREFILL_CATEGORY = "category"
        const val ARG_PREFILL_LEVEL = "level"
        const val ARG_VARIATION_OF = "variationOf"
        const val ARG_VARIATION_ID = "variationId"
        const val ARG_OPEN_AI = "openAi"
        const val ARG_MUSCLE = "muscle"
        const val ARG_TAG = "tag"
    }
}

enum class TopLevelTab(
    val destination: Destination,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    /** Where tapping the tab goes (a route without arguments). */
    val target: String = destination.route,
) {
    WORKOUTS(Destination.Workouts, R.string.nav_workouts, Icons.AutoMirrored.Outlined.PlaylistPlay),
    LIBRARY(Destination.Library, R.string.nav_library, Icons.Outlined.FitnessCenter),
    COACH(Destination.Coach, R.string.nav_coach, Icons.Outlined.Sports, Destination.Coach.TAB),
    HISTORY(Destination.History, R.string.nav_journal, Icons.Outlined.CalendarMonth),
    // Data about the user (Garmin, load, later the scale); settings open from its top bar.
    ME(Destination.Me, R.string.nav_me, Icons.Outlined.Person),
}
