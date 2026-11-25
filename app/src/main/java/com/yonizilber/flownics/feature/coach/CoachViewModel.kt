package com.yonizilber.flownics.feature.coach

import android.net.Uri
import com.yonizilber.flownics.data.local.media.AiPhotos
import com.yonizilber.flownics.domain.ai.FitnessPrompts
import com.yonizilber.flownics.domain.coach.CoachPhoto
import java.io.File
import java.io.IOException
import com.yonizilber.flownics.domain.model.PlannedWorkout
import com.yonizilber.flownics.domain.coach.PlanChange
import com.yonizilber.flownics.data.repository.PlanRepository
import java.time.ZoneId
import com.yonizilber.flownics.domain.model.Activity
import com.yonizilber.flownics.domain.coach.ExerciseField
import com.yonizilber.flownics.domain.coach.ExerciseChange
import com.yonizilber.flownics.domain.coach.CoachProposals
import com.yonizilber.flownics.data.ai.ExerciseAiNote
import com.yonizilber.flownics.data.repository.ActivityRepository
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yonizilber.flownics.data.ai.AiService
import com.yonizilber.flownics.data.coach.CoachAgent
import com.yonizilber.flownics.data.coach.CoachStepLimitException
import com.yonizilber.flownics.data.coach.CoachStore
import com.yonizilber.flownics.domain.ai.AiException
import com.yonizilber.flownics.domain.ai.AiModel
import com.yonizilber.flownics.domain.ai.FitnessAnswers
import com.yonizilber.flownics.domain.ai.GeneratedWorkout
import com.yonizilber.flownics.domain.coach.CoachConversation
import com.yonizilber.flownics.domain.coach.CoachEntry
import com.yonizilber.flownics.domain.coach.CoachTopic
import com.yonizilber.flownics.domain.coach.ProposalState
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.repository.ExerciseRepository
import com.yonizilber.flownics.domain.repository.SessionRepository
import com.yonizilber.flownics.domain.repository.WorkoutRepository
import com.yonizilber.flownics.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Why the last message got no answer. Shown with a way to try again. */
sealed interface CoachFailure {
    data class Ai(val error: AiException) : CoachFailure
    data object OutOfSteps : CoachFailure
}

