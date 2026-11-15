package com.yonizilber.flownics.data.transfer

import com.yonizilber.flownics.data.local.db.entity.PlannedStepJson
import com.yonizilber.flownics.data.local.db.entity.SegmentJson
import com.yonizilber.flownics.data.local.db.entity.BlockJson
import com.yonizilber.flownics.data.local.db.entity.MediaJson
import com.yonizilber.flownics.data.local.db.entity.SessionPlanJson
import com.yonizilber.flownics.data.local.db.entity.StageJson
import com.yonizilber.flownics.data.local.db.entity.TagJson
import kotlinx.serialization.Serializable

/*
 * The Flownics file: a zip holding manifest.json, then data.json, then media/<file> for each photo
 * or video the data refers to. These classes are the file format, separate from the database, so
 * a schema change in the app doesn't silently change what older or newer copies can read. Field
 * names here are a compatibility contract; add fields with defaults, and bump FORMAT_VERSION for
 * anything an older reader would misunderstand.
 */

const val BUNDLE_FORMAT = "flownics"
const val BUNDLE_FORMAT_VERSION = 1

/** What a file is for: [SHARE] merges into someone's library; [BACKUP] replaces everything on restore. */
enum class BundleKind { SHARE, BACKUP }

@Serializable
data class BundleManifest(
    val format: String = BUNDLE_FORMAT,
    val formatVersion: Int = BUNDLE_FORMAT_VERSION,
    val kind: BundleKind,
    val createdAtMillis: Long,
    val appVersion: String,
)

@Serializable
data class BundleData(
    val exercises: List<ExerciseDto> = emptyList(),
    val variations: List<VariationDto> = emptyList(),
    val aiNotes: List<AiNoteDto> = emptyList(),
    val workouts: List<WorkoutDto> = emptyList(),
    // Backups only: training history and the AI spend log are personal and never shared.
    val sessions: List<SessionDto> = emptyList(),
    val aiUsage: List<AiUsageDto> = emptyList(),
    // Backups only, added with DB v8 (older files simply have none).
    val activities: List<ActivityDto> = emptyList(),
    val coachConversations: List<CoachConversationDto> = emptyList(),
    val coachNotes: List<CoachNoteDto> = emptyList(),
    val coachProfile: CoachProfileDto? = null,
    // Added with DB v9. Calendar links are not here: they belong to this phone's calendar.
    val plans: List<PlanDto> = emptyList(),
    // Added with DB v18: what the tags of these exercises are for (shared too: it explains them).
    val tagInfos: List<TagInfoDto> = emptyList(),
)

@Serializable
data class PlanDto(
    val id: Long,
    val date: String,
    val time: String? = null,
    val kind: String,
    val workoutId: Long? = null,
    val activityType: String? = null,
    val title: String = "",
    val durationMinutes: Int? = null,
    val notes: String = "",
    val steps: List<PlannedStepJson> = emptyList(),
    val status: String = "PLANNED",
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
)

@Serializable
data class ActivityDto(
    val id: Long,
    val type: String,
    val title: String = "",
    val startedAtMillis: Long,
    val durationSeconds: Int,
    val distanceMeters: Int? = null,
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    val elevationGainMeters: Int? = null,
    val rpe: Int? = null,
    val notes: String = "",
    val segments: List<SegmentJson> = emptyList(),
    val source: String = "MANUAL",
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
    val externalId: String? = null,
    /** ActivityMetrics as the app stores it (JSON text). */
    val metricsJson: String? = null,
    val attachedSessionId: Long? = null,
    val keepSeparate: Boolean = false,
)

@Serializable
data class CoachConversationDto(
    val id: Long,
    val title: String = "",
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val messages: List<CoachMessageDto> = emptyList(),
)

/** [dataJson] is kept as the app wrote it; ids in it stay valid because a restore keeps ids. */
@Serializable
data class CoachMessageDto(val atMillis: Long, val kind: String, val text: String = "", val dataJson: String = "{}")

@Serializable
data class CoachNoteDto(val id: Long, val text: String, val createdAtMillis: Long)

@Serializable
data class CoachProfileDto(
    val goals: String = "",
    val limitations: String = "",
    val equipment: String = "",
    val schedule: String = "",
    val maxHeartRate: Int? = null,
    val restingHeartRate: Int? = null,
)

/** Ids are the sender's; an import gives everything new ids and rewires the references. */
@Serializable
data class ExerciseDto(
    val id: Long,
    val name: String,
    val category: String,
    val extraCategories: List<String> = emptyList(),
    val primaryMuscles: List<String> = emptyList(),
    val secondaryMuscles: List<String> = emptyList(),
    val equipment: String = "",
    val instructions: List<String> = emptyList(),
    val cues: List<String> = emptyList(),
    val stages: List<StageJson> = emptyList(),
    /** For IMAGE and VIDEO, location is a file name under media/ in the same zip; for LINK, a URL. */
    val media: List<MediaJson> = emptyList(),
    val measureMode: String,
    val unilateral: Boolean = false,
    val level: Int? = null,
    val variationOf: Long? = null,
    val notes: String = "",
    // Added with DB v18; older files have none.
    val tags: List<TagJson> = emptyList(),
    val general: Boolean = false,
    val quickAdded: Boolean = false,
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
)

/** What a tag is for (DB v18). */
@Serializable
data class TagInfoDto(val name: String, val description: String)

@Serializable
data class VariationDto(
    val exerciseId: Long,
    val name: String,
    val kind: String,
    val difference: String = "",
    val equipment: String = "",
    val category: String = "",
    val why: String = "",
    val level: Int? = null,
    val modelId: String = "",
    val atMillis: Long = 0,
    val addedExerciseId: Long? = null,
)

@Serializable
data class SourceDto(val title: String, val url: String)

@Serializable
data class AiNoteDto(
    val exerciseId: Long,
    val atMillis: Long,
    val modelId: String,
    val summary: String = "",
    val applied: List<String> = emptyList(),
    val sources: List<SourceDto> = emptyList(),
)

@Serializable
data class WorkoutDto(
    val id: Long,
    val name: String,
    val description: String = "",
    val blocks: List<BlockJson> = emptyList(),
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
    /** The workout (in the same file) whose exercises fill the rests, if any. */
    val restFillerWorkoutId: Long? = null,
    val restFillerLeftovers: Boolean = true,
    val restFillerRepeatSets: Boolean = false,
    val restFillerRoundsTogether: Boolean = true,
    val confirmActiveRest: Boolean = true,
)

@Serializable
data class SessionDto(
    val id: Long,
    val templateId: Long? = null,
    val workoutName: String,
    val plan: SessionPlanJson,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val notes: String = "",
    val logs: List<SetLogDto> = emptyList(),
)

@Serializable
data class SetLogDto(
    val stepIndex: Int,
    val exerciseId: Long,
    val exerciseName: String,
    val blockIndex: Int,
    val setNumber: Int,
    val round: Int,
    val side: String? = null,
    val reps: Int? = null,
    val seconds: Int? = null,
    val weightKg: Double? = null,
    val distanceMeters: Int? = null,
    val skipped: Boolean = false,
    val completedAtMillis: Long,
    val startedAtMillis: Long? = null,
)

@Serializable
data class AiUsageDto(
    val atMillis: Long,
    val feature: String,
    val modelId: String,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val webSearches: Long = 0,
    val costUsd: Double = 0.0,
    val subject: String = "",
    val durationMs: Long = 0,
    val outcome: String = "OK",
    val detail: String = "",
)
