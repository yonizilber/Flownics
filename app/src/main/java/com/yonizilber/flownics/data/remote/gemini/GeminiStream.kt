package com.yonizilber.flownics.data.remote.gemini

import com.yonizilber.flownics.domain.ai.AgentStep
import com.yonizilber.flownics.domain.ai.AgentToolCall
import com.yonizilber.flownics.domain.ai.AiException
import com.yonizilber.flownics.domain.ai.AiModel
import com.yonizilber.flownics.domain.ai.AiProgress
import com.yonizilber.flownics.domain.ai.AiProvider
import com.yonizilber.flownics.domain.ai.AiReply
import com.yonizilber.flownics.domain.ai.AiSource
import com.yonizilber.flownics.domain.ai.AiUsage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Builds one answer from Gemini's streamed chunks. Pure, so the joining rules are unit-tested:
 * answer text is concatenated across chunks, reasoning summaries are never part of it, usage is
 * the last report (Gemini sends running totals), and search queries and sources are merged.
 */
class GeminiStream(private val freeText: Boolean, private val onProgress: (AiProgress) -> Unit) {
    private val text = StringBuilder()
    private val queries = linkedSetOf<String>()
    private val sources = linkedMapOf<String, AiSource>()
    private val calls = mutableListOf<GeminiFunctionCall>()
    private var usage: GeminiUsage? = null
    private var finishReason: String? = null
    private var blockReason: String? = null
    private var sawCandidate = false

    /** Every part of the answer as it arrived, to be sent back unchanged within an agent turn. */
    val rawParts = mutableListOf<JsonObject>()

    /** [raw] is the same chunk undecoded, when the caller needs [rawParts]. */
    fun add(chunk: GeminiResponse, raw: JsonObject? = null) {
        chunk.error?.let {
            throw when (it.code) {
                503 -> AiException.Overloaded(AiProvider.GEMINI)
                429 -> AiException.RateLimited(AiProvider.GEMINI)
                else -> AiException.Service(AiProvider.GEMINI, it.code, it.message.ifBlank { it.status })
            }
        }
        raw?.let { rawParts += partsOf(it) }
        chunk.usageMetadata?.let { usage = it }
        chunk.promptFeedback?.blockReason?.let { blockReason = it }
        val candidate = chunk.candidates.firstOrNull()
        if (candidate == null) {
            onProgress(AiProgress.Thinking)
            return
        }
        sawCandidate = true
        candidate.finishReason?.let { finishReason = it }
        candidate.groundingMetadata?.let { g ->
            val before = queries.size
            queries += g.webSearchQueries
            g.groundingChunks.forEach { c -> c.web?.uri?.let { sources.putIfAbsent(it, AiSource(c.web.title ?: it, it)) } }
            if (queries.size > before) onProgress(AiProgress.Searching(queries.size))
        }
        var wrote = false
        candidate.content?.parts.orEmpty().forEach { part ->
            when {
                part.thought == true -> onProgress(AiProgress.Thinking)
                part.functionCall != null -> {
                    calls += part.functionCall
                    onProgress(AiProgress.Thinking)
                }
                else -> part.text?.let {
                    text.append(it)
                    wrote = true
                }
            }
        }
        if (wrote) onProgress(AiProgress.Writing(text.length, if (freeText) text.toString() else null))
        // Something arrived even if there's nothing to show: still a sign of life.
        if (!wrote && candidate.content == null && candidate.groundingMetadata == null) onProgress(AiProgress.Thinking)
    }

    fun finish(model: AiModel): AiReply {
        val usage = checkFinished()
        val answer = text.toString().trim()
        if (answer.isEmpty()) throw AiException.BadResponse("Gemini returned an empty answer", usage = usage)
        return AiReply(text = answer, sources = sources.values.toList(), usage = usage, model = model)
    }

    /** An agent step: text, tool calls, or both. Neither is an empty answer, which is an error. */
    fun finishStep(model: AiModel): AgentStep {
        val usage = checkFinished()
        val answer = text.toString().trim()
        if (answer.isEmpty() && calls.isEmpty()) throw AiException.BadResponse("Gemini returned an empty answer", usage = usage)
        val toolCalls = calls.mapIndexed { i, c -> AgentToolCall(id = c.id ?: "call_$i", name = c.name, args = c.args ?: JsonObject(emptyMap())) }
        return AgentStep(text = answer, calls = toolCalls, usage = usage, model = model)
    }

    private fun checkFinished(): AiUsage {
        val usage = usage?.let {
            AiUsage(
                inputTokens = it.promptTokenCount + it.toolUsePromptTokenCount,
                outputTokens = it.candidatesTokenCount + it.thoughtsTokenCount,
                webSearches = queries.size.toLong(),
            )
        } ?: AiUsage(webSearches = queries.size.toLong())
        blockReason?.let { throw AiException.Refused(it, usage) }
        if (!sawCandidate) throw AiException.BadResponse("Gemini returned no answer", usage = usage)
        when (finishReason) {
            "STOP" -> Unit
            // The stream ended without saying it was done: the answer may be cut off.
            null -> throw AiException.BadResponse("Gemini's answer ended without finishing", usage = usage)
            "MAX_TOKENS" -> throw AiException.Truncated(usage)
            "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION" -> throw AiException.Refused(finishReason, usage)
            else -> throw AiException.BadResponse("Gemini stopped early: $finishReason", usage = usage)
        }
        return usage
    }

    private fun partsOf(raw: JsonObject): List<JsonObject> = runCatching {
        raw["candidates"]!!.jsonArray.first().jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray.map { it.jsonObject }
    }.getOrDefault(emptyList())
}
