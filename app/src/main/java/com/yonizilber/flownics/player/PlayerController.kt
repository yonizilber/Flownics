package com.yonizilber.flownics.player

import com.yonizilber.flownics.domain.workout.RestStep
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.player.SessionEdits
import com.yonizilber.flownics.domain.player.LaneEntry
import com.yonizilber.flownics.domain.player.RestLane
import com.yonizilber.flownics.data.garmin.RecordingLinker
import com.yonizilber.flownics.sensors.HeartRateMonitor
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.yonizilber.flownics.data.di.ApplicationScope
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.MeasureMode
import com.yonizilber.flownics.domain.model.SessionStatus
import com.yonizilber.flownics.domain.model.SetLog
import com.yonizilber.flownics.domain.model.SetTarget
import com.yonizilber.flownics.domain.model.WorkoutSession
import com.yonizilber.flownics.domain.player.Phase
import com.yonizilber.flownics.domain.player.PlayerState
import com.yonizilber.flownics.domain.player.SetDefaults
import com.yonizilber.flownics.domain.player.WorkoutPlayer
import com.yonizilber.flownics.domain.repository.ExerciseRepository
import com.yonizilber.flownics.domain.repository.SessionRepository
import com.yonizilber.flownics.domain.repository.WorkoutRepository
import com.yonizilber.flownics.domain.workout.WorkStep
import com.yonizilber.flownics.domain.workout.WorkoutCompiler
import com.yonizilber.flownics.domain.workout.WorkoutStep
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** The workout being played: its frozen plan, where the player is, and what has been logged. */
data class ActiveWorkout(
    val session: WorkoutSession,
    val player: PlayerState,
    /** Logged work steps, by step index. */
    val logs: Map<Int, SetLog>,
    /** Per exercise, the latest set from an earlier finished workout ("last time: 8 × 40 kg"). */
    val lastTime: Map<Long, SetLog>,
    /** The app was closed mid-workout and the session came back paused. */
    val restored: Boolean = false,
    /** Set during the workout: every timed step waits at zero for the user. */
    val holdAll: Boolean = false,
) {
    /** The current timed step is past zero, waiting for the user. */
    fun overtimeMs(nowMs: Long): Long = step?.takeIf { WorkoutPlayer.holds(it, holdAll) }?.let { WorkoutPlayer.overtimeMs(player.phase, nowMs) } ?: 0

    val step: WorkoutStep? get() = session.steps.getOrNull(player.index)

    /** The next set to do after the current step. */
    fun nextWork(): WorkStep? =
        session.steps.drop(player.index + 1).firstOrNull { it is WorkStep } as WorkStep?
}

sealed interface StartResult {
    data object Started : StartResult
    data object AnotherInProgress : StartResult
    data object NothingToRun : StartResult
    data object NotFound : StartResult
}

/**
 * Owns the running workout for the whole process, so it outlives the player screen. Screens and
 * the notification only send commands and read [state]; [WorkoutService] keeps the process alive
 * and the CPU awake during countdowns. All calls happen on the main thread.
 */