data class CoachUiState(
    val conversationId: Long? = null,
    /** What the screen was opened about; it goes with the next message, then is cleared. */
    val topic: CoachTopic? = null,
    val sending: Boolean = false,
    val failure: CoachFailure? = null,
    /** A proposal that couldn't be saved (e.g. its exercises were deleted since). */
    val proposalError: Long? = null,
    /** Photos picked for the next message (shrunk copies on the phone). */
    val draftPhotos: List<String> = emptyList(),
    val photoError: Boolean = false,
    /** The photos couldn't be described, so nothing was sent: this text goes back into the input (once). */
    val unsent: String? = null,
    /** With [failure]: nothing was sent, so trying again means sending the input again. */
    val failedBeforeSending: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CoachViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val store: CoachStore,
    private val agent: CoachAgent,
    private val ai: AiService,
    private val exercises: ExerciseRepository,
    private val workouts: WorkoutRepository,
    private val sessions: SessionRepository,
    private val activities: ActivityRepository,
    private val plans: PlanRepository,
    private val photos: AiPhotos,
) : ViewModel() {

    private val _state = MutableStateFlow(CoachUiState())
    val state: StateFlow<CoachUiState> = _state.asStateFlow()

    val entries: StateFlow<List<CoachEntry>> = _state
        .map { it.conversationId }
        .flatMapLatest { id -> id?.let(store::observeEntries) ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val conversations: StateFlow<List<CoachConversation>> = store.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val library: StateFlow<Map<Long, Exercise>> = exercises.observeAll().map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val workoutNames: StateFlow<Map<Long, String>> = workouts.observeAll().map { list -> list.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val aiReady: StateFlow<Boolean> = ai.settings.map { it.ready }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ai.settings.value.ready)
    val model: AiModel get() = ai.settings.value.model

    private var job: Job? = null

    init {
        val exerciseId = savedStateHandle.get<Long>(Destination.ARG_EXERCISE_ID)?.takeIf { it > 0 }
        val workoutId = savedStateHandle.get<Long>(Destination.ARG_WORKOUT_ID)?.takeIf { it > 0 }
        val sessionId = savedStateHandle.get<Long>(Destination.ARG_SESSION_ID)?.takeIf { it > 0 }
        val aboutHistory = savedStateHandle.get<Boolean>(Destination.ARG_HISTORY) == true
        viewModelScope.launch {
            val topic = when {
                exerciseId != null -> exercises.get(exerciseId)?.let { CoachTopic.Exercise(it.id, it.name) }
                workoutId != null -> workouts.get(workoutId)?.let { CoachTopic.Workout(it.id, it.name) }
                sessionId != null -> sessions.observe(sessionId).first()?.let { CoachTopic.Session(it.id, it.workoutName, it.startedAtMillis) }
                aboutHistory -> CoachTopic.History
                else -> null
            }
            // Opened about something: a fresh conversation. From the tab: carry on the latest one.
            val conversation = if (topic == null) store.latestConversation()?.id else null
            _state.update { it.copy(topic = topic, conversationId = conversation) }
        }
    }

    fun send(text: String, language: String) {
        val message = text.trim()
        val s = _state.value
        if ((message.isEmpty() && s.draftPhotos.isEmpty()) || s.sending) return
        job = viewModelScope.launch {
            // Photos first become text, once: the coach reads the descriptions from then on.
            val sent = if (s.draftPhotos.isEmpty()) {
                emptyList()
            } else {
                _state.update { it.copy(sending = true, failure = null, unsent = null, failedBeforeSending = false) }
                try {
                    val images = s.draftPhotos.map { photos.load(File(it)) }
                    val descriptions = ai.ask(FitnessPrompts.photosForCoach(images, message, language)) { FitnessAnswers.photoDescriptions(it, images.size) }.value
                    s.draftPhotos.zip(descriptions) { path, d -> CoachPhoto(photos.keepForCoach(File(path)), d) }
                } catch (e: AiException) {
                    _state.update { it.copy(sending = false, failure = CoachFailure.Ai(e), unsent = message, failedBeforeSending = true) }
                    return@launch
                } catch (e: IOException) {
                    _state.update { it.copy(sending = false, failure = CoachFailure.Ai(AiException.BadResponse(e.message ?: "A photo could not be read", e)), unsent = message, failedBeforeSending = true) }
                    return@launch
                }
            }
            val now = System.currentTimeMillis()
            val id = s.conversationId ?: store.newConversation(now)
            store.addUser(id, message, now, sent)
            val title = message.ifBlank { sent.firstOrNull()?.description.orEmpty() }
            store.touch(id, store.conversation(id)?.title?.ifBlank { title } ?: title, now)
            _state.update { it.copy(conversationId = id, failure = null, draftPhotos = emptyList(), unsent = null, failedBeforeSending = false) }
            answer(id, s.topic, language)
        }
    }

    /** Asks again for the last unanswered message (not after a failure before sending: the input is sent again). */
    fun retry(language: String) {
        val s = _state.value
        if (s.sending || s.failedBeforeSending) return
        val id = s.conversationId ?: return
        job = viewModelScope.launch { answer(id, s.topic, language) }
    }

    /** The input took back the text that couldn't be sent. */
    fun unsentTaken() = _state.update { it.copy(unsent = null) }

    fun onPhotosPicked(uris: List<Uri>) = viewModelScope.launch {
        _state.update { it.copy(photoError = false) }
        val room = AiPhotos.MAX_PER_REQUEST - _state.value.draftPhotos.size
        val added = uris.take(room).mapNotNull { uri ->
            try {
                photos.prepare(uri).path
            } catch (e: IOException) {
                _state.update { it.copy(photoError = true) }
                null
            }
        }
        _state.update { it.copy(draftPhotos = it.draftPhotos + added) }
    }

    fun removePhoto(path: String) {
        photos.discardPending(listOf(File(path)))
        _state.update { it.copy(draftPhotos = it.draftPhotos - path) }
    }

    fun photoFile(name: String): File = photos.coachFile(name)

    override fun onCleared() {
        photos.discardPending(_state.value.draftPhotos.map(::File))
        super.onCleared()
    }

    private suspend fun answer(conversationId: Long, topic: CoachTopic?, language: String) {
        _state.update { it.copy(sending = true, failure = null) }
        try {
            agent.reply(conversationId, topic, language)
            // The topic went with this message; later ones are just the conversation.
            _state.update { it.copy(sending = false, topic = null) }
        } catch (e: AiException) {
            _state.update { it.copy(sending = false, failure = CoachFailure.Ai(e)) }
        } catch (e: CoachStepLimitException) {
            _state.update { it.copy(sending = false, failure = CoachFailure.OutOfSteps) }
        } finally {
            _state.update { if (it.sending) it.copy(sending = false) else it }
        }
    }

    /**
     * Stops waiting. If the coach hadn't done anything yet, the message is taken back (returned,
     * so it can go back into the input); otherwise what it did so far stays, and retry continues.
     */
    fun cancel(): String? {
        job?.cancel()
        val s = _state.value
        val last = entries.value.lastOrNull() as? CoachEntry.User
        // Still describing the photos: nothing was saved; the photos stay picked.
        if (s.draftPhotos.isNotEmpty() || last == null) {
            _state.update { it.copy(sending = false, failure = null) }
            return null
        }
        _state.update { it.copy(sending = false, failure = null) }
        viewModelScope.launch {
            store.deleteEntry(last.id)
            // Its photos go back to the input with the text.
            val back = last.photos.mapNotNull { p -> runCatching { photos.backToPending(p.name).path }.getOrNull() }
            _state.update { it.copy(draftPhotos = back + it.draftPhotos) }
        }
        return last.text
    }

    fun dismissFailure() = _state.update { it.copy(failure = null) }

    fun newConversation() {
        if (_state.value.sending) return
        _state.update { CoachUiState() }
    }

    fun openConversation(id: Long) {
        if (_state.value.sending) return
        _state.update { CoachUiState(conversationId = id) }
    }

    fun deleteConversation(id: Long) = viewModelScope.launch {
        store.deleteConversation(id)
        if (_state.value.conversationId == id) _state.update { CoachUiState() }
    }

    fun clearTopic() = _state.update { it.copy(topic = null) }

    /** The proposal as the app reads it now: exercises deleted since it was made are left out. */
    fun workoutOf(proposal: CoachEntry.Proposal, library: Map<Long, Exercise>): GeneratedWorkout? =
        runCatching { FitnessAnswers.workout(proposal.args.toString(), library) }.getOrNull()

    /** Saves the proposal: over the workout it changes when [replace], otherwise as a new one. */
    fun saveProposal(proposal: CoachEntry.Proposal, replace: Boolean, onSaved: (Long) -> Unit = {}) = viewModelScope.launch {
        val generated = workoutOf(proposal, library.value)
        if (generated == null || generated.template.blocks.isEmpty()) {
            _state.update { it.copy(proposalError = proposal.id) }
            return@launch
        }
        val existing = if (replace) proposal.replacesWorkoutId?.let { workouts.get(it) } else null
        // (a workout proposal)
        val template = existing?.copy(name = generated.template.name, description = generated.template.description, blocks = generated.template.blocks)
            ?: generated.template
        val id = workouts.save(template)
        store.setProposalState(proposal.id, ProposalState.SAVED, id)
        _state.update { it.copy(proposalError = null) }
        onSaved(id)
    }

    /** The exercise proposal as it reads against the library now; null once it no longer applies. */
    fun exerciseChangeOf(proposal: CoachEntry.Proposal, library: Map<Long, Exercise>): ExerciseChange? =
        runCatching { CoachProposals.exerciseChange(proposal.args, library) }.getOrNull()

    fun activityOf(proposal: CoachEntry.Proposal): Activity? =
        runCatching { CoachProposals.activity(proposal.args, ZoneId.systemDefault()) }.getOrNull()

    /**
     * Applies the chosen parts of an exercise proposal (all of it, for a new exercise), and notes
     * on the exercise that they came from the coach, as for any AI-suggested details.
     */
    fun applyExerciseChange(proposal: CoachEntry.Proposal, chosen: Set<ExerciseField>) = viewModelScope.launch {
        val change = exerciseChangeOf(proposal, library.value)
        if (change == null || (!change.isNew && chosen.isEmpty())) {
            _state.update { it.copy(proposalError = proposal.id) }
            return@launch
        }
        val exercise = change.applied(chosen)
        val id = exercises.save(exercise)
        if (change.isNew && exercise.variationOf != null || ExerciseField.STYLE_OF in chosen) exercises.setStyleOf(id, exercise.variationOf)
        val fields = if (change.isNew) emptyList() else change.fields.filter { it in chosen }.map { it.name }
        ai.saveExerciseNote(
            id,
            ExerciseAiNote(
                atMillis = System.currentTimeMillis(),
                model = AiModel.byId(proposal.modelId),
                modelId = proposal.modelId,
                summary = change.reason,
                applied = fields.map { it.lowercase() },
                sources = emptyList(),
            ),
        )
        store.setProposalState(proposal.id, ProposalState.SAVED, id, fields)
        _state.update { it.copy(proposalError = null) }
    }

    fun saveActivity(proposal: CoachEntry.Proposal) = viewModelScope.launch {
        val activity = activityOf(proposal)
        if (activity == null) {
            _state.update { it.copy(proposalError = proposal.id) }
            return@launch
        }
        val id = activities.save(activity)
        store.setProposalState(proposal.id, ProposalState.SAVED, id)
    }

    val currentPlans: StateFlow<Map<Long, PlannedWorkout>> = plans.observeAll().map { all -> all.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun planChangeOf(proposal: CoachEntry.Proposal, existing: Map<Long, PlannedWorkout>, workoutIds: Set<Long>): PlanChange? =
        runCatching { CoachProposals.planChange(proposal.args, existing, workoutIds) }.getOrNull()

    /** Accepts the chosen rows of a plan proposal: adds, moves and drops plans. */
    fun applyPlanChange(proposal: CoachEntry.Proposal, chosen: Set<String>) = viewModelScope.launch {
        val change = planChangeOf(proposal, currentPlans.value, workoutNames.value.keys)
        if (change == null || chosen.isEmpty()) {
            _state.update { it.copy(proposalError = proposal.id) }
            return@launch
        }
        change.adds.forEachIndexed { i, p -> if ("add:$i" in chosen) plans.save(p) }
        change.moves.forEach { (old, new) -> if ("move:${old.id}" in chosen) plans.save(new) }
        change.removes.forEach { p -> if ("remove:${p.id}" in chosen) plans.delete(p.id) }
        store.setProposalState(proposal.id, ProposalState.SAVED, null, change.keys.filter { it in chosen })
        _state.update { it.copy(proposalError = null) }
    }

    fun declineProposal(proposal: CoachEntry.Proposal) = viewModelScope.launch {
        store.setProposalState(proposal.id, ProposalState.DECLINED, null)
    }

    /** Takes back what the coach did to its memory. */
    fun undoNote(note: CoachEntry.Note) = viewModelScope.launch {
        if (note.removed) store.addNote(note.text, System.currentTimeMillis()) else store.deleteNote(note.noteId)
        store.markNoteEntryUndone(note.id)
    }
}
