package com.yonizilber.flownics.data.ai

import com.yonizilber.flownics.data.local.db.dao.AiDao
import com.yonizilber.flownics.data.local.db.entity.AiUsageEntity
import com.yonizilber.flownics.data.local.db.entity.ExerciseAiNoteEntity
import com.yonizilber.flownics.data.local.db.entity.FeatureSpendRow
import com.yonizilber.flownics.data.local.prefs.AiSettings
import com.yonizilber.flownics.data.local.prefs.AiSettingsStore
import com.yonizilber.flownics.data.remote.claude.ClaudeAiClient
import com.yonizilber.flownics.data.remote.gemini.GeminiAiClient
import com.yonizilber.flownics.domain.ai.AgentRequest
import com.yonizilber.flownics.domain.ai.AgentStep
import com.yonizilber.flownics.domain.ai.AgentToolResult
import com.yonizilber.flownics.domain.ai.AgentTurn
import com.yonizilber.flownics.domain.ai.AiClient
import com.yonizilber.flownics.domain.ai.AiDepth
import com.yonizilber.flownics.domain.ai.AiException
import com.yonizilber.flownics.domain.ai.AiFeature
import com.yonizilber.flownics.domain.ai.AiModel
import com.yonizilber.flownics.domain.ai.AiProvider
import com.yonizilber.flownics.domain.ai.AiReply
import com.yonizilber.flownics.domain.ai.AiResult
import com.yonizilber.flownics.domain.ai.AiRequest
import com.yonizilber.flownics.domain.ai.AiSource
import com.yonizilber.flownics.domain.ai.AiTurn
import com.yonizilber.flownics.domain.ai.AiUsage
import com.yonizilber.flownics.domain.ai.AiOutcome
import android.os.SystemClock
import com.yonizilber.flownics.domain.ai.AiProgress
import com.yonizilber.flownics.domain.ai.logDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one way the app talks to an AI. Every call is started by the user, goes to the provider they
 * chose with their own key, is checked against their monthly cap first, and is logged with its cost.
 */
