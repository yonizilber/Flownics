package com.yonizilber.flownics.feature.ai.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yonizilber.flownics.data.ai.AiService
import com.yonizilber.flownics.data.local.db.entity.FeatureSpendRow
import com.yonizilber.flownics.data.local.prefs.AiSettings
import com.yonizilber.flownics.data.local.prefs.AiSettingsStore
import com.yonizilber.flownics.domain.ai.AiException
import com.yonizilber.flownics.domain.ai.AiModel
import com.yonizilber.flownics.domain.ai.AiProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The key dialog: testing a key makes one tiny real call before it is saved. */
sealed interface KeyTest {
    data object Idle : KeyTest
    data object Testing : KeyTest
    data class Failed(val error: AiException) : KeyTest
    data object Saved : KeyTest
}

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val store: AiSettingsStore,
    private val ai: AiService,
) : ViewModel() {

    val settings: StateFlow<AiSettings> = store.settings

    val monthSpend: StateFlow<Double> = ai.observeMonthSpend()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    val spendByFeature: StateFlow<List<FeatureSpendRow>> = ai.observeMonthSpendByFeature()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _keyTest = MutableStateFlow<KeyTest>(KeyTest.Idle)
    val keyTest: StateFlow<KeyTest> = _keyTest.asStateFlow()
    private var testJob: Job? = null

    fun setProvider(provider: AiProvider) = store.setProvider(provider)
    fun setModel(model: AiModel) = store.setModel(model)
    fun removeKey(provider: AiProvider) = store.removeKey(provider)
    fun setMonthlyCap(capUsd: Double?) = store.setMonthlyCap(capUsd)

    fun testAndSave(provider: AiProvider, key: String) {
        testJob?.cancel()
        _keyTest.value = KeyTest.Testing
        testJob = viewModelScope.launch {
            _keyTest.value = try {
                ai.testKey(provider, key)
                store.setKey(provider, key)
                KeyTest.Saved
            } catch (e: AiException) {
                KeyTest.Failed(e)
            }
        }
    }

    /** For when the test failed for a reason that isn't the key (no network, overload). */
    fun saveUntested(provider: AiProvider, key: String) {
        store.setKey(provider, key)
        _keyTest.value = KeyTest.Saved
    }

    fun resetKeyTest() {
        testJob?.cancel()
        _keyTest.value = KeyTest.Idle
    }
}
