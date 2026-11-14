package com.yonizilber.flownics.feature.garmin

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yonizilber.flownics.R
import com.yonizilber.flownics.data.garmin.FitImporter
import com.yonizilber.flownics.data.garmin.GarminException
import com.yonizilber.flownics.data.garmin.GarminSettings
import com.yonizilber.flownics.data.garmin.GarminSync
import com.yonizilber.flownics.data.garmin.WellnessSettings
import com.yonizilber.flownics.data.garmin.WellnessSync
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A finished sync or import, said in words; [error] when it failed. */
data class GarminMessage(val text: String, val error: Boolean, val openActivityId: Long? = null)

/** Syncing from Garmin and importing .fit files, shared by the journal and the Garmin settings. */
@HiltViewModel
class GarminViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sync: GarminSync,
    private val importer: FitImporter,
    private val wellness: WellnessSync,
) : ViewModel() {

    val settings: StateFlow<GarminSettings> = sync.settings
    val wellnessSettings: StateFlow<WellnessSettings> = wellness.settings

    fun setHistoryDays(days: Int) = wellness.setHistoryDays(days)

    fun clearWellness() {
        viewModelScope.launch { wellness.clear() }
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _message = MutableStateFlow<GarminMessage?>(null)
    val message: StateFlow<GarminMessage?> = _message.asStateFlow()

    fun dismiss() {
        _message.value = null
    }

    /** Syncs when there's a key and the last sync is a few hours old (quietly, unless something fails). */
    fun syncIfDue() {
        if (sync.due(hours = AUTO_SYNC_HOURS)) syncNow(quiet = true)
    }

    fun syncNow(quiet: Boolean = false) = run {
        val r = sync.sync()
        val text = buildString {
            append(context.resources.getQuantityString(R.plurals.garmin_synced, r.added, r.added))
            if (r.failed.isNotEmpty()) append(" " + context.getString(R.string.garmin_some_failed, r.failed.size))
            if (r.wellness.requests > 0) append(" " + context.getString(R.string.wellness_synced))
            r.wellness.errorKind?.let { append(" " + context.getString(R.string.wellness_sync_stopped, errorText(context, it, r.wellness.errorDetail))) }
        }
        val problem = r.failed.isNotEmpty() || r.wellness.errorKind != null
        if (quiet && r.added == 0 && !problem) null else GarminMessage(text, problem)
    }

    fun importFile(uri: Uri) = run {
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw GarminException.BadFile(context.getString(R.string.fit_unreadable))
        }
        val outcome = importer.import(bytes)
        GarminMessage(
            context.getString(if (outcome.alreadyThere) R.string.fit_already else R.string.fit_imported),
            error = false,
            openActivityId = outcome.activityId,
        )
    }

    fun saveKey(key: String) = run {
        sync.saveKey(key)
        GarminMessage(context.getString(R.string.garmin_key_ok), error = false)
    }

    fun removeKey() = sync.removeKey()

    private fun run(task: suspend () -> GarminMessage?) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            _message.value = try {
                task()
            } catch (e: GarminException) {
                GarminMessage(errorText(context, e.javaClass.simpleName, e.message), error = true)
            }
            _busy.value = false
        }
    }

    companion object {
        const val AUTO_SYNC_HOURS = 3L

        /** A failure in words, from the exception's class name (also how the last error is kept). */
        fun errorText(context: Context, kind: String?, detail: String?): String = when (kind) {
            GarminException.NoKey::class.java.simpleName -> context.getString(R.string.garmin_error_no_key)
            GarminException.KeyRejected::class.java.simpleName -> context.getString(R.string.garmin_error_key)
            GarminException.Network::class.java.simpleName -> context.getString(R.string.garmin_error_network)
            GarminException.RateLimited::class.java.simpleName -> context.getString(R.string.garmin_error_rate)
            GarminException.Http::class.java.simpleName -> context.getString(R.string.garmin_error_other, detail.orEmpty())
            GarminException.BadFile::class.java.simpleName -> context.getString(R.string.fit_error, detail.orEmpty())
            else -> context.getString(R.string.garmin_error_other, detail.orEmpty())
        }
    }
}
