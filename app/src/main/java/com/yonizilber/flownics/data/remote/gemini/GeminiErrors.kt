package com.yonizilber.flownics.data.remote.gemini

import com.yonizilber.flownics.domain.ai.AiException
import com.yonizilber.flownics.domain.ai.AiProvider
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * What a Gemini error means and whether it is worth waiting out. Pure, so it is unit-tested.
 * Google asks clients to back off and retry on 429, 500, 503 and 504; a per-day quota is not
 * retried (it won't clear in seconds), and a 429 that names a longer wait than [MAX_SERVER_WAIT_MS]
 * is reported rather than sat through.
 */
object GeminiErrors {

    /** Waits before the 1st, 2nd, … retry. About 40 s in all, under the stall limit each. */
    val BACKOFF_MS = listOf(2_000L, 5_000L, 10_000L, 20_000L)
    const val MAX_SERVER_WAIT_MS = 30_000L

    fun toException(code: Int, error: GeminiError?): AiException {
        val message = error?.message.orEmpty()
        val detail = listOfNotNull(error?.status?.takeIf { it.isNotBlank() }, quotaIds(error).joinToString().takeIf { it.isNotBlank() }, message.take(160).takeIf { it.isNotBlank() })
            .joinToString("; ")
        return when {
            // Google answers a bad key with 400 INVALID_ARGUMENT, not 401.
            code == 401 || "API key not valid" in message || "API_KEY_INVALID" in message -> AiException.InvalidKey(AiProvider.GEMINI)
            code == 403 -> AiException.NoAccess(AiProvider.GEMINI, message)
            code == 429 -> AiException.RateLimited(AiProvider.GEMINI, detail, daily = isDaily(error))
            code == 503 || code == 500 || code == 502 || code == 504 -> AiException.Overloaded(AiProvider.GEMINI, "HTTP $code" + (if (detail.isNotBlank()) "; $detail" else ""))
            else -> AiException.Service(AiProvider.GEMINI, code, message.ifBlank { error?.status.orEmpty() })
        }
    }

    /** How long to wait before retry number [attempt] (0-based), or null to give up and report. */
    fun waitBeforeRetry(attempt: Int, code: Int, error: GeminiError?): Long? {
        if (attempt >= BACKOFF_MS.size) return null
        return when (code) {
            500, 502, 503, 504 -> BACKOFF_MS[attempt]
            429 -> {
                if (isDaily(error)) return null
                val server = retryDelayMs(error)
                when {
                    server == null -> BACKOFF_MS[attempt]
                    server <= MAX_SERVER_WAIT_MS -> server
                    else -> null
                }
            }
            else -> null
        }
    }

    /** RetryInfo's retryDelay ("17s", "1.5s"), in ms. */
    fun retryDelayMs(error: GeminiError?): Long? = error?.details.orEmpty().firstNotNullOfOrNull { d ->
        if (d["@type"]?.let { (it as? JsonPrimitive)?.contentOrNull }?.endsWith("RetryInfo") != true) return@firstNotNullOfOrNull null
        (d["retryDelay"] as? JsonPrimitive)?.contentOrNull?.removeSuffix("s")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
    }

    fun quotaIds(error: GeminiError?): List<String> = error?.details.orEmpty().flatMap { d ->
        runCatching { d["violations"]!!.jsonArray.mapNotNull { (it.jsonObject["quotaId"] as? JsonPrimitive)?.contentOrNull } }.getOrDefault(emptyList())
    }

    fun isDaily(error: GeminiError?): Boolean = quotaIds(error).any { "PerDay" in it }
}
