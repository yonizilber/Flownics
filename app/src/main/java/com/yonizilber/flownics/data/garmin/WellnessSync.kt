package com.yonizilber.flownics.data.garmin

import android.content.Context
import com.yonizilber.flownics.data.local.db.dao.WellnessDao
import com.yonizilber.flownics.data.local.db.entity.GarminRawEntity
import com.yonizilber.flownics.data.local.db.entity.WellnessDayEntity
import com.yonizilber.flownics.domain.wellness.Fetched
import com.yonizilber.flownics.domain.wellness.GarminEndpoint
import com.yonizilber.flownics.domain.wellness.RawAnswer
import com.yonizilber.flownics.domain.wellness.WellnessDay
import com.yonizilber.flownics.domain.wellness.WellnessFetchPlan
import com.yonizilber.flownics.domain.wellness.WellnessParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** How the all-day data went in one sync: requests answered, and the error that stopped it, if one did. */
data class WellnessOutcome(val requests: Int, val errorKind: String? = null, val errorDetail: String? = null)

data class WellnessSettings(
    /** How far back the all-day data is fetched. */
    val historyDays: Int = DEFAULT_HISTORY_DAYS,
    val lastSyncAtMillis: Long? = null,
    val lastErrorKind: String? = null,
    val lastErrorDetail: String? = null,
    /** Fields looked for in the latest answers but not found there, by endpoint: shown, not hidden. */
    val missing: Map<String, List<String>> = emptyMap(),
) {
    companion object {
        const val DEFAULT_HISTORY_DAYS = 30
    }
}

/**
 * Sleep (with its stages), HRV, resting heart rate, Body Battery, stress, steps and VO2max from
 * Garmin, day by day. Each answer is kept as it came (gzipped) and the days are built from all of
 * them by [WellnessParser], so a better reading later rebuilds them without asking Garmin again.
 */
