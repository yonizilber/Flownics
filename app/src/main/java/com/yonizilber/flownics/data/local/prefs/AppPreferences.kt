package com.yonizilber.flownics.data.local.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

// Small non-secret app state. Secrets (the AI key) live in a separate, backup-excluded file.
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val disclaimerAccepted = MutableStateFlow(
        prefs.getInt(KEY_DISCLAIMER_VERSION, 0) >= CURRENT_DISCLAIMER_VERSION,
    )

    fun observeDisclaimerAccepted(): StateFlow<Boolean> = disclaimerAccepted.asStateFlow()

    fun acceptDisclaimer() {
        prefs.edit().putInt(KEY_DISCLAIMER_VERSION, CURRENT_DISCLAIMER_VERSION).apply()
        disclaimerAccepted.value = true
    }

    private val cueSettings = MutableStateFlow(
        CueSettings(
            sound = prefs.getBoolean(KEY_CUE_SOUND, true),
            voice = prefs.getBoolean(KEY_CUE_VOICE, true),
            vibrate = prefs.getBoolean(KEY_CUE_VIBRATE, true),
        ),
    )

    fun observeCueSettings(): StateFlow<CueSettings> = cueSettings.asStateFlow()

    fun setCueSettings(settings: CueSettings) {
        prefs.edit()
            .putBoolean(KEY_CUE_SOUND, settings.sound)
            .putBoolean(KEY_CUE_VOICE, settings.voice)
            .putBoolean(KEY_CUE_VIBRATE, settings.vibrate)
            .apply()
        cueSettings.value = settings
    }

    private companion object {
        const val PREFS_NAME = "app_prefs"
        const val KEY_DISCLAIMER_VERSION = "disclaimer_accepted_version"
        const val KEY_CUE_SOUND = "cue_sound"
        const val KEY_CUE_VOICE = "cue_voice"
        const val KEY_CUE_VIBRATE = "cue_vibrate"

        // Bump when the disclaimer text changes materially, so every user sees and accepts it again.
        const val CURRENT_DISCLAIMER_VERSION = 1
    }
}

/** How the workout player signals each step while the user isn't looking at the screen. */
data class CueSettings(
    val sound: Boolean,
    val voice: Boolean,
    val vibrate: Boolean,
)
