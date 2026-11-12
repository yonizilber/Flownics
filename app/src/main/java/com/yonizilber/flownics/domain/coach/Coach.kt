package com.yonizilber.flownics.domain.coach

import kotlinx.serialization.json.JsonObject

enum class CoachEntryKind { USER, ASSISTANT, STEP, PROPOSAL, NOTE }

enum class ProposalState { PENDING, SAVED, DECLINED }

/** What a proposal would do once the user accepts it. */
enum class ProposalKind { WORKOUT, EXERCISE, ACTIVITY, PLAN }

/** One entry of a conversation, as the screen shows it and the coach later reads it back. */
sealed interface CoachEntry {
    val id: Long
    val atMillis: Long

    data class User(override val id: Long, override val atMillis: Long, val text: String) : CoachEntry

    data class Assistant(
        override val id: Long,
        override val atMillis: Long,
        val text: String,
        val modelId: String,
        val costUsd: Double,
    ) : CoachEntry

    /** A tool the coach ran. [tool] is its name; [args] what it asked for, shown as a short line. */
    data class Step(override val id: Long, override val atMillis: Long, val tool: String, val args: JsonObject, val failed: Boolean) : CoachEntry

    /**
     * A change the coach proposed, exactly as it gave it ([args], checked again whenever it is
     * shown): a workout, an exercise (new, or changes to one) or an activity to log. Nothing is
     * saved until the user says so. [replacesWorkoutId] when a workout proposal changes a saved
     * one; [savedId] (the workout, exercise or activity) once saved; [applied] the exercise
     * fields the user kept.
     */
    data class Proposal(
        override val id: Long,
        override val atMillis: Long,
        val kind: ProposalKind,
        val args: JsonObject,
        val replacesWorkoutId: Long?,
        val state: ProposalState,
        val savedId: Long?,
        val modelId: String,
        val applied: List<String>,
    ) : CoachEntry

    /** The coach saved (or removed) a note in its memory. [undone] once the user took it back. */
    data class Note(override val id: Long, override val atMillis: Long, val noteId: Long, val text: String, val removed: Boolean, val undone: Boolean) : CoachEntry
}

/** What the user tells the coach about themselves, in their own words. Every field may be blank. */
data class CoachProfile(
    val goals: String = "",
    val limitations: String = "",
    val equipment: String = "",
    val schedule: String = "",
) {
    val isBlank: Boolean get() = goals.isBlank() && limitations.isBlank() && equipment.isBlank() && schedule.isBlank()
}

data class CoachNote(val id: Long, val text: String, val createdAtMillis: Long)

data class CoachConversation(val id: Long, val title: String, val createdAtMillis: Long, val updatedAtMillis: Long)

/** What the coach screen was opened about, passed to the coach as a reference it can look up. */
sealed interface CoachTopic {
    data class Exercise(val id: Long, val name: String) : CoachTopic
    data class Workout(val id: Long, val name: String) : CoachTopic
    data class Session(val id: Long, val name: String, val atMillis: Long) : CoachTopic
    data object History : CoachTopic
}
