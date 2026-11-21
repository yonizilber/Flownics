package com.yonizilber.flownics.feature.player

import com.yonizilber.flownics.data.local.prefs.WorkoutDefaults
import com.yonizilber.flownics.data.local.prefs.AppPreferences
import com.yonizilber.flownics.sensors.SensorState
import com.yonizilber.flownics.sensors.HeartRateMonitor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yonizilber.flownics.data.local.media.MediaStorage
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.SetTarget
import com.yonizilber.flownics.domain.player.LaneEntry
import com.yonizilber.flownics.player.ActiveWorkout
import com.yonizilber.flownics.player.CueProblem
import com.yonizilber.flownics.player.PlayerController
import com.yonizilber.flownics.player.WorkoutCues
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val controller: PlayerController,
    cues: WorkoutCues,
    private val mediaStorage: MediaStorage,
    heartRate: HeartRateMonitor,
    prefs: AppPreferences,
) : ViewModel() {

    val defaults: StateFlow<WorkoutDefaults> = prefs.observeWorkoutDefaults()

    fun setHoldAll(on: Boolean) = controller.setHoldAll(on)
    fun addSet(): Int? = controller.addSet()
    fun addExercise(a: AddedExercise) {
        viewModelScope.launch {
            val id = a.newName?.let { controller.createQuick(it, a.newMode, a.newGeneral) } ?: a.exerciseId
            controller.addExercise(id, a.sets, a.target, a.restSeconds)
        }
    }
    fun restAgain() = controller.restAgain()
    fun restNotNow() = controller.restNotNow()
    fun restSkip() = controller.restSkip()
    fun restPick(entry: LaneEntry) = controller.restPick(entry)
    fun restBack() = controller.restBack()
    fun jumpTo(index: Int) = controller.jumpTo(index)

    /** Live heart rate, when a sensor is connected. */
    val sensor: StateFlow<SensorState> = heartRate.state


    init {
        controller.restoreIfNeeded()
    }

    val workout: StateFlow<ActiveWorkout?> = controller.state
    val library: StateFlow<Map<Long, Exercise>> = controller.library
    val cueProblems: StateFlow<Set<CueProblem>> = cues.problems

    /** What the user dialed in for set [index]; the controller keeps it with the step. */
    fun setEntry(index: Int, value: SetTarget) = controller.setDraft(index, value)

    fun done(actual: SetTarget?) = controller.done(actual)

    fun skip() = controller.skip()

    fun previous() = controller.previous()

    fun pause() = controller.pause()

    fun resume() = controller.resume()

    fun adjust(deltaSeconds: Int) = controller.adjust(deltaSeconds)

    fun end() = controller.end()

    fun dismissRestoredNotice() = controller.dismissRestoredNotice()

    /** [onDone] gets the saved session's id, or null if the workout was already gone. */
    fun finish(notes: String, onDone: (Long?) -> Unit) {
        viewModelScope.launch { onDone(controller.finish(notes)) }
    }

    fun discard(onDone: () -> Unit) {
        viewModelScope.launch {
            controller.discard()
            onDone()
        }
    }

    fun mediaFile(name: String): File = mediaStorage.file(name)
}
