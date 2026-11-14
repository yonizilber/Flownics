package com.yonizilber.flownics.data.garmin

import android.content.Context
import com.yonizilber.flownics.data.local.crypto.ApiKeyCipher
import com.yonizilber.flownics.data.repository.ActivityRepository
import com.yonizilber.flownics.domain.fit.FitActivity
import com.yonizilber.flownics.domain.fit.FitException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Why talking to Garmin failed, each with its own message in the app. */
sealed class GarminException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NoKey : GarminException("No Garmin key")
    class KeyRejected : GarminException("Garmin refused the key")
    class Http(val status: Int) : GarminException("Garmin answered $status")
    class RateLimited : GarminException("Garmin is limiting requests")
    class Network(cause: Throwable) : GarminException("Network error", cause)
    class Unexpected(val detail: String) : GarminException(detail)
    class BadFile(val detail: String) : GarminException(detail)
}

data class GarminSettings(
    val hasKey: Boolean = false,
    val lastSyncAtMillis: Long? = null,
    /** The failing [GarminException]'s class name (shown in words by the app), and its detail. */
    val lastError: String? = null,
    val lastErrorDetail: String? = null,
)

/** What one import added: the activity's id, and whether it was there already. */
data class ImportOutcome(val activityId: Long, val alreadyThere: Boolean)

/** [wellness] is how the all-day data went: days updated, or why it stopped. */
data class SyncOutcome(val added: Int, val alreadyThere: Int, val failed: List<String>, val wellness: WellnessOutcome)

/**
 * Turns a watch recording (a .fit file, or the .zip Garmin wraps it in) into an activity in the
 * history. The same recording is never added twice: it is recognised by the FIT file's own id.
 * The original file is kept on the phone, for later analysis.
 */
@Singleton
class FitImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val activities: ActivityRepository,
    private val linker: RecordingLinker,
) {
    suspend fun import(bytes: ByteArray): ImportOutcome = withContext(Dispatchers.IO) {
        val fit = unwrap(bytes)
        val result = try {
            FitActivity.read(fit)
        } catch (e: FitException) {
            throw GarminException.BadFile(e.message.orEmpty())
        }
        activities.byExternalId(result.externalId)?.let { return@withContext ImportOutcome(it.id, alreadyThere = true) }
        val id = activities.save(result.activity)
        File(context.filesDir, FIT_DIR).apply { mkdirs() }.resolve("$id.fit").writeBytes(fit)
        // Recorded during a workout in the app? Then it joins that workout.
        linker.link()
        ImportOutcome(id, alreadyThere = false)
    }

    /** The .fit inside a Garmin .zip, or the bytes themselves. */
    private fun unwrap(bytes: ByteArray): ByteArray {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return bytes
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.lowercase().endsWith(".fit")) {
                    return readLimited(zip)
                }
            }
        }
        throw GarminException.BadFile("the .zip has no .fit file in it")
    }

    private fun readLimited(stream: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_FIT_BYTES) throw GarminException.BadFile("the .fit file is larger than ${MAX_FIT_BYTES / 1_000_000} MB")
        }
        return out.toByteArray()
    }

    companion object {
        const val FIT_DIR = "fit"
        const val MAX_FIT_BYTES = 50_000_000
    }
}

/**
 * Pulls the user's own activities from Garmin Connect with their personal API key (Garmin's
 * "Personal" tier: read-only, the user's own account). The key is kept encrypted with the
 * Android Keystore and never leaves the phone except to Garmin; the 24-hour access token it is
 * exchanged for lives only in memory. Shapes as in Garmin's own SDK (connect-personal-api-python).
 */
