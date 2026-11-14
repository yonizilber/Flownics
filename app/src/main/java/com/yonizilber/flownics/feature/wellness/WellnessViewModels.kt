package com.yonizilber.flownics.feature.wellness

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yonizilber.flownics.data.garmin.GarminException
import com.yonizilber.flownics.data.garmin.GarminSync
import com.yonizilber.flownics.data.garmin.WellnessSync
import com.yonizilber.flownics.domain.wellness.GarminEndpoint
import com.yonizilber.flownics.domain.wellness.JsonOutline
import com.yonizilber.flownics.domain.wellness.WellnessDay
import com.yonizilber.flownics.domain.wellness.WellnessParser
import com.yonizilber.flownics.feature.garmin.GarminViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.LocalDate
import javax.inject.Inject

/** The days of sleep and recovery, newest first. */
@HiltViewModel
class WellnessViewModel @Inject constructor(wellness: WellnessSync) : ViewModel() {
    val days: StateFlow<List<WellnessDay>?> = wellness.observe(LocalDate.now().minusDays(DAYS), LocalDate.now())
        .map { it.sortedByDescending(WellnessDay::date) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    companion object {
        const val DAYS = 60L
    }
}

/** What one request to Garmin gave, for looking at: its size, shape, what Flownics read from it, and the text. */
data class ExplorerResult(
    val endpoint: GarminEndpoint,
    val start: LocalDate,
    val end: LocalDate,
    val bytes: ByteArray,
    val millis: Long,
    val outline: List<JsonOutline.Line>,
    val pretty: String,
    /** What [WellnessParser] made of it, when it reads this endpoint. */
    val reading: WellnessParser.Reading?,
    val notJson: Boolean,
)

/**
 * The data explorer: ask Garmin any of the known endpoints for a day (or a week) and see the
 * answer as it is. Nothing is saved unless the user saves it to a file of their choosing.
 */
@HiltViewModel
class GarminExplorerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sync: GarminSync,
    private val json: Json,
) : ViewModel() {
    private val pretty = Json(json) { prettyPrint = true }

    private val _result = MutableStateFlow<ExplorerResult?>(null)
    val result: StateFlow<ExplorerResult?> = _result.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    fun fetch(endpoint: GarminEndpoint, day: LocalDate) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        _saved.value = false
        // A range endpoint shows the week up to the day.
        val start = if (endpoint.maxDays > 1) day.minusDays(6) else day
        viewModelScope.launch {
            try {
                val began = System.currentTimeMillis()
                val bytes = sync.explore(endpoint.pathFor(start, day))
                val took = System.currentTimeMillis() - began
                _result.value = withContext(Dispatchers.Default) { describe(endpoint, start, day, bytes, took) }
            } catch (e: GarminException) {
                _error.value = GarminViewModel.errorText(context, e.javaClass.simpleName, e.message)
            }
            _busy.value = false
        }
    }

    private fun describe(endpoint: GarminEndpoint, start: LocalDate, end: LocalDate, bytes: ByteArray, millis: Long): ExplorerResult {
        val root: JsonElement? = try {
            json.parseToJsonElement(String(bytes))
        } catch (_: SerializationException) {
            null
        }
        return ExplorerResult(
            endpoint, start, end, bytes, millis,
            outline = root?.let { JsonOutline.lines(it) }.orEmpty(),
            pretty = (root?.let { pretty.encodeToString(JsonElement.serializer(), it) } ?: String(bytes)).take(MAX_SHOWN),
            reading = root?.takeIf { WellnessParser.reads(endpoint) }?.let { WellnessParser.read(endpoint, start, end, it) },
            notJson = root == null,
        )
    }

    /** The answer exactly as it came, to a file the user picked. */
    fun save(uri: Uri) {
        val bytes = _result.value?.bytes ?: return
        viewModelScope.launch {
            _saved.value = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null }.getOrDefault(false)
            }
            if (!_saved.value) _error.value = context.getString(com.yonizilber.flownics.R.string.explorer_save_failed)
        }
    }

    companion object {
        /** The screen shows the start of a very long answer; the saved file has all of it. */
        const val MAX_SHOWN = 60_000
    }
}