@Singleton
class WellnessSync @Inject constructor(
    @ApplicationContext context: Context,
    private val client: GarminClient,
    private val dao: WellnessDao,
    private val json: Json,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<WellnessSettings> = _settings.asStateFlow()

    fun setHistoryDays(days: Int) {
        prefs.edit().putInt(KEY_HISTORY, days).apply()
        _settings.value = load()
    }

    fun observe(from: LocalDate, to: LocalDate): Flow<List<WellnessDay>> =
        dao.observe(from.toString(), to.toString()).map { rows -> rows.mapNotNull(::decode) }

    suspend fun days(from: LocalDate, to: LocalDate): List<WellnessDay> =
        dao.between(from.toString(), to.toString()).mapNotNull(::decode)

    /**
     * Fetches what's missing, oldest first, a little apart so as not to flood Garmin. Never
     * throws: a failure stops it where it is and is reported (and kept for the settings screen);
     * the answers saved so far count, and the next sync goes on from there.
     */
    suspend fun sync(bearer: String, today: LocalDate = LocalDate.now()): WellnessOutcome = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val done = dao.rawIndex().mapNotNull { r ->
            runCatching { Fetched(GarminEndpoint.valueOf(r.endpoint), LocalDate.parse(r.startDate), LocalDate.parse(r.endDate), r.fetchedAtMillis) }.getOrNull()
        }
        val from = today.minusDays(_settings.value.historyDays.toLong() - 1)
        val requests = WellnessFetchPlan.requests(GarminEndpoint.entries.filter { it.synced }, done, from, today, zone)
            // Oldest first: what's missing gets filled in order.
            .sortedBy { it.second.start }
        var answered = 0
        var error: GarminException? = null
        for ((endpoint, range) in requests) {
            try {
                val bytes = client.get(bearer, GarminSync.BASE + endpoint.pathFor(range.start, range.endInclusive), "application/json")
                dao.putRaw(GarminRawEntity(endpoint.name, range.start.toString(), range.endInclusive.toString(), System.currentTimeMillis(), gzip(bytes)))
                answered++
                delay(PAUSE_MS)
            } catch (e: GarminException.Http) {
                // Not every watch has every metric (say, no SpO2): one missing endpoint doesn't stop the rest.
                if (e.status == 404 || e.status == 400) {
                    error = e
                    continue
                }
                error = e
                break
            } catch (e: GarminException) {
                error = e
                break
            }
        }
        if (answered > 0 || error == null) rebuild()
        prefs.edit().apply {
            putLong(KEY_SYNCED, System.currentTimeMillis())
            if (error != null) putString(KEY_ERROR, error.javaClass.simpleName).putString(KEY_ERROR_DETAIL, error.message) else remove(KEY_ERROR).remove(KEY_ERROR_DETAIL)
        }.apply()
        _settings.value = load()
        WellnessOutcome(answered, error?.javaClass?.simpleName, error?.message)
    }

    /** All days rebuilt from all saved answers: deterministic, the same answers give the same days. */
    suspend fun rebuild() = withContext(Dispatchers.IO) {
        val answers = mutableListOf<RawAnswer>()
        val missing = mutableMapOf<String, List<String>>()
        GarminEndpoint.entries.filter(WellnessParser::reads).forEach { endpoint ->
            val rows = dao.raw(endpoint.name)
            rows.forEach { r ->
                val root = try {
                    json.parseToJsonElement(String(gunzip(r.gzip)))
                } catch (_: SerializationException) {
                    null
                } ?: return@forEach
                answers += RawAnswer(endpoint, LocalDate.parse(r.startDate), LocalDate.parse(r.endDate), r.fetchedAtMillis, root)
            }
            // What the most recent answer of each kind lacked: said in the settings, never hidden.
            answers.filter { it.endpoint == endpoint }.maxByOrNull { it.fetchedAtMillis }?.let { a ->
                val reading = WellnessParser.read(a.endpoint, a.start, a.end, a.root)
                if (reading.missing.isNotEmpty()) missing[endpoint.name] = reading.missing
            }
        }
        val days = WellnessParser.build(answers)
        dao.replaceDays(days.map { WellnessDayEntity(it.date.toString(), json.encodeToString(WellnessDay.serializer(), it)) })
        prefs.edit().putString(KEY_MISSING, json.encodeToString(MissingMap.serializer(), MissingMap(missing))).putInt(KEY_READER, READER_VERSION).apply()
        _settings.value = load()
    }

    /** When the reading changed since the days were built (an app update), they are built again. */
    suspend fun rebuildIfReaderChanged() {
        if (prefs.getInt(KEY_READER, 0) != READER_VERSION && dao.rawCount() > 0) rebuild()
    }

    /** Forgets every downloaded day (the user's choice in the settings). */
    suspend fun clear() = withContext(Dispatchers.IO) {
        dao.clearRaw()
        dao.clearDays()
        prefs.edit().remove(KEY_MISSING).remove(KEY_SYNCED).apply()
        _settings.value = load()
    }

    private fun decode(row: WellnessDayEntity): WellnessDay? =
        try {
            json.decodeFromString(WellnessDay.serializer(), row.dataJson)
        } catch (_: SerializationException) {
            null
        }

    private fun load(): WellnessSettings = WellnessSettings(
        historyDays = prefs.getInt(KEY_HISTORY, WellnessSettings.DEFAULT_HISTORY_DAYS),
        lastSyncAtMillis = prefs.getLong(KEY_SYNCED, -1).takeIf { it > 0 },
        lastErrorKind = prefs.getString(KEY_ERROR, null),
        lastErrorDetail = prefs.getString(KEY_ERROR_DETAIL, null),
        missing = prefs.getString(KEY_MISSING, null)?.let { runCatching { json.decodeFromString(MissingMap.serializer(), it).byEndpoint }.getOrNull() }.orEmpty(),
    )

    @kotlinx.serialization.Serializable
    private data class MissingMap(val byEndpoint: Map<String, List<String>>)

    companion object {
        const val PREFS = "wellness_prefs"
        private const val KEY_HISTORY = "history_days"
        private const val KEY_SYNCED = "last_synced"
        private const val KEY_ERROR = "last_error"
        private const val KEY_ERROR_DETAIL = "last_error_detail"
        private const val KEY_MISSING = "missing"
        private const val KEY_READER = "reader_version"
        /** Raise when [WellnessParser] reads differently, so saved answers are read again. */
        const val READER_VERSION = 1
        private const val PAUSE_MS = 300L

        fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

        fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
    }
}
