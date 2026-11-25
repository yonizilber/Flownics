package com.yonizilber.flownics.data.remote.claude

import com.anthropic.client.AnthropicClientAsync
import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync
import com.anthropic.core.JsonValue
import com.anthropic.core.Timeout
import com.anthropic.core.http.AsyncStreamResponse
import com.anthropic.errors.AnthropicIoException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.RawMessageStreamEvent
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.OverloadedException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.ServiceUnavailableException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolChoiceAuto
import com.anthropic.models.messages.WebSearchTool20250305
import com.anthropic.models.messages.WebSearchTool20260209
import com.fasterxml.jackson.databind.JsonNode
import com.yonizilber.flownics.data.remote.toJsonSchema
import com.yonizilber.flownics.data.remote.toPlain
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ToolResultBlockParam
import com.yonizilber.flownics.domain.ai.AgentRequest
import com.yonizilber.flownics.domain.ai.AgentStep
import com.yonizilber.flownics.domain.ai.AgentToolCall
import com.yonizilber.flownics.domain.ai.AgentToolResult
import com.yonizilber.flownics.domain.ai.AgentTurn
import com.yonizilber.flownics.domain.ai.AiClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import com.yonizilber.flownics.domain.ai.AiDepth
import com.yonizilber.flownics.domain.ai.AiImage
import com.anthropic.models.messages.Base64ImageSource
import com.yonizilber.flownics.domain.ai.AiException
import com.yonizilber.flownics.domain.ai.AiModel
import com.yonizilber.flownics.domain.ai.AiProgress
import com.yonizilber.flownics.domain.ai.AiProvider
import com.yonizilber.flownics.domain.ai.AiReply
import com.yonizilber.flownics.domain.ai.AiRequest
import com.yonizilber.flownics.domain.ai.AiSchema
import com.yonizilber.flownics.domain.ai.AiSource
import com.yonizilber.flownics.domain.ai.AiUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import java.time.Duration
import java.util.concurrent.CompletionException
import javax.inject.Inject
import javax.inject.Singleton

private fun imageBlock(image: AiImage): ContentBlockParam = ContentBlockParam.ofImage(
    Base64ImageSource.builder()
        .data(image.base64)
        .mediaType(
            when (image.mimeType) {
                "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG
                "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP
                "image/jpeg" -> Base64ImageSource.MediaType.IMAGE_JPEG
                else -> throw AiException.BadResponse("Unsupported image type ${image.mimeType}")
            },
        )
        .build(),
)

/** Claude through Anthropic's official Java SDK (its async client, so a closed screen cancels the call). */
@Singleton
class ClaudeAiClient @Inject constructor() : AiClient {

    override val provider = AiProvider.CLAUDE

    // One SDK client per key: building one sets up its own OkHttp pool.
    private var cached: Pair<String, AnthropicClientAsync>? = null

    @Synchronized
    private fun clientFor(apiKey: String): AnthropicClientAsync {
        cached?.let { (key, client) -> if (key == apiKey) return client else client.close() }
        return AnthropicOkHttpClientAsync.builder()
            .apiKey(apiKey)
            // The answer streams, so the whole request may run long while bytes keep coming; a
            // silent stream is caught sooner by AiService's stall watchdog. These are backstops.
            .timeout(
                Timeout.builder()
                    .connect(Duration.ofSeconds(20))
                    .read(Duration.ofSeconds(120))
                    .write(Duration.ofSeconds(30))
                    .request(Duration.ofMinutes(10))
                    .build(),
            )
            .maxRetries(4)
            .build()
            .also { cached = apiKey to it }
    }

