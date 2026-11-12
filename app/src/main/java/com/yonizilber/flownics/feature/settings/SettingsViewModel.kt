package com.yonizilber.flownics.feature.settings

import androidx.lifecycle.ViewModel
import com.yonizilber.flownics.data.local.prefs.AppPreferences
import com.yonizilber.flownics.data.local.prefs.CueSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPreferences,
) : ViewModel() {

    val cueSettings: StateFlow<CueSettings> = prefs.observeCueSettings()

    fun updateCues(change: (CueSettings) -> CueSettings) = prefs.setCueSettings(change(cueSettings.value))
}