@Singleton
class AiService @Inject constructor(
    private val store: AiSettingsStore,
    private val dao: AiDao,
    private val json: Json,
    gemini: GeminiAiClient,
    claude: ClaudeAiClient,
) {
    private val clients: Map<AiProvider, AiClient> = listOf(gemini, claude).associateBy { it.provider }

    val settings: StateFlow<AiSettings> = store.settings

    private val _progress = MutableStateFlow<AiProgress?>(null)

    /** What the latest running call is doing, for the waiting card; null when none runs. */
    val progress: StateFlow<AiProgress?> = _progress.asStateFlow()

    // The call [progress] belongs to: an older call still finishing doesn't overwrite a newer one's.
    @Volatile
    private var reporting: Any? = null

    private fun report(token: Any, progress: AiProgress?) {
        synchronized(this) { if (reporting === token) _progress.value = progress }
    }

    fun observeMonthSpend(): Flow<Double> = dao.observeSpentSince(monthStartMillis())

    fun observeMonthSpendByFeature(): Flow<List<FeatureSpendRow>> = dao.observeSpendByFeature(monthStartMillis())

    suspend fun run(request: AiRequest): AiReply {
        val (model, key) = ready()
        return call(request, model, key) { it }
    }

    fun observeRecent(): Flow<List<AiActivity>> = dao.observeRecent(RECENT_LIMIT).map { rows ->
        rows.map { row ->
            AiActivity(
                atMillis = row.atMillis,
                feature = row.feature,
                subject = row.subject,
                model = AiModel.byId(row.modelId),
                modelId = row.modelId,
                usage = AiUsage(row.inputTokens, row.outputTokens, row.webSearches),
                costUsd = row.costUsd,
                durationMs = row.durationMs,
                outcome = AiOutcome.entries.firstOrNull { it.name == row.outcome } ?: AiOutcome.FAILED,
                detail = row.detail,
            )
        }
    }

    /** The chosen model and its key, once the monthly cap allows another call. */
    private suspend fun ready(): Pair<AiModel, String> {
        val s = store.settings.value
        val key = store.apiKey(s.provider) ?: throw AiException.NoKey(s.provider)
        s.monthlyCapUsd?.let { cap ->
            val spent = dao.spentSince(monthStartMillis())
            if (spent >= cap) throw AiException.CapReached(spent, cap)
        }
        return s.model to key
    }

    /** [run], then [parse] the answer (which throws [AiException.BadResponse] on the wrong shape). */
    suspend fun <T> ask(request: AiRequest, parse: (String) -> T): AiResult<T> {
        val (model, key) = ready()
        return call(request, model, key) { AiResult(parse(it.text), it.sources, it.model, it.costUsd) }
    }

    /** A tiny real call with a key before saving it, so a typo is caught at once. */
    suspend fun testKey(provider: AiProvider, key: String): AiReply {
        val request = AiRequest(
            feature = AiFeature.KEY_TEST,
            system = "Reply with the single word OK.",
            turns = listOf(AiTurn(fromUser = true, text = "Test")),
            maxOutputTokens = 2_000,
            depth = AiDepth.LIGHT,
        )
        return call(request, store.settings.value.models.getValue(provider), key.trim()) { it }
    }

    suspend fun saveExerciseNote(exerciseId: Long, note: ExerciseAiNote) {
        dao.insertNote(
            ExerciseAiNoteEntity(
                exerciseId = exerciseId,
                atMillis = note.atMillis,
                modelId = note.modelId,
                summary = note.summary,
                appliedJson = json.encodeToString(note.applied),
                sourcesJson = json.encodeToString(note.sources.map { SourceJson(it.title, it.url) }),
            ),
        )
    }

    fun observeExerciseNotes(exerciseId: Long): Flow<List<ExerciseAiNote>> = dao.observeNotes(exerciseId).map { rows ->
        rows.map { row ->
            ExerciseAiNote(
                atMillis = row.atMillis,
                // A model this version doesn't know (from an import) still shows, by its id.
                model = AiModel.byId(row.modelId),
                modelId = row.modelId,
                summary = row.summary,
                applied = runCatching { json.decodeFromString<List<String>>(row.appliedJson) }.getOrDefault(emptyList()),
                sources = runCatching { json.decodeFromString<List<SourceJson>>(row.sourcesJson) }.getOrDefault(emptyList())
                    .map { AiSource(it.title, it.url) },
            )
        }
    }

    /** Starts an agent turn with the chosen model and key. Each step is then sent with [agentStep]. */
    suspend fun startAgentTurn(request: AgentRequest): AgentSession {
        val (model, key) = ready()
        return AgentSession(clients.getValue(model.provider).startTurn(request, model, key), model)
    }

    /**
     * One step of an agent turn, guarded and logged like any other call. The monthly cap is
     * checked before every step, so a long turn can't run past it.
     */
    suspend fun agentStep(session: AgentSession, results: List<AgentToolResult>, subject: String): AgentStep {
        ready()
        return guarded(AiFeature.COACH, subject, webSearch = false, session.model, work = { p -> session.turn.next(results, p) }, billed = { it.model to it.usage }) { it }
    }

    private suspend fun <T> call(request: AiRequest, model: AiModel, key: String, use: (AiReply) -> T): T {
        val client = clients.getValue(model.provider)
        return guarded(request.feature, request.subject, request.webSearch, model, work = { p -> client.complete(request, model, key, p) }, billed = { it.model to it.usage }, use = use)
    }

    /**
     * Runs one streamed call and logs it however it ends: answered, failed, timed out or cancelled
     * by the user. A slow answer that keeps arriving is waited for (up to a generous overall
     * limit); one where nothing arrives for [STALL_MS] is stopped, since it is stuck rather than
     * slow. [use] turns the answer into what the screen needs; if that fails (an answer in the
     * wrong shape) the call is logged as failed, with its cost.
     */
    private suspend fun <R, T> guarded(
        feature: AiFeature,
        subject: String,
        webSearch: Boolean,
        model: AiModel,
        work: suspend ((AiProgress) -> Unit) -> R,
        billed: (R) -> Pair<AiModel, AiUsage>,
        use: (R) -> T,
    ): T {
        val log = LogEntry(feature, subject, System.currentTimeMillis())
        val limitMs = if (webSearch) SEARCH_LIMIT_MS else LIMIT_MS
        val lastSign = AtomicLong(SystemClock.elapsedRealtime())
        val token = Any()
        synchronized(this) { reporting = token }
        report(token, AiProgress.Connecting)
        var answer: R? = null
        try {
            val done = withTimeout(limitMs) {
                coroutineScope {
                    val watchdog = launch {
                        while (true) {
                            delay(WATCH_EVERY_MS)
                            if (SystemClock.elapsedRealtime() - lastSign.get() > STALL_MS) throw AiException.Timeout(null, stalled = true)
                        }
                    }
                    work { p ->
                        lastSign.set(SystemClock.elapsedRealtime())
                        report(token, p)
                    }.also { watchdog.cancel() }
                }
            }
            answer = done
            val (by, usage) = billed(done)
            return use(done).also { record(log, by, usage, AiOutcome.OK) }
        } catch (e: TimeoutCancellationException) {
            // The provider may still bill a call it was working on; its cost isn't known.
            val timeout = AiException.Timeout(e)
            record(log, model, null, AiOutcome.TIMED_OUT, timeout.logDetail())
            throw timeout
        } catch (e: CancellationException) {
            withContext(NonCancellable) { record(log, model, null, AiOutcome.CANCELLED) }
            throw e
        } catch (e: AiException.Timeout) {
            record(log, model, null, AiOutcome.TIMED_OUT, e.logDetail())
            throw e
        } catch (e: AiException) {
            // A cut-off, declined or unusable answer is still billed by the provider.
            val billedAs = answer?.let(billed)
            record(log, billedAs?.first ?: model, billedAs?.second ?: e.usage, AiOutcome.FAILED, e.logDetail())
            throw e
        } finally {
            report(token, null)
        }
    }

    private class LogEntry(val feature: AiFeature, val subject: String, val startedAt: Long)

    private suspend fun record(log: LogEntry, model: AiModel, usage: AiUsage?, outcome: AiOutcome, detail: String = "") {
        val now = System.currentTimeMillis()
        dao.insertUsage(
            AiUsageEntity(
                atMillis = log.startedAt,
                feature = log.feature.name,
                modelId = model.id,
                inputTokens = usage?.inputTokens ?: 0,
                outputTokens = usage?.outputTokens ?: 0,
                webSearches = usage?.webSearches ?: 0,
                costUsd = usage?.let { model.costUsd(it) } ?: 0.0,
                subject = log.subject.take(SUBJECT_MAX),
                durationMs = now - log.startedAt,
                outcome = outcome.name,
                detail = detail,
            ),
        )
    }

    private companion object {
        // Nothing at all from the provider for this long: stuck, not slow. Gemini streams reasoning
        // summaries and Claude streams events, so a working call is never this quiet.
        const val STALL_MS = 60_000L
        const val WATCH_EVERY_MS = 2_000L

        // Backstops for an answer that keeps trickling in. No silent retries past them.
        const val LIMIT_MS = 5 * 60_000L
        const val SEARCH_LIMIT_MS = 8 * 60_000L
        const val RECENT_LIMIT = 300
        const val SUBJECT_MAX = 80
    }

    private fun monthStartMillis(): Long {
        val zone = ZoneId.systemDefault()
        return LocalDate.now(zone).withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

/** An agent turn under way, with the model it runs on. */
class AgentSession internal constructor(val turn: AgentTurn, val model: AiModel)

@Serializable
private data class SourceJson(val title: String, val url: String)

/** Where AI-suggested details of an exercise came from. [applied] names the fields the user kept. */
data class ExerciseAiNote(
    val atMillis: Long,
    val model: AiModel?,
    val modelId: String,
    val summary: String,
    val applied: List<String>,
    val sources: List<AiSource>,
)

/** One logged AI call. [costUsd] is 0 and unknown when [outcome] is a timeout or a cancellation. */
data class AiActivity(
    val atMillis: Long,
    val feature: String,
    val subject: String,
    val model: AiModel?,
    val modelId: String,
    val usage: AiUsage,
    val costUsd: Double,
    val durationMs: Long,
    val outcome: AiOutcome,
    /** What went wrong, for a call that didn't answer (empty for calls logged before v6). */
    val detail: String,
) {
    val costKnown: Boolean get() = outcome == AiOutcome.OK || outcome == AiOutcome.FAILED && (usage.inputTokens > 0 || usage.outputTokens > 0)
}
