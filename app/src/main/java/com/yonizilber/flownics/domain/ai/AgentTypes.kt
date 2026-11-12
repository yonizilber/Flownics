package com.yonizilber.flownics.domain.ai

import kotlinx.serialization.json.JsonObject

/**
 * A tool the agent may call. The app runs it: reading the user's data, or turning a change into a
 * proposal the user approves. [schema] follows [AiSchema]'s rule that every property is required
 * ("not set" is an empty string or 0), which both providers' strict tool modes accept.
 */
data class AiToolSpec(val name: String, val description: String, val schema: AiSchema.Obj)

data class AgentToolCall(
    /** The provider's id for the call, sent back with its result. */
    val id: String,
    val name: String,
    val args: JsonObject,
)

data class AgentToolResult(val call: AgentToolCall, val content: String, val isError: Boolean = false)

/** One agent turn: the user's new message on top of the conversation so far. */
data class AgentRequest(
    val system: String,
    /** Earlier messages, as plain text: tool calls and results of finished turns are not resent. */
    val history: List<AiTurn>,
    val userText: String,
    val tools: List<AiToolSpec>,
    val maxOutputTokens: Int = 8_000,
)

/** What the model did in one step: text, tool calls to run, or both. [done] when it asked for none. */
data class AgentStep(
    val text: String,
    val calls: List<AgentToolCall>,
    val usage: AiUsage,
    val model: AiModel,
) {
    val done: Boolean get() = calls.isEmpty()
}

/**
 * A turn in progress with one provider. It keeps the provider's own record of the steps so far
 * (thinking, signatures, tool-use ids), which must go back exactly as received while the turn
 * lasts. The first [next] takes no results; each later one takes the results of the step before.
 */
interface AgentTurn {
    suspend fun next(results: List<AgentToolResult>, onProgress: (AiProgress) -> Unit): AgentStep
}