@Singleton
class GarminSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: GarminClient,
    private val cipher: ApiKeyCipher,
    private val json: Json,
    private val importer: FitImporter,
    private val wellness: WellnessSync,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<GarminSettings> = _settings.asStateFlow()
    private val mutex = Mutex()

    /** Checks the key with Garmin before keeping it, so a mistyped key is caught at once. */
    suspend fun saveKey(key: String) = withContext(Dispatchers.IO) {
        val trimmed = key.trim()
        client.forget()
        client.exchange(trimmed)
        prefs.edit().putString(KEY_KEY, cipher.encrypt(trimmed)).remove(KEY_ERROR).remove(KEY_ERROR_DETAIL).apply()
        _settings.value = load()
        GarminSyncWorker.schedule(context, hasKey = true)
    }

    fun removeKey() {
        client.forget()
        prefs.edit().remove(KEY_KEY).remove(KEY_ERROR).apply()
        _settings.value = load()
        GarminSyncWorker.schedule(context, hasKey = false)
    }

    /** True when there's a key and the last sync is older than [hours]. */
    fun due(hours: Long): Boolean {
        val s = _settings.value
        return s.hasKey && (s.lastSyncAtMillis == null || System.currentTimeMillis() - s.lastSyncAtMillis > hours * 3_600_000)
    }

    /**
     * Imports every activity since the last sync (two days of overlap, so a late upload isn't
     * missed; the first time, the last [FIRST_DAYS] days). Activities already imported are
     * skipped by their Garmin id without downloading them again.
     */
    suspend fun sync(): SyncOutcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val key = storedKey() ?: throw GarminException.NoKey()
                val bearer = client.bearer(key)
                val since = _settings.value.lastSyncAtMillis?.let { LocalDate.ofEpochDay(it / 86_400_000).minusDays(2) }
                    ?: LocalDate.now().minusDays(FIRST_DAYS)
                val ids = activityIds(bearer, since, LocalDate.now().plusDays(1))
                val done = prefs.getStringSet(KEY_DONE_IDS, emptySet()).orEmpty().toMutableSet()
                val fresh = ids.filter { it !in done }
                var added = 0
                var already = ids.size - fresh.size
                val failed = mutableListOf<String>()
                fresh.forEach { id ->
                    try {
                        val outcome = importer.import(download(bearer, id))
                        if (outcome.alreadyThere) already++ else added++
                    } catch (e: GarminException.BadFile) {
                        failed += "$id: ${e.detail}"
                    }
                    // Remembered either way: a file that can't be read won't read next time either.
                    done += id
                }
                prefs.edit()
                    .putStringSet(KEY_DONE_IDS, done.toList().takeLast(MAX_REMEMBERED).toSet())
                    .putLong(KEY_SYNCED, System.currentTimeMillis())
                    .remove(KEY_ERROR)
                    .apply()
                _settings.value = load()
                // Then sleep, HRV and the rest of the day: its own failure never undoes the activities.
                val days = wellness.sync(bearer)
                SyncOutcome(added, already, failed, days)
            } catch (e: GarminException) {
                prefs.edit().putString(KEY_ERROR, e.javaClass.simpleName).putString(KEY_ERROR_DETAIL, e.message).apply()
                _settings.value = load()
                throw e
            }
        }
    }

    private fun storedKey(): String? = prefs.getString(KEY_KEY, null)?.let { runCatching { cipher.decrypt(it) }.getOrNull() }

    /** One request for the data explorer: what Garmin answers, as it came. */
    suspend fun explore(path: String): ByteArray = withContext(Dispatchers.IO) {
        val key = storedKey() ?: throw GarminException.NoKey()
        client.get(client.bearer(key), BASE + path, "application/json")
    }

    /** Ids of the activities between two dates, page by page. */
    private fun activityIds(bearer: String, from: LocalDate, to: LocalDate): List<String> {
        val ids = mutableListOf<String>()
        var start = 0
        while (true) {
            val url = "$BASE$SEARCH_PATH?start=$start&limit=$PAGE&startDate=$from&endDate=$to"
            val page = json.parseToJsonElement(String(client.get(bearer, url, "application/json")))
            val items = itemsOf(page)
            val pageIds = items.mapNotNull { item -> (item["activityId"] as? JsonPrimitive)?.contentOrNull }
            if (items.isNotEmpty() && pageIds.isEmpty()) {
                // Fail visibly: the shape isn't what Garmin's SDK and service use. Field names only, no data.
                throw GarminException.Unexpected("unexpected activity list (fields: ${items.first().keys.take(12).joinToString()})")
            }
            ids += pageIds
            if (items.size < PAGE || start > MAX_ITEMS) break
            start += PAGE
        }
        return ids.distinct()
    }

    /** The list is a JSON array, or an object holding one; either is accepted. */
    private fun itemsOf(root: JsonElement): List<JsonObject> = when (root) {
        is JsonArray -> root.filterIsInstance<JsonObject>()
        is JsonObject -> root.values.filterIsInstance<JsonArray>().firstOrNull()?.filterIsInstance<JsonObject>().orEmpty()
        else -> emptyList()
    }

    private fun download(bearer: String, id: String): ByteArray =
        client.get(bearer, "$BASE$DOWNLOAD_PATH$id", "application/zip, application/x-zip-compressed, application/octet-stream")

    private fun load() = GarminSettings(
        hasKey = prefs.contains(KEY_KEY),
        lastSyncAtMillis = prefs.getLong(KEY_SYNCED, -1).takeIf { it > 0 },
        lastError = prefs.getString(KEY_ERROR, null),
        lastErrorDetail = prefs.getString(KEY_ERROR_DETAIL, null),
    )

    companion object {
        const val PREFS = "garmin_settings"
        private const val KEY_KEY = "key"
        private const val KEY_SYNCED = "last_synced"
        private const val KEY_ERROR_DETAIL = "last_error_detail"
        private const val KEY_ERROR = "last_error"
        private const val KEY_DONE_IDS = "imported_ids"
        const val BASE = GarminClient.BASE
        private const val SEARCH_PATH = "/activitylist-service/activities/search/activities"
        private const val DOWNLOAD_PATH = "/download-service/files/activity/"
        private const val PAGE = 50
        private const val MAX_ITEMS = 2_000
        private const val MAX_REMEMBERED = 5_000
        private const val FIRST_DAYS = 30L
    }
}

