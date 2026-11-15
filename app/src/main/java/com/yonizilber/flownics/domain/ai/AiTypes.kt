package com.yonizilber.flownics.domain.ai

/** Whose API the user's own key belongs to. Each provider has its own key, model choice and terms. */
enum class AiProvider { GEMINI, CLAUDE }

/**
 * Every model the app can call, with its list price in USD per million tokens. Prices are only used
 * for the local "about $X" estimate and the monthly cap; the provider's own bill is what counts.
 * Claude: Anthropic's price list as of 2026-10-06. Gemini: Google's as of 2026-10-24 (copied from
 * Lectura). Gemini's search-grounding fee is not known here, so it is never included (and the UI
 * says so); Claude's web search is $10 per 1,000 searches.
 */
enum class AiModel(
    val provider: AiProvider,
    val id: String,
    val inputUsdPerMillion: Double,
    val outputUsdPerMillion: Double,
    val searchUsdEach: Double?,
) {
    GEMINI_FLASH_LITE(AiProvider.GEMINI, "gemini-3.5-flash-lite", 0.30, 2.50, null),
    GEMINI_FLASH(AiProvider.GEMINI, "gemini-3.6-flash", 0.75, 3.75, null),

    // Preview: may be refused on free-tier keys. The error says so instead of failing silently.
    GEMINI_PRO(AiProvider.GEMINI, "gemini-3.1-pro-preview", 2.00, 12.00, null),
    CLAUDE_HAIKU(AiProvider.CLAUDE, "claude-haiku-5-5", 0.10, 0.50, 0.01),
    CLAUDE_SONNET(AiProvider.CLAUDE, "claude-sonnet-5-5", 2.00, 10.00, 0.01),
    CLAUDE_OPUS(AiProvider.CLAUDE, "claude-opus-5-5", 4.00, 20.00, 0.01),
    ;

    fun costUsd(usage: AiUsage): Double =
        usage.inputTokens / 1_000_000.0 * inputUsdPerMillion +
            usage.outputTokens / 1_000_000.0 * outputUsdPerMillion +
            usage.webSearches * (searchUsdEach ?: 0.0)

    companion object {
        fun forProvider(provider: AiProvider): List<AiModel> = entries.filter { it.provider == provider }

        /** The model a provider starts on: Gemini's GA mid tier (works on free keys), Claude's Opus. */
        fun defaultFor(provider: AiProvider): AiModel = when (provider) {
            AiProvider.GEMINI -> GEMINI_FLASH
            AiProvider.CLAUDE -> CLAUDE_OPUS
        }

        /** A reply can name a different model than requested (e.g. a provider-side fallback). */
        fun byId(id: String): AiModel? = entries.firstOrNull { id == it.id || id.startsWith(it.id + "-") }
    }
}

/** What a call was for: shown next to its cost in the usage list. */
enum class AiFeature { KEY_TEST, EXERCISE_FILL, EXERCISE_VARIATIONS, EXERCISE_VIDEOS, WORKOUT_REVIEW, WORKOUT_VARIATIONS, WORKOUT_DRAFT, CHAT, COACH }

/**
 * How a call ended, for the activity log. Only [OK] and [FAILED] with a billed usage have a known
 * cost; a call cut off by the user or by the deadline may still have been billed by the provider.
 */
enum class AiOutcome { OK, FAILED, TIMED_OUT, CANCELLED }

data class AiTurn(val fromUser: Boolean, val text: String)

data class AiRequest(
    val feature: AiFeature,
    /** A short label for the activity log, e.g. the exercise's name. Empty when there is none. */
    val subject: String = "",
    /** The app's own instructions. User data goes in [turns], never here. */
    val system: String,
    val turns: List<AiTurn>,
    /** null: free text. Otherwise the reply must be one JSON object of this shape. */
    val schema: AiSchema.Obj? = null,
    /** Lets the model search the web, and returns the pages it actually got back as [AiReply.sources]. */
    val webSearch: Boolean = false,
    val maxOutputTokens: Int = 16_000,
    val depth: AiDepth = AiDepth.NORMAL,
)

/**
 * How much the model reasons before answering. [LIGHT] is for lists and lookups, where long
 * thinking mostly adds waiting (Gemini Flash at its default level could run past the deadline).
 */
enum class AiDepth { LIGHT, NORMAL }

data class AiSource(val title: String, val url: String)

data class AiUsage(val inputTokens: Long = 0, val outputTokens: Long = 0, val webSearches: Long = 0) {
    operator fun plus(other: AiUsage) =
        AiUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens, webSearches + other.webSearches)
}

