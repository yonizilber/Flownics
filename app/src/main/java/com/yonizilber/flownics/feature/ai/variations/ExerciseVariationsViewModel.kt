package com.yonizilber.flownics.feature.ai.variations

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yonizilber.flownics.data.ai.AiService
import com.yonizilber.flownics.domain.ai.AiException
import com.yonizilber.flownics.domain.ai.AiModel
import com.yonizilber.flownics.domain.ai.FitnessAnswers
import com.yonizilber.flownics.domain.ai.FitnessPrompts
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.repository.ExerciseRepository
import com.yonizilber.flownics.domain.repository.SavedVariation
import com.yonizilber.flownics.domain.repository.VariationRepository
import com.yonizilber.flownics.feature.ai.AiCall
import com.yonizilber.flownics.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the last request brought: how many were new, and the AI's rating of the original. */
data class VariationsAnswer(val newCount: Int, val baseLevel: Int?, val model: AiModel, val costUsd: Double, val notes: String)

@HiltViewModel
class ExerciseVariationsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val exercises: ExerciseRepository,
    private val variations: VariationRepository,
    private val ai: AiService,
) : ViewModel() {

    private val exerciseId: Long = checkNotNull(savedStateHandle.get<Long>(Destination.ARG_EXERCISE_ID))

    /** null while loading. */
    val library: StateFlow<List<Exercise>?> = exercises.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val exercise: StateFlow<Exercise?> = exercises.observeAll().map { all -> all.firstOrNull { it.id == exerciseId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Every variation suggested for this exercise so far, kept until the user deletes it. */
    val saved: StateFlow<List<SavedVariation>?> = variations.observeFor(exerciseId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val aiReady: StateFlow<Boolean> = ai.settings.map { it.ready }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ai.settings.value.ready)
    val model: AiModel get() = ai.settings.value.model

    private val _call = MutableStateFlow<AiCall<VariationsAnswer>>(AiCall.Idle)
    val call: StateFlow<AiCall<VariationsAnswer>> = _call.asStateFlow()
    private var job: Job? = null

    fun ask(request: String, language: String) {
        val target = exercise.value ?: return
        val all = library.value.orEmpty()
        val suggested = saved.value.orEmpty().map { it.variation.name }
        job?.cancel()
        _call.value = AiCall.Running
        job = viewModelScope.launch {
            _call.value = try {
                val result = ai.ask(FitnessPrompts.exerciseVariations(target, all, suggested, request, language), FitnessAnswers::variations)
                val added = variations.saveNew(exerciseId, result.value.variations, result.model)
                AiCall.Done(VariationsAnswer(added, result.value.baseLevel, result.model, result.costUsd, result.value.notes))
            } catch (e: AiException) {
                AiCall.Failed(e)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _call.value = AiCall.Idle
    }

    fun delete(variation: SavedVariation) {
        viewModelScope.launch { variations.delete(variation.id) }
    }

    /** Takes the AI's rating of the original exercise as its level. */
    fun setLevel(level: Int) {
        val target = exercise.value ?: return
        viewModelScope.launch { exercises.save(target.copy(level = level)) }
    }
}
