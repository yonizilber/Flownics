package com.yonizilber.flownics.feature.disclaimer

import androidx.lifecycle.ViewModel
import com.yonizilber.flownics.data.local.prefs.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class DisclaimerViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
) : ViewModel() {

    val accepted: StateFlow<Boolean> = appPreferences.observeDisclaimerAccepted()

    fun accept() = appPreferences.acceptDisclaimer()
}
