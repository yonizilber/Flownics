package com.yonizilber.flownics.data.remote.gemini

import com.yonizilber.flownics.domain.ai.AiException
import com.yonizilber.flownics.domain.ai.AiModel
import com.yonizilber.flownics.domain.ai.AiProgress
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiStreamTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun chunk(raw: String) = json.decodeFromString(GeminiResponse.serializer(), raw)

    @Test
    fun joinsTextAcrossChunksAndKeepsThoughtsOut() {
        val seen = mutableListOf<AiProgress>()
        val stream = GeminiStream(freeText = true) { seen += it }
        stream.add(chunk("""{"candidates":[{"content":{"parts":[{"text":"Plan the squat","thought":true}]}}]}"""))
        stream.add(chunk("""{"candidates":[{"content":{"parts":[{"text":"Hello "}]}}]}"""))
        stream.add(
            chunk(
                """{"candidates":[{"content":{"parts":[{"text":"world"}]},"finishReason":"STOP"}],
                   "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":4,"thoughtsTokenCount":20}}""",
            ),
        )
        val reply = stream.finish(AiModel.GEMINI_FLASH)

        assertEquals("Hello world", reply.text)
        assertEquals(10L, reply.usage.inputTokens)
        assertEquals(24L, reply.usage.outputTokens)
        assertEquals(AiProgress.Thinking, seen.first())
        assertEquals(AiProgress.Writing(11, "Hello world"), seen.last())
    }

    @Test
    fun jsonAnswersShowNoPreview() {
        val seen = mutableListOf<AiProgress>()
        val stream = GeminiStream(freeText = false) { seen += it }
        stream.add(chunk("""{"candidates":[{"content":{"parts":[{"text":"{\"a\":1}"}]},"finishReason":"STOP"}]}"""))
        assertEquals(AiProgress.Writing(7, null), seen.last())
        assertEquals("{\"a\":1}", stream.finish(AiModel.GEMINI_FLASH).text)
    }

    @Test
    fun mergesSearchQueriesAndSources() {
        val stream = GeminiStream(freeText = true) {}
        stream.add(
            chunk(
                """{"candidates":[{"content":{"parts":[{"text":"x"}]},
                   "groundingMetadata":{"webSearchQueries":["q1"],"groundingChunks":[{"web":{"uri":"https://a","title":"A"}}]}}]}""",
            ),
        )
        stream.add(
            chunk(
                """{"candidates":[{"finishReason":"STOP",
                   "groundingMetadata":{"webSearchQueries":["q1","q2"],"groundingChunks":[{"web":{"uri":"https://a","title":"A"}},{"web":{"uri":"https://b"}}]}}]}""",
            ),
        )
        val reply = stream.finish(AiModel.GEMINI_FLASH)
        assertEquals(2L, reply.usage.webSearches)
        assertEquals(listOf("https://a", "https://b"), reply.sources.map { it.url })
        assertEquals("https://b", reply.sources[1].title)
    }

    @Test
    fun agentStepsKeepToolCallsAndTheRawPartsWithTheirSignatures() {
        val stream = GeminiStream(freeText = true) {}
        val first = """{"candidates":[{"content":{"role":"model","parts":[{"text":"Looking","thought":true},
            {"functionCall":{"name":"training_log","args":{"from_date":"2026-11-01","to_date":"2026-11-10","exercise_id":0}},"thoughtSignature":"sig-1"}]}}]}"""
        val last = """{"candidates":[{"content":{"role":"model","parts":[{"text":""}]},"finishReason":"STOP"}]}"""
        listOf(first, last).forEach { stream.add(chunk(it), json.parseToJsonElement(it) as kotlinx.serialization.json.JsonObject) }
        val step = stream.finishStep(AiModel.GEMINI_FLASH)

        assertEquals("", step.text)
        assertEquals(listOf("training_log"), step.calls.map { it.name })
        assertEquals("call_0", step.calls.single().id)
        assertEquals("\"2026-11-01\"", step.calls.single().args["from_date"].toString())
        // Sent back as they came: the signature is on the call's part, untouched.
        assertEquals(3, stream.rawParts.size)
        assertEquals("\"sig-1\"", stream.rawParts[1]["thoughtSignature"].toString())
    }

    @Test
    fun anAgentStepWithNeitherTextNorCallsIsAnError() {
        val stream = GeminiStream(freeText = true) {}
        stream.add(chunk("""{"candidates":[{"content":{"parts":[{"text":"hmm","thought":true}]},"finishReason":"STOP"}]}"""))
        assertTrue(runCatching { stream.finishStep(AiModel.GEMINI_FLASH) }.exceptionOrNull() is AiException.BadResponse)
    }

    @Test
    fun aStreamThatEndsWithoutFinishingIsAnError() {
        val stream = GeminiStream(freeText = true) {}
        stream.add(chunk("""{"candidates":[{"content":{"parts":[{"text":"half an ans"}]}}]}"""))
        val e = runCatching { stream.finish(AiModel.GEMINI_FLASH) }.exceptionOrNull()
        assertTrue(e is AiException.BadResponse)
    }

    @Test
    fun cutOffAndErrorChunksAreReported() {
        val cut = GeminiStream(freeText = true) {}
        cut.add(chunk("""{"candidates":[{"content":{"parts":[{"text":"{"}]},"finishReason":"MAX_TOKENS"}]}"""))
        assertTrue(runCatching { cut.finish(AiModel.GEMINI_FLASH) }.exceptionOrNull() is AiException.Truncated)

        val failing = GeminiStream(freeText = true) {}
        val busy = runCatching { failing.add(chunk("""{"error":{"code":503,"message":"overloaded","status":"UNAVAILABLE"}}""")) }.exceptionOrNull()
        assertTrue(busy is AiException.Overloaded)
        val other = runCatching { failing.add(chunk("""{"error":{"code":500,"message":"","status":"INTERNAL"}}""")) }.exceptionOrNull()
        // A 500 is a passing server fault, told like an overload (Google: retry it), with what Google said.
        assertTrue(other is AiException.Overloaded && "HTTP 500" in other.detail && "INTERNAL" in other.detail)
    }
}
