package com.yonizilber.flownics.data.coach

import android.content.Context
import com.yonizilber.flownics.data.local.db.dao.CoachDao
import com.yonizilber.flownics.data.local.db.entity.CoachConversationEntity
import com.yonizilber.flownics.data.local.db.entity.CoachMessageEntity
import com.yonizilber.flownics.data.local.db.entity.CoachNoteEntity
import com.yonizilber.flownics.domain.coach.CoachConversation
import com.yonizilber.flownics.domain.coach.CoachEntry
import com.yonizilber.flownics.domain.coach.CoachEntryKind
import com.yonizilber.flownics.domain.coach.CoachNote
import com.yonizilber.flownics.domain.coach.CoachProfile
import com.yonizilber.flownics.domain.coach.ProposalKind
import com.yonizilber.flownics.domain.coach.ProposalState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** The coach's conversations, its memory and the user's profile, all kept on the device. */
@Singleton
class CoachStore @Inject constructor(
    @ApplicationContext context: Context,
    private val dao: CoachDao,
    private val json: Json,
) {
    private val prefs = context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
    private val _profile = MutableStateFlow(
        CoachProfile(
            goals = prefs.getString(KEY_GOALS, "").orEmpty(),
            limitations = prefs.getString(KEY_LIMITATIONS, "").orEmpty(),
            equipment = prefs.getString(KEY_EQUIPMENT, "").orEmpty(),
            schedule = prefs.getString(KEY_SCHEDULE, "").orEmpty(),
            maxHeartRate = prefs.getInt(KEY_MAX_HR, -1).takeIf { it > 0 },
            restingHeartRate = prefs.getInt(KEY_REST_HR, -1).takeIf { it > 0 },
        ),
    )
    val profile: StateFlow<CoachProfile> = _profile.asStateFlow()

    fun saveProfile(profile: CoachProfile) {
        prefs.edit()
            .putString(KEY_GOALS, profile.goals.trim())
            .putString(KEY_LIMITATIONS, profile.limitations.trim())
            .putString(KEY_EQUIPMENT, profile.equipment.trim())
            .putString(KEY_SCHEDULE, profile.schedule.trim())
            .putInt(KEY_MAX_HR, profile.maxHeartRate ?: -1)
            .putInt(KEY_REST_HR, profile.restingHeartRate ?: -1)
            .apply()
        _profile.value = profile
    }

    // ---- Conversations ------------------------------------------------------------------------

    fun observeConversations(): Flow<List<CoachConversation>> = dao.observeConversations().map { rows -> rows.map { it.toDomain() } }

    suspend fun latestConversation(): CoachConversation? = dao.latestConversation()?.toDomain()

    suspend fun conversation(id: Long): CoachConversation? = dao.conversation(id)?.toDomain()

    suspend fun newConversation(now: Long): Long = dao.insertConversation(CoachConversationEntity(title = "", createdAtMillis = now, updatedAtMillis = now))

    suspend fun deleteConversation(id: Long) = dao.deleteConversation(id)

    suspend fun touch(conversationId: Long, title: String, now: Long) = dao.touchConversation(conversationId, title.take(TITLE_MAX), now)

    fun observeEntries(conversationId: Long): Flow<List<CoachEntry>> = dao.observeMessages(conversationId).map { rows -> rows.mapNotNull { it.toEntry() } }

    suspend fun entries(conversationId: Long): List<CoachEntry> = dao.messages(conversationId).mapNotNull { it.toEntry() }

    suspend fun addUser(conversationId: Long, text: String, now: Long) = insert(conversationId, CoachEntryKind.USER, text, now, "{}")

    suspend fun addAssistant(conversationId: Long, text: String, modelId: String, costUsd: Double, now: Long) =
        insert(conversationId, CoachEntryKind.ASSISTANT, text, now, json.encodeToString(AssistantData.serializer(), AssistantData(modelId, costUsd)))

    suspend fun addStep(conversationId: Long, tool: String, args: JsonObject, failed: Boolean, now: Long) =
        insert(conversationId, CoachEntryKind.STEP, tool, now, json.encodeToString(StepData.serializer(), StepData(args, failed)))

    suspend fun addProposal(conversationId: Long, kind: ProposalKind, args: JsonObject, replacesWorkoutId: Long?, modelId: String, now: Long): Long =
        insert(
            conversationId,
            CoachEntryKind.PROPOSAL,
            "",
            now,
            json.encodeToString(
                ProposalData.serializer(),
                ProposalData(args, replacesWorkoutId ?: 0, ProposalState.PENDING.name, 0, kind.name, modelId),
            ),
        )

    suspend fun setProposalState(entryId: Long, state: ProposalState, savedId: Long?, applied: List<String> = emptyList()) {
        val row = dao.message(entryId) ?: return
        val data = json.decodeFromString(ProposalData.serializer(), row.dataJson)
        dao.updateMessageData(
            entryId,
            json.encodeToString(ProposalData.serializer(), data.copy(state = state.name, savedWorkoutId = savedId ?: 0, applied = applied)),
        )
    }

    suspend fun addNoteEntry(conversationId: Long, noteId: Long, text: String, removed: Boolean, now: Long) =
        insert(conversationId, CoachEntryKind.NOTE, text, now, json.encodeToString(NoteData.serializer(), NoteData(noteId, removed, undone = false)))

    suspend fun markNoteEntryUndone(entryId: Long) {
        val row = dao.message(entryId) ?: return
        val data = json.decodeFromString(NoteData.serializer(), row.dataJson)
        dao.updateMessageData(entryId, json.encodeToString(NoteData.serializer(), data.copy(undone = true)))
    }

    suspend fun deleteEntry(entryId: Long) = dao.deleteMessage(entryId)

    private suspend fun insert(conversationId: Long, kind: CoachEntryKind, text: String, now: Long, dataJson: String): Long =
        dao.insertMessage(CoachMessageEntity(conversationId = conversationId, atMillis = now, kind = kind.name, text = text, dataJson = dataJson))

    // ---- Memory -------------------------------------------------------------------------------

    fun observeNotes(): Flow<List<CoachNote>> = dao.observeNotes().map { rows -> rows.map { CoachNote(it.id, it.text, it.createdAtMillis) } }

    suspend fun notes(): List<CoachNote> = dao.notes().map { CoachNote(it.id, it.text, it.createdAtMillis) }

    suspend fun addNote(text: String, now: Long): Long = dao.insertNote(CoachNoteEntity(text = text.trim().take(NOTE_MAX), createdAtMillis = now))

    suspend fun updateNote(id: Long, text: String) = dao.updateNote(id, text.trim().take(NOTE_MAX))

    /** True when there was such a note. */
    suspend fun deleteNote(id: Long): Boolean = dao.deleteNote(id) > 0

    // ---- Mapping ------------------------------------------------------------------------------

    private fun CoachConversationEntity.toDomain() = CoachConversation(id, title, createdAtMillis, updatedAtMillis)

    // A row this version can't read (a newer kind, broken JSON) is left out rather than crashing
    // the conversation; it stays in the database untouched.
    private fun CoachMessageEntity.toEntry(): CoachEntry? = runCatching {
        when (CoachEntryKind.valueOf(kind)) {
            CoachEntryKind.USER -> CoachEntry.User(id, atMillis, text)
            CoachEntryKind.ASSISTANT -> json.decodeFromString(AssistantData.serializer(), dataJson).let {
                CoachEntry.Assistant(id, atMillis, text, it.modelId, it.costUsd)
            }
            CoachEntryKind.STEP -> json.decodeFromString(StepData.serializer(), dataJson).let {
                CoachEntry.Step(id, atMillis, text, it.args, it.failed)
            }
            CoachEntryKind.PROPOSAL -> json.decodeFromString(ProposalData.serializer(), dataJson).let {
                CoachEntry.Proposal(
                    id = id,
                    atMillis = atMillis,
                    kind = ProposalKind.valueOf(it.kind),
                    args = it.args,
                    replacesWorkoutId = it.replacesWorkoutId.takeIf { r -> r > 0 },
                    state = ProposalState.valueOf(it.state),
                    savedId = it.savedWorkoutId.takeIf { s -> s > 0 },
                    modelId = it.modelId,
                    applied = it.applied,
                )
            }
            CoachEntryKind.NOTE -> json.decodeFromString(NoteData.serializer(), dataJson).let {
                CoachEntry.Note(id, atMillis, it.noteId, text, it.removed, it.undone)
            }
        }
    }.getOrNull()

    @Serializable
    private data class AssistantData(val modelId: String, val costUsd: Double)

    @Serializable
    private data class StepData(val args: JsonObject, val failed: Boolean = false)

    // savedWorkoutId keeps its v7 name; it holds the saved workout, exercise or activity.
    @Serializable
    private data class ProposalData(
        val args: JsonObject,
        val replacesWorkoutId: Long = 0,
        val state: String,
        val savedWorkoutId: Long = 0,
        val kind: String = "WORKOUT",
        val modelId: String = "",
        val applied: List<String> = emptyList(),
    )

    @Serializable
    private data class NoteData(val noteId: Long, val removed: Boolean = false, val undone: Boolean = false)

    private companion object {
        const val PROFILE_PREFS = "coach_profile"
        const val KEY_GOALS = "goals"
        const val KEY_LIMITATIONS = "limitations"
        const val KEY_EQUIPMENT = "equipment"
        const val KEY_SCHEDULE = "schedule"
        const val KEY_MAX_HR = "max_hr"
        const val KEY_REST_HR = "resting_hr"
        const val TITLE_MAX = 80
        const val NOTE_MAX = 300
    }
}