/**
 * Garmin's personal API over HTTPS: the key is swapped for an access token (kept in memory only)
 * and each request carries it. Shared by the activity sync, the all-day data and the explorer.
 */
@Singleton
class GarminClient @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
) {
    private var token: Pair<String, Long>? = null

    fun forget() {
        token = null
    }

    fun bearer(key: String): String {
        token?.let { (value, expires) -> if (System.currentTimeMillis() < expires - REFRESH_MARGIN_MS) return value }
        return exchange(key)
    }

    /** The key is swapped for an access token: a plain JWT in the body, valid about 24 hours. */
    fun exchange(key: String): String {
        val request = Request.Builder()
            .url(BASE + TOKEN_PATH)
            .header("Accept", "*/*")
            .header("Authorization", "ApiKey $key")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        val body = call(request) { status -> if (status == 401 || status == 403) GarminException.KeyRejected() else GarminException.Http(status) }
        val jwt = String(body).trim()
        val expires = runCatching {
            val payload = String(Base64.getUrlDecoder().decode(jwt.split('.')[1].let { it + "=".repeat((4 - it.length % 4) % 4) }))
            (json.parseToJsonElement(payload) as JsonObject)["exp"]!!.toString().toDouble().toLong() * 1000
        }.getOrElse { throw GarminException.Unexpected("Garmin's token couldn't be read") }
        token = jwt to expires
        return jwt
    }

    fun get(bearer: String, url: String, accept: String): ByteArray {
        val request = Request.Builder().url(url).header("Accept", accept).header("Authorization", "Bearer $bearer").get().build()
        return call(request) { status ->
            if (status == 401) token = null
            if (status == 401 || status == 403) GarminException.KeyRejected() else GarminException.Http(status)
        }
    }

    private fun call(request: Request, onError: (Int) -> GarminException): ByteArray = try {
        http.newCall(request).execute().use { response ->
            if (response.code == 429) throw GarminException.RateLimited()
            if (!response.isSuccessful) throw onError(response.code)
            response.body?.bytes() ?: ByteArray(0)
        }
    } catch (e: IOException) {
        throw GarminException.Network(e)
    }

    companion object {
        const val BASE = "https://personal.connectapi.garmin.com"
        private const val TOKEN_PATH = "/personalapitoken-service/personal-api/personal-api-key/token"
        private const val REFRESH_MARGIN_MS = 3_600_000L
    }
}