    override suspend fun complete(request: AiRequest, model: AiModel, apiKey: String, onProgress: (AiProgress) -> Unit): AiReply {
        val client = clientFor(apiKey)
        val progress = ClaudeProgress(freeText = request.schema == null, onProgress = onProgress)
        val schema = request.schema
        // Web search answers carry citations, which can't be combined with JSON output mode. With
        // both, the answer comes back through a strict tool instead: same guarantee on the shape.
        val submitThroughTool = schema != null && request.webSearch

        // Assistant turns the server paused mid-search, sent back unchanged to resume them.
        val paused = mutableListOf<Message>()

        var usage = AiUsage()
        val sources = linkedMapOf<String, AiSource>()
        var answeredBy = model
        val text = StringBuilder()

        repeat(MAX_CONTINUATIONS) {
            val params = buildParams(request, model, schema, submitThroughTool, paused)
            val message = send(client, params, progress)
            usage += message.usageOf()
            AiModel.byId(message.model().asString())?.let { answeredBy = it }

            message.content().forEach { block ->
                block.webSearchToolResult().ifPresent { result ->
                    // Text before a search is narration ("I'll look that up"); the answer comes after.
                    text.clear()
                    result.content().resultBlocks().ifPresent { results ->
                        results.forEach { sources.putIfAbsent(it.url(), AiSource(it.title().ifBlank { it.url() }, it.url())) }
                    }
                }
                block.text().ifPresent { text.append(it.text()) }
            }

            val submitted = message.content().firstNotNullOfOrNull { block ->
                block.toolUse().orElse(null)?.takeIf { it.name() == SUBMIT_TOOL }
            }
            if (submitted != null) {
                val json = submitted._input().convert(JsonNode::class.java).toString()
                return AiReply(json, sources.values.toList(), usage, answeredBy)
            }

            when (message.stopReason().orElse(null)) {
                StopReason.REFUSAL -> throw AiException.Refused(message.stopDetails().flatMap { it.explanation() }.orElse(null), usage)
                StopReason.MAX_TOKENS -> throw AiException.Truncated(usage)
                // The server paused a long search loop: send its turn back unchanged to resume.
                StopReason.PAUSE_TURN -> paused += message
                else -> {
                    if (submitThroughTool) throw AiException.BadResponse("Claude finished without submitting the answer", usage = usage)
                    val answer = text.toString().trim()
                    if (answer.isEmpty()) throw AiException.BadResponse("Claude returned an empty answer", usage = usage)
                    return AiReply(answer, sources.values.toList(), usage, answeredBy)
                }
            }
        }
        throw AiException.BadResponse("Claude kept searching without answering", usage = usage)
    }

