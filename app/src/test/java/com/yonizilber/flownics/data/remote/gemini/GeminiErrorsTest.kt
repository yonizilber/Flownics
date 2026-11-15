package com.yonizilber.flownics.data.remote.gemini

import com.yonizilber.flownics.domain.ai.AiException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiErrorsTest {

    private fun error(body: String): GeminiError =
        Json { ignoreUnknownKeys = true }.decodeFromString(GeminiErrorEnvelope.serializer(), body).error!!

    private val perMinute = error(
        """{"error":{"code":429,"message":"Resource has been exhausted","status":"RESOURCE_EXHAUSTED","details":[
            {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel"}]},
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"17s"}]}}""",
    )

    private val perDay = error(
        """{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED","details":[
            {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel"}]},
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"3600s"}]}}""",
    )

    @Test
    fun aShortRateLimitIsWaitedOutAsTheServerSays() {
        assertEquals(17_000L, GeminiErrors.waitBeforeRetry(0, 429, perMinute))
        val e = GeminiErrors.toException(429, perMinute) as AiException.RateLimited
        assertTrue(!e.daily)
        assertTrue(e.detail, "PerMinute" in e.detail)
    }

    @Test
    fun aDailyQuotaIsReportedNotWaitedFor() {
        assertNull(GeminiErrors.waitBeforeRetry(0, 429, perDay))
        assertTrue((GeminiErrors.toException(429, perDay) as AiException.RateLimited).daily)
    }

    @Test
    fun overloadsBackOffThenGiveUp() {
        assertEquals(GeminiErrors.BACKOFF_MS, GeminiErrors.BACKOFF_MS.indices.map { GeminiErrors.waitBeforeRetry(it, 503, null) })
        assertNull(GeminiErrors.waitBeforeRetry(GeminiErrors.BACKOFF_MS.size, 503, null))
        assertTrue(GeminiErrors.toException(500, null) is AiException.Overloaded)
        // A request the server rejects as wrong is never retried.
        assertNull(GeminiErrors.waitBeforeRetry(0, 400, null))
        assertTrue(GeminiErrors.toException(400, error("""{"error":{"code":400,"message":"API key not valid","status":"INVALID_ARGUMENT"}}""")) is AiException.InvalidKey)
    }
}
