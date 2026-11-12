package com.yonizilber.flownics.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.yonizilber.flownics.data.local.prefs.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** A cue the user turned on but the device can't deliver; the player says so instead of going quiet. */
enum class CueProblem { VOICE_UNAVAILABLE, SOUND_UNAVAILABLE }

/** Beeps, vibration and spoken announcements, each gated by the user's cue settings. */
@Singleton
class WorkoutCues @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
) {
    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val audioManager = context.getSystemService(AudioManager::class.java)

    // Music keeps playing, just quieter, while a cue is spoken.
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(speechAttributes)
        .build()

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var tone: ToneGenerator? = null

    private val _problems = MutableStateFlow(emptySet<CueProblem>())
    val problems: StateFlow<Set<CueProblem>> = _problems.asStateFlow()

    private val settings get() = prefs.observeCueSettings().value

    /** Call when a workout starts or resumes: the voice engine takes a moment to load. */
    fun prepare() {
        val s = settings
        if (s.voice && tts == null) {
            ttsReady = false
            tts = TextToSpeech(context, ::onTtsInit)
        }
        if (s.sound && tone == null) {
            // ToneGenerator throws when the device can't open an audio track for it.
            tone = try {
                ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
            } catch (e: RuntimeException) {
                report(CueProblem.SOUND_UNAVAILABLE)
                null
            }
        }
    }

    fun countdownBeep() {
        if (settings.sound) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, COUNTDOWN_TONE_MS)
    }

    fun stepStarted() {
        val s = settings
        if (s.sound) tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, STEP_TONE_MS)
        if (s.vibrate) vibrate()
    }

    fun say(text: String) {
        if (!settings.voice || !ttsReady) return
        audioManager.requestAudioFocus(focusRequest)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    fun release() {
        tts?.shutdown()
        tts = null
        ttsReady = false
        tone?.release()
        tone = null
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private fun onTtsInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            report(CueProblem.VOICE_UNAVAILABLE)
            return
        }
        // Speak the app's language (the per-app language on Android 13+), not necessarily the device's.
        val result = engine.setLanguage(context.resources.configuration.locales[0])
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            report(CueProblem.VOICE_UNAVAILABLE)
            return
        }
        engine.setAudioAttributes(speechAttributes)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                audioManager.abandonAudioFocusRequest(focusRequest)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                audioManager.abandonAudioFocusRequest(focusRequest)
            }
        })
        _problems.update { it - CueProblem.VOICE_UNAVAILABLE }
        ttsReady = true
    }

    private fun vibrate() {
        val effect = VibrationEffect.createOneShot(VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
        // Alarm usage, like a kitchen timer: otherwise Android may drop it while the screen is off.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator?.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            @Suppress("DEPRECATION")
            vibrator?.vibrate(effect, attributes)
        }
    }

    private fun report(problem: CueProblem) = _problems.update { it + problem }

    private companion object {
        const val TONE_VOLUME = 90
        const val COUNTDOWN_TONE_MS = 150
        const val STEP_TONE_MS = 400
        const val VIBRATION_MS = 300L
        const val UTTERANCE_ID = "flownics-cue"
    }
}