    override fun startTurn(request: AgentRequest, model: AiModel, apiKey: String): AgentTurn = object : AgentTurn {
        // The turn's steps so far: each assistant message goes back exactly as it came (thinking
        // and tool-use ids included), followed by the results of the tools it asked for.
        private val steps = mutableListOf<(MessageCreateParams.Builder) -> Unit>()

        override suspend fun next(results: List<AgentToolResult>, onProgress: (AiProgress) -> Unit): AgentStep {
            if (results.isNotEmpty()) {
                val blocks = results.map {
                    ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder().toolUseId(it.call.id).content(it.content).isError(it.isError).build(),
                    )
                }
                steps += { b -> b.addUserMessageOfContentBlockParams(blocks) }
            }
            val builder = MessageCreateParams.builder()
                .model(model.id)
                .maxTokens(request.maxOutputTokens.toLong())
                .system(request.system)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
                // Caches the unchanging start (instructions, tools, earlier messages) between steps.
                .cacheControl(CacheControlEphemeral.builder().build())
            request.history.forEach { turn -> if (turn.fromUser) builder.addUserMessage(turn.text) else builder.addAssistantMessage(turn.text) }
            builder.addUserMessage(request.userText)
            steps.forEach { it(builder) }
            // Not strict: Claude compiles every strict tool's schema into one grammar and refuses a
            // request when it is too large (the coach has 15 tools). Without strict the shape is
            // no longer guaranteed, so the app checks each call's arguments itself and sends a
            // problem back to the model as an error result (CoachTools), never filling in silently.
            request.tools.forEach { tool ->
                builder.addTool(
                    Tool.builder()
                        .name(tool.name)
                        .description(tool.description)
                        .inputSchema(inputSchemaOf(tool.schema))
                        .build(),
                )
            }
            if (model != AiModel.CLAUDE_HAIKU) {
                builder.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }

            val message = send(clientFor(apiKey), builder.build(), ClaudeProgress(freeText = true, onProgress = onProgress))
            steps += { b -> b.addMessage(message) }
            val usage = message.usageOf()
            val answeredBy = AiModel.byId(message.model().asString()) ?: model
            when (message.stopReason().orElse(null)) {
                StopReason.REFUSAL -> throw AiException.Refused(message.stopDetails().flatMap { it.explanation() }.orElse(null), usage)
                StopReason.MAX_TOKENS -> throw AiException.Truncated(usage)
                else -> Unit
            }
            val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim()
            val calls = message.content().mapNotNull { block ->
                block.toolUse().orElse(null)?.let { use ->
                    val args = runCatching { Json.parseToJsonElement(use._input().convert(JsonNode::class.java).toString()).jsonObject }
                        .getOrElse { JsonObject(emptyMap()) }
                    AgentToolCall(id = use.id(), name = use.name(), args = args)
                }
            }
            if (text.isEmpty() && calls.isEmpty()) throw AiException.BadResponse("Claude returned an empty answer", usage = usage)
            return AgentStep(text = text, calls = calls, usage = usage, model = answeredBy)
        }
    }

    private fun buildParams(
        request: AiRequest,
        model: AiModel,
        schema: AiSchema.Obj?,
        submitThroughTool: Boolean,
        paused: List<Message>,
    ): MessageCreateParams {
        val output = OutputConfig.builder()
            .effort(if (request.depth == AiDepth.LIGHT) OutputConfig.Effort.LOW else OutputConfig.Effort.MEDIUM)
        if (schema != null && !submitThroughTool) {
            output.format(JsonOutputFormat.builder().schema(jsonSchemaOf(schema)).build())
        }
        val system = if (submitThroughTool) {
            request.system + "\n\nWhen you have the final answer, call the $SUBMIT_TOOL tool exactly once with it, and write nothing after."
        } else {
            request.system
        }
        val builder = MessageCreateParams.builder()
            .model(model.id)
            .maxTokens(request.maxOutputTokens.toLong())
            .system(system)
            .outputConfig(output.build())
        request.turns.forEach { turn ->
            when {
                !turn.fromUser -> builder.addAssistantMessage(turn.text)
                turn.images.isEmpty() -> builder.addUserMessage(turn.text)
                else -> builder.addUserMessageOfContentBlockParams(turn.images.map(::imageBlock) + ContentBlockParam.ofText(turn.text))
            }
        }
        paused.forEach(builder::addMessage)

        if (request.webSearch) {
            // The dynamic-filtering search needs Opus or Sonnet; Haiku gets the basic one.
            if (model == AiModel.CLAUDE_HAIKU) {
                builder.addTool(WebSearchTool20250305.builder().maxUses(MAX_SEARCHES).build())
            } else {
                builder.addTool(WebSearchTool20260209.builder().maxUses(MAX_SEARCHES).build())
            }
        }
        if (submitThroughTool && schema != null) {
            builder.addTool(
                Tool.builder()
                    .name(SUBMIT_TOOL)
                    .description("Submit the final answer in the required shape.")
                    .inputSchema(inputSchemaOf(schema))
                    .strict(true)
                    .build(),
            )
            builder.toolChoice(ToolChoiceAuto.builder().build())
        }
        if (model != AiModel.CLAUDE_HAIKU) {
            // If a safety classifier declines, Anthropic retries on a model it picks instead of
            // just stopping (Opus and Sonnet only). The reply names the model that answered.
            builder.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return builder.build()
    }

    /** One streamed request, put back together into the same [Message] a plain call returns. */
    private suspend fun send(client: AnthropicClientAsync, params: MessageCreateParams, progress: ClaudeProgress): Message {
        val accumulator = MessageAccumulator.create()
        val stream = client.messages().createStreaming(params)
        stream.subscribe(
            object : AsyncStreamResponse.Handler<RawMessageStreamEvent> {
                override fun onNext(value: RawMessageStreamEvent) {
                    accumulator.accumulate(value)
                    progress.on(value)
                }
            },
        )
        try {
            stream.onCompleteFuture().await()
        } catch (e: CancellationException) {
            // The user's cancel or the stall watchdog: stop the download too.
            stream.close()
            throw e
        } catch (e: Exception) {
            throw toAiException((e as? CompletionException)?.cause ?: e)
        }
        return try {
            accumulator.message()
        } catch (e: Exception) {
            throw AiException.BadResponse("Claude's answer ended without finishing", e)
        }
    }

    /** Turns stream events into [AiProgress]: every event, even an unshown one, is a sign of life. */
    private class ClaudeProgress(private val freeText: Boolean, private val onProgress: (AiProgress) -> Unit) {
        private var last: AiProgress = AiProgress.Connecting
        private var searches = 0
        private val text = StringBuilder()
        private var jsonChars = 0

        @Synchronized
        fun on(event: RawMessageStreamEvent) {
            event.contentBlockStart().ifPresent { start ->
                val block = start.contentBlock()
                when {
                    block.isThinking() || block.isRedactedThinking() -> last = AiProgress.Thinking
                    block.isServerToolUse() -> last = AiProgress.Searching(++searches)
                    // Text before a search is narration; the answer is what comes after.
                    block.isWebSearchToolResult() -> text.clear()
                }
            }
            event.contentBlockDelta().ifPresent { delta ->
                val d = delta.delta()
                when {
                    d.isThinking() -> last = AiProgress.Thinking
                    d.isText() -> {
                        text.append(d.asText().text())
                        last = AiProgress.Writing(text.length, if (freeText) text.toString() else null)
                    }
                    d.isInputJson() -> {
                        jsonChars += d.asInputJson().partialJson().length
                        last = AiProgress.Writing(jsonChars, null)
                    }
                }
            }
            onProgress(last)
        }
    }

    private fun toAiException(e: Throwable): AiException = when (e) {
        is UnauthorizedException -> AiException.InvalidKey(provider)
        is PermissionDeniedException -> AiException.NoAccess(provider, e.message.orEmpty())
        is RateLimitException -> AiException.RateLimited(provider)
        is OverloadedException, is ServiceUnavailableException -> AiException.Overloaded(provider)
        is InternalServerException -> AiException.Service(provider, e.statusCode(), e.message.orEmpty())
        is AnthropicServiceException -> AiException.Service(provider, e.statusCode(), e.message.orEmpty())
        is AnthropicIoException -> AiException.Network(e)
        else -> AiException.BadResponse(e.message ?: e.javaClass.simpleName, e)
    }

    private fun Message.usageOf(): AiUsage {
        val u = usage()
        return AiUsage(
            // Cache reads/writes are billed at different rates; counting them as plain input
            // slightly overestimates, which is the safe side for a spending cap.
            inputTokens = u.inputTokens() + u.cacheReadInputTokens().orElse(0L) + u.cacheCreationInputTokens().orElse(0L),
            outputTokens = u.outputTokens(),
            webSearches = u.serverToolUse().map { it.webSearchRequests() }.orElse(0L),
        )
    }

    private fun jsonSchemaOf(schema: AiSchema.Obj): JsonOutputFormat.Schema {
        val plain = schema.toJsonSchema().toPlain() as Map<*, *>
        return JsonOutputFormat.Schema.builder()
            .additionalProperties(plain.entries.associate { (k, v) -> k as String to JsonValue.from(v) })
            .build()
    }

    private fun inputSchemaOf(schema: AiSchema.Obj): Tool.InputSchema {
        val plain = schema.toJsonSchema().toPlain() as Map<*, *>
        val properties = plain["properties"] as Map<*, *>
        return Tool.InputSchema.builder()
            .properties(
                Tool.InputSchema.Properties.builder()
                    .additionalProperties(properties.entries.associate { (k, v) -> k as String to JsonValue.from(v) })
                    .build(),
            )
            .required(schema.properties.keys.toList())
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .build()
    }

    private companion object {
        const val SUBMIT_TOOL = "submit_answer"
        const val MAX_SEARCHES = 3L
        const val MAX_CONTINUATIONS = 4
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    }
}