data class AiReply(
    /** The answer text, or the JSON object when the request had a schema. */
    val text: String,
    /** Pages the search really returned. Never taken from the model's own text. */
    val sources: List<AiSource>,
    val usage: AiUsage,
    /** The model that actually answered. */
    val model: AiModel,
) {
    val costUsd: Double get() = model.costUsd(usage)
}

/**
 * A provider-neutral JSON schema, small enough for both providers' structured output. Every
 * property is required (Claude's strict mode needs that); "nothing" is an empty string or list.
 */
sealed interface AiSchema {
    val description: String?

    data class Obj(val properties: Map<String, AiSchema>, override val description: String? = null) : AiSchema
    data class Arr(val items: AiSchema, override val description: String? = null) : AiSchema
    data class Str(val enum: List<String>? = null, override val description: String? = null) : AiSchema
    data class Num(override val description: String? = null) : AiSchema
    data class Integer(override val description: String? = null) : AiSchema
    data class Bool(override val description: String? = null) : AiSchema
}

/**
 * Why an AI call failed. Each case has its own message in the UI; none is swallowed. [usage] is set
 * when the provider still billed the call (a cut-off or declined answer), so the cost is counted.
 */
sealed class AiException(message: String, cause: Throwable? = null, val usage: AiUsage? = null) : Exception(message, cause) {
    class NoKey(val provider: AiProvider) : AiException("No API key for $provider")
    class InvalidKey(val provider: AiProvider) : AiException("The $provider key was rejected")
    class NoAccess(val provider: AiProvider, val detail: String) : AiException(detail)
    /** [detail] is the provider's own words (which quota, for how long), for the activity log. [daily]: a per-day quota, not worth waiting for. */
    class RateLimited(val provider: AiProvider, val detail: String = "", val daily: Boolean = false) : AiException("Rate limited by $provider")
    class Overloaded(val provider: AiProvider, val detail: String = "") : AiException("$provider is overloaded")
    class Network(cause: Throwable) : AiException("Network error", cause)

    /** [stalled]: the provider stopped sending anything mid-answer; otherwise the overall limit was hit. */
    class Timeout(cause: Throwable?, val stalled: Boolean = false) : AiException(if (stalled) "Stalled" else "Timed out", cause)
    class Refused(val detail: String?, usage: AiUsage? = null) : AiException("The model declined: $detail", usage = usage)
    class Truncated(usage: AiUsage? = null) : AiException("The answer was cut off at the length limit", usage = usage)
    class BadResponse(val detail: String, cause: Throwable? = null, usage: AiUsage? = null) : AiException(detail, cause, usage)
    class Service(val provider: AiProvider, val status: Int, val detail: String) : AiException("$provider $status: $detail")
    class CapReached(val spentUsd: Double, val capUsd: Double) : AiException("Monthly cap reached")
}

/** A short, key-free reason for the activity log, so a failure there says what actually happened. */
fun AiException.logDetail(): String = when (this) {
    is AiException.Service -> "HTTP $status: $detail"
    is AiException.Overloaded -> "Overloaded (503/529)" + detail.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
    is AiException.RateLimited -> (if (daily) "Daily quota used up (429)" else "Rate limited (429)") + detail.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
    is AiException.Timeout -> if (stalled) "Stalled: nothing received for a while" else "Overall time limit"
    is AiException.Truncated -> "Cut off at the output limit"
    is AiException.Refused -> "Declined" + (detail?.let { ": $it" } ?: "")
    is AiException.Network -> "Network: " + (cause?.javaClass?.simpleName ?: "?")
    else -> message.orEmpty()
}.take(300)

/**
 * What a running call is doing, reported as the answer streams in. Each report also tells the
 * caller the provider is still alive, which is how a slow answer is told apart from a stuck one.
 */
sealed interface AiProgress {
    data object Connecting : AiProgress
    data class Retrying(val attempt: Int) : AiProgress
    data object Thinking : AiProgress
    data class Searching(val searches: Int) : AiProgress

    /** [preview] is the answer so far, for free-text answers only (a half-written JSON means nothing). */
    data class Writing(val chars: Int, val preview: String?) : AiProgress
}

/** One provider's transport. Knows nothing about fitness; the prompts live in [FitnessPrompts]. */
interface AiClient {
    val provider: AiProvider

    /** Streams the answer, calling [onProgress] on everything that arrives (from any thread). */
    suspend fun complete(request: AiRequest, model: AiModel, apiKey: String, onProgress: (AiProgress) -> Unit): AiReply

    /** Starts an agent turn; nothing is sent until its first [AgentTurn.next]. */
    fun startTurn(request: AgentRequest, model: AiModel, apiKey: String): AgentTurn
}
