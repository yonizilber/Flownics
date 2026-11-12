package com.yonizilber.flownics.data.local.prefs

import android.content.Context
import com.yonizilber.flownics.data.local.crypto.ApiKeyCipher
import com.yonizilber.flownics.domain.ai.AiModel
import com.yonizilber.flownics.domain.ai.AiProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What the UI may know about the AI setup. The keys themselves never leave [AiSettingsStore]. */
data class AiSettings(
    val provider: AiProvider = AiProvider.GEMINI,
    val models: Map<AiProvider, AiModel> = AiProvider.entries.associateWith { AiModel.defaultFor(it) },
    /** "…a1b2" for each saved key, so the user can tell which key is in. */
    val keyHints: Map<AiProvider, String> = emptyMap(),
    /** A key was saved but can't be decrypted any more (e.g. data restored onto a new phone). */
    val unreadableKeys: Set<AiProvider> = emptySet(),
    /** null: no cap. */
    val monthlyCapUsd: Double? = DEFAULT_CAP_USD,
) {
    val model: AiModel get() = models.getValue(provider)
    val ready: Boolean get() = provider in keyHints

    companion object {
        // A small default protects a friend who pastes a key without setting a limit at the provider.
        const val DEFAULT_CAP_USD = 5.0
    }
}

// Lives in ai_settings.xml, which backup_rules.xml and data_extraction_rules.xml exclude from every
// backup and device transfer: the encrypted key is useless elsewhere and must not travel.
@Singleton
class AiSettingsStore @Inject constructor(
    @ApplicationContext context: Context,
    private val cipher: ApiKeyCipher,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val keys = mutableMapOf<AiProvider, String>()
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AiSettings> = _settings.asStateFlow()

    @Synchronized
    fun apiKey(provider: AiProvider): String? = keys[provider]

    @Synchronized
    fun setKey(provider: AiProvider, key: String) {
        // A pasted key often brings an invisible space or newline; no real key contains whitespace.
        val trimmed = key.trim()
        prefs.edit().putString(keyPref(provider), cipher.encrypt(trimmed)).apply()
        keys[provider] = trimmed
        _settings.value = _settings.value.copy(
            keyHints = _settings.value.keyHints + (provider to hint(trimmed)),
            unreadableKeys = _settings.value.unreadableKeys - provider,
        )
    }

    @Synchronized
    fun removeKey(provider: AiProvider) {
        prefs.edit().remove(keyPref(provider)).apply()
        keys -= provider
        _settings.value = _settings.value.copy(
            keyHints = _settings.value.keyHints - provider,
            unreadableKeys = _settings.value.unreadableKeys - provider,
        )
    }

    fun setProvider(provider: AiProvider) {
        prefs.edit().putString(KEY_PROVIDER, provider.name).apply()
        _settings.value = _settings.value.copy(provider = provider)
    }

    fun setModel(model: AiModel) {
        prefs.edit().putString(modelPref(model.provider), model.name).apply()
        _settings.value = _settings.value.copy(models = _settings.value.models + (model.provider to model))
    }

    fun setMonthlyCap(capUsd: Double?) {
        prefs.edit().apply { if (capUsd == null) putFloat(KEY_CAP, NO_CAP) else putFloat(KEY_CAP, capUsd.toFloat()) }.apply()
        _settings.value = _settings.value.copy(monthlyCapUsd = capUsd)
    }

    private fun load(): AiSettings {
        val unreadable = mutableSetOf<AiProvider>()
        AiProvider.entries.forEach { provider ->
            val stored = prefs.getString(keyPref(provider), null) ?: return@forEach
            // Fails when the Keystore key is gone; say so in settings rather than crash or pretend.
            runCatching { cipher.decrypt(stored) }
                .onSuccess { keys[provider] = it }
                .onFailure { unreadable += provider }
        }
        val cap = prefs.getFloat(KEY_CAP, AiSettings.DEFAULT_CAP_USD.toFloat())
        return AiSettings(
            provider = enumOrNull<AiProvider>(prefs.getString(KEY_PROVIDER, null)) ?: AiProvider.GEMINI,
            models = AiProvider.entries.associateWith { provider ->
                enumOrNull<AiModel>(prefs.getString(modelPref(provider), null))?.takeIf { it.provider == provider }
                    ?: AiModel.defaultFor(provider)
            },
            keyHints = keys.mapValues { hint(it.value) },
            unreadableKeys = unreadable,
            monthlyCapUsd = cap.takeIf { it != NO_CAP }?.toDouble(),
        )
    }

    private fun hint(key: String) = "…" + key.takeLast(4)

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

    private companion object {
        const val PREFS_NAME = "ai_settings"
        const val KEY_PROVIDER = "provider"
        const val KEY_CAP = "monthly_cap_usd"
        const val NO_CAP = -1f

        fun keyPref(provider: AiProvider) = "key_" + provider.name.lowercase()
        fun modelPref(provider: AiProvider) = "model_" + provider.name.lowercase()
    }
}