@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val sessions: SessionRepository,
    private val workouts: WorkoutRepository,
    private val exercises: ExerciseRepository,
    private val cues: WorkoutCues,
    private val heartRate: HeartRateMonitor,
    private val linker: RecordingLinker,
) {
    val library: StateFlow<Map<Long, Exercise>> = exercises.observeAll()
        .map { list -> list.associateBy { it.id } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val _state = MutableStateFlow<ActiveWorkout?>(null)
    val state: StateFlow<ActiveWorkout?> = _state.asStateFlow()

    /** Kept by [WorkoutService], so commands from the notification don't try to start it again. */
    internal var serviceRunning = false

    private var restoreChecked = false
    private var ticker: Job? = null
    private var lastBeepSecond = -1
    private var overtimeCued: Int? = null

    private fun now() = SystemClock.elapsedRealtime()

    /**
     * Brings back a workout left in progress when the process died. It comes back paused at the
     * start of its step, never fast-forwarded: the cues for whatever "happened" while the app was
     * dead were never played, so pretending the timer kept going would be a lie.
     */
    fun restoreIfNeeded() {
        if (restoreChecked) return
        restoreChecked = true
        scope.launch {
            val session = sessions.inProgress() ?: return@launch
            if (_state.value != null) return@launch
            _state.value = ActiveWorkout(
                session = session,
                player = WorkoutPlayer.restore(session.steps, session.currentIndex, now()),
                logs = sessions.logs(session.id).associateBy { it.stepIndex },
                lastTime = lastTimeFor(session),
                restored = true,
            )
            startHeartRate()
        }
    }

    suspend fun start(templateId: Long): StartResult {
        if (_state.value != null || sessions.inProgress() != null) return StartResult.AnotherInProgress
        val template = workouts.get(templateId) ?: return StartResult.NotFound
        val library = exercises.observeAll().first().associateBy { it.id }
        val compiled = WorkoutCompiler.compile(template, library, workouts.observeAll().first().associateBy { it.id })
        if (compiled.steps.isEmpty()) return StartResult.NothingToRun

        val draft = WorkoutSession(
            templateId = template.id,
            workoutName = template.name,
            blockTitles = template.blocks.map { it.title } + listOfNotNull(compiled.extraBlockTitle),
            steps = compiled.steps,
            status = SessionStatus.IN_PROGRESS,
            startedAtMillis = System.currentTimeMillis(),
        )
        val session = draft.copy(id = sessions.create(draft))
        restoreChecked = true
        cues.prepare()
        val workout = ActiveWorkout(session, WorkoutPlayer.start(session.steps, now()), emptyMap(), lastTimeFor(session))
        _state.value = workout
        startHeartRate()
        cueNewStep(workout)
        ensureService()
        ensureTicker()
        return StartResult.Started
    }

    /** Logs the current set ([actual], or the defaults when null) and moves on. */
    fun done(actual: SetTarget? = null) = transition { w, now ->
        val step = w.step
        val logged = if (step is WorkStep) {
            val chosen = actual ?: SetDefaults.forStep(step, w.player.index, w.logs, w.lastTime[step.exerciseId])
            log(w, w.player.index, withTimeDone(w, step, chosen, now), skipped = false)
        } else {
            w
        }
        logged.copy(player = WorkoutPlayer.advance(w.player, w.session.steps, now))
    }

    fun skip() = transition { w, now ->
        val logged = if (w.step is WorkStep) log(w, w.player.index, SetTarget(), skipped = true) else w
        logged.copy(player = WorkoutPlayer.advance(w.player, w.session.steps, now))
    }

    fun previous() = transition { w, now -> w.copy(player = WorkoutPlayer.previous(w.player, w.session.steps, now)) }

    fun pause() = transition { w, now -> w.copy(player = WorkoutPlayer.pause(w.player, now)) }

    fun resume() {
        cues.prepare()
        transition { w, now -> w.copy(player = WorkoutPlayer.resume(w.player, now), restored = false) }
        ensureService()
    }

    fun adjust(deltaSeconds: Int) = transition { w, now -> w.copy(player = WorkoutPlayer.adjust(w.player, deltaSeconds, now)) }

    /** Jumps to the end screen; logged sets stay. [previous] goes back to where it was ended. */
    fun end() = transition { w, _ -> w.copy(player = WorkoutPlayer.end(w.player)) }

    fun dismissRestoredNotice() = transition { w, _ -> w.copy(restored = false) }

    // ---- Changing the workout while it runs ----------------------------------------------------

    /** Every timed step waits at zero for the user (or not), for the rest of this workout. */
    fun setHoldAll(on: Boolean) {
        transition { w, _ -> w.copy(holdAll = on) }
        ensureTicker()
    }

    // The rest lane: the exercises of the rests have their own place, beside the sets.

    fun restAgain() = laneEdit { w, logged, block -> RestLane.again(w.session.steps, w.player.index, library.value, logged, block) }

    fun restNotNow() = laneEdit { w, logged, block -> RestLane.postpone(w.session.steps, w.player.index, library.value, logged, block) }

    fun restSkip() = laneEdit { w, logged, block -> RestLane.skip(w.session.steps, w.player.index, library.value, logged, block) }

    /** [entry] in this rest (or the next one, during a set); the rest of the lane moves to make room. */
    fun restPick(entry: LaneEntry) = laneEdit { w, logged, block -> RestLane.pick(w.session.steps, w.player.index, entry, library.value, logged, block) }

    /**
     * Goes to step [target] of the sets (back to redo one, or ahead). The rest lane stays where it
     * was: the rests from there on take the exercises still to come.
     */
    fun jumpTo(target: Int) {
        val w = _state.value ?: return
        if (target !in w.session.steps.indices || target == w.player.index) return
        val from = w.player.index.coerceAtMost(w.session.steps.lastIndex)
        val (titles, block) = laneBlock(w.session.blockTitles)
        RestLane.moveSets(w.session.steps, from, target, library.value, w.logs.keys, block)?.let { edit ->
            apply(w, edit.steps, edit.shiftFrom, edit.shiftBy, if (edit.newBlockUsed) titles else w.session.blockTitles)
        }
        transition { a, now -> a.copy(player = WorkoutPlayer.jumpTo(a.session.steps, target, now)) }
        ensureService()
    }

    private fun laneEdit(edit: (ActiveWorkout, Set<Int>, Int) -> RestLane.Layout?): Boolean {
        val w = _state.value ?: return false
        val (titles, block) = laneBlock(w.session.blockTitles)
        val layout = edit(w, w.logs.keys, block) ?: return false
        apply(w, layout.steps, layout.shiftFrom, layout.shiftBy, if (layout.newBlockUsed) titles else w.session.blockTitles)
        // The rest now holds another exercise: say which, as when a step starts.
        if (layout.steps.getOrNull(w.player.index) != w.session.steps.getOrNull(w.player.index)) _state.value?.let(::cueNewStep)
        return true
    }

    /** Where rest exercises that no longer fit go when the workout has no such block yet. */
    private fun laneBlock(titles: List<String>): Pair<List<String>, Int> =
        (titles + context.getString(R.string.player_rest_leftovers)) to titles.size

    /** One more set of the current exercise. */
    fun addSet() {
        val w = _state.value ?: return
        val edit = SessionEdits.addSet(w.session.steps, w.player.index) ?: return
        apply(w, edit.steps, edit.insertedAt, edit.shift)
    }

    /** An exercise that wasn't in the plan: after the current step, or (when it was over) as the next thing to do. */
    fun addExercise(exerciseId: Long, sets: Int, target: SetTarget, restSeconds: Int) {
        val w = _state.value ?: return
        val exercise = library.value[exerciseId] ?: return
        val (titles, block) = addedBlock(w.session.blockTitles)
        val finished = w.player.phase is Phase.Finished
        val edit = SessionEdits.addAfterCurrent(w.session.steps, w.player.index, finished, SessionEdits.exerciseSteps(exercise, sets, target, restSeconds, block))
        apply(w, edit.steps, edit.insertedAt, edit.shift, titles)
        if (finished) {
            // The workout goes on: straight into what was just added.
            transition { a, now -> a.copy(player = WorkoutPlayer.jumpTo(a.session.steps, edit.insertedAt, now)) }
            ensureService()
        }
    }

    /** A workout without a plan: starts with the exercises chosen, and more can be added as it goes. */
    suspend fun startFree(first: List<Triple<Long, Int, SetTarget>>, restSeconds: Int): StartResult {
        if (_state.value != null || sessions.inProgress() != null) return StartResult.AnotherInProgress
        val lib = library.value.ifEmpty { exercises.observeAll().first().associateBy { it.id } }
        val steps = first.flatMap { (id, sets, target) -> lib[id]?.let { SessionEdits.exerciseSteps(it, sets, target, restSeconds, 0) }.orEmpty() }
            .dropLastWhile { it is RestStep }
        if (steps.isEmpty()) return StartResult.NothingToRun
        val draft = WorkoutSession(
            templateId = null,
            workoutName = context.getString(R.string.player_free_workout),
            blockTitles = listOf(context.getString(R.string.player_added_block)),
            steps = steps,
            status = SessionStatus.IN_PROGRESS,
            startedAtMillis = System.currentTimeMillis(),
        )
        val session = draft.copy(id = sessions.create(draft))
        restoreChecked = true
        cues.prepare()
        val workout = ActiveWorkout(session, WorkoutPlayer.start(session.steps, now()), emptyMap(), lastTimeFor(session))
        _state.value = workout
        startHeartRate()
        cueNewStep(workout)
        ensureService()
        ensureTicker()
        return StartResult.Started
    }

    /** The block exercises added during the workout go in (made once, at the end). */
    private fun addedBlock(titles: List<String>): Pair<List<String>, Int> {
        val name = context.getString(R.string.player_added_block)
        return if (titles.lastOrNull() == name) titles to titles.lastIndex else (titles + name) to titles.size
    }

    /** Puts edited steps in place: the player stays on the same step, logs stay with their steps, all saved. */
    private fun apply(w: ActiveWorkout, steps: List<WorkoutStep>, shiftFrom: Int, shiftBy: Int, titles: List<String> = w.session.blockTitles) {
        val session = w.session.copy(steps = steps, blockTitles = titles)
        val logs = w.logs.mapKeys { (i, _) -> if (i >= shiftFrom) i + shiftBy else i }
            .mapValues { (i, log) -> log.copy(stepIndex = i) }
        val index = if (w.player.index >= shiftFrom) w.player.index + shiftBy else w.player.index
        _state.value = w.copy(session = session, logs = logs, player = WorkoutPlayer.reindex(w.player, index))
        scope.launch { sessions.updatePlan(session, shiftFrom, shiftBy) }
        ensureTicker()
    }

    /** Saves the workout to history; returns its session id, or null when nothing was running. */
    suspend fun finish(notes: String): Long? {
        val w = _state.value ?: return null
        val heart = heartRate.stopRecording()
        sessions.complete(w.session.id, System.currentTimeMillis(), notes.trim())
        linker.saveSensor(w.session.id, heart)
        stop()
        return w.session.id
    }

    /** Throws the running (or left-over) session away, logs included. */
    suspend fun discard() {
        val id = _state.value?.session?.id ?: sessions.inProgress()?.id
        id?.let { sessions.delete(it) }
        stop()
    }

    /** Live heart rate during the workout, when a sensor was chosen (a strap, or a watch broadcasting). */
    private fun startHeartRate() {
        if (heartRate.saved.value == null) return
        heartRate.connect()
        heartRate.startRecording()
    }

    private fun stop() {
        heartRate.stopRecording()
        heartRate.disconnect()
        _state.value = null
        ticker?.cancel()
        cues.release()
    }

    private fun transition(change: (ActiveWorkout, Long) -> ActiveWorkout) {
        val before = _state.value ?: return
        val after = change(before, now())
        if (after == before) return
        _state.value = after
        if (after.player.index != before.player.index) {
            scope.launch { sessions.updateIndex(after.session.id, after.player.index) }
        }
        val phase = after.player.phase
        val newStep = after.player.index != before.player.index ||
            (phase is Phase.Finished && before.player.phase !is Phase.Finished)
        when {
            phase is Phase.Paused -> Unit
            newStep -> cueNewStep(after)
            before.player.phase is Phase.LeadIn && phase is Phase.Running -> cues.stepStarted()
        }
        ensureTicker()
    }

    private fun cueNewStep(workout: ActiveWorkout) {
        cues.stepStarted()
        PlayerTexts(context.resources).announcement(workout, library.value)?.let(cues::say)
    }

    // Deadlines are checked a few times a second; the remaining time itself always comes from the
    // stored deadline, so a late wake-up delays a cue slightly but never shifts the workout clock.
    private fun ensureTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (true) {
                val phase = _state.value?.player?.phase ?: break
                val w = _state.value ?: break
                if (WorkoutPlayer.nextDeadline(w.player, w.session.steps, now(), w.holdAll) == null) break
                tick()
                beepCountdown()
                delay(TICK_MS)
            }
        }
    }

    private fun tick() = transition { w, now ->
        val next = WorkoutPlayer.tick(w.player, w.session.steps, now, w.holdAll)
        // A step that waits for the user just reached zero: say so, once.
        if (next.index == w.player.index && w.overtimeMs(now) == 0L && w.copy(player = next).overtimeMs(now + 1) > 0 && overtimeCued != next.index) {
            overtimeCued = next.index
            cues.stepStarted()
        }
        // Timed sets that ran out on their own were done in full.
        val logged = (w.player.index until next.index).fold(w) { acc, index ->
            val step = w.session.steps[index] as? WorkStep ?: return@fold acc
            val defaults = SetDefaults.forStep(step, index, acc.logs, acc.lastTime[step.exerciseId])
            log(acc, index, defaults.copy(seconds = step.durationSeconds), skipped = false)
        }
        logged.copy(player = next)
    }

    private fun beepCountdown() {
        val phase = _state.value?.player?.phase ?: return
        if (phase is Phase.Paused) return
        val remaining = WorkoutPlayer.remainingMs(phase, now()) ?: return
        val second = ((remaining + 999) / 1000).toInt()
        if (second in 1..COUNTDOWN_BEEPS && second != lastBeepSecond) cues.countdownBeep()
        lastBeepSecond = second
    }

    private fun withTimeDone(w: ActiveWorkout, step: WorkStep, actual: SetTarget, now: Long): SetTarget {
        // A timed set ended by the user (early, or one with no target time): log the time actually done.
        val timed = step.durationSeconds != null || library.value[step.exerciseId]?.measureMode == MeasureMode.TIME
        return if (timed && (step.durationSeconds != null || actual.seconds == null)) {
            actual.copy(seconds = (WorkoutPlayer.elapsedMs(w.player.phase, now) / 1000).toInt())
        } else {
            actual
        }
    }

    private fun log(w: ActiveWorkout, index: Int, actual: SetTarget, skipped: Boolean): ActiveWorkout {
        val step = w.session.steps[index] as WorkStep
        val entry = SetLog(
            sessionId = w.session.id,
            stepIndex = index,
            exerciseId = step.exerciseId,
            // Empty when the exercise was deleted mid-workout; history shows it as deleted.
            exerciseName = library.value[step.exerciseId]?.name.orEmpty(),
            blockIndex = step.blockIndex,
            set = step.set,
            round = step.round,
            side = step.side,
            actual = actual,
            skipped = skipped,
            completedAtMillis = System.currentTimeMillis(),
            startedAtMillis = startedAtWall(w, index),
        )
        scope.launch { sessions.saveLog(entry) }
        return w.copy(logs = w.logs + (index to entry))
    }

    /**
     * When the step being logged began, on the wall clock (so it lines up with a watch recording).
     * The player keeps monotonic times; pauses are already taken out of them.
     */
    private fun startedAtWall(w: ActiveWorkout, index: Int): Long? {
        if (index != w.player.index) return null
        val phase = w.player.phase.let { (it as? Phase.Paused)?.frozen ?: it } as? Phase.Running ?: return null
        return System.currentTimeMillis() - (SystemClock.elapsedRealtime() - phase.startedAtMs)
    }

    private suspend fun lastTimeFor(session: WorkoutSession): Map<Long, SetLog> =
        session.steps.filterIsInstance<WorkStep>().map { it.exerciseId }.distinct()
            .mapNotNull { id -> sessions.lastLog(id)?.let { id to it } }
            .toMap()

    // Only called from user actions in the app, which is in the foreground: Android 12+ refuses to
    // start a foreground service from the background.
    private fun ensureService() {
        if (!serviceRunning) ContextCompat.startForegroundService(context, Intent(context, WorkoutService::class.java))
    }

    private companion object {
        const val TICK_MS = 250L
        const val COUNTDOWN_BEEPS = 3
    }
}
