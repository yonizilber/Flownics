package com.yonizilber.flownics.data.remote.gemini

import com.yonizilber.flownics.data.remote.toGeminiSchema
import com.yonizilber.flownics.domain.ai.AgentRequest
import com.yonizilber.flownics.domain.ai.AgentStep
import com.yonizilber.flownics.domain.ai.AgentToolResult
import com.yonizilber.flownics.domain.ai.AgentTurn
import com.yonizilber.flownics.domain.ai.AiClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import com.yonizilber.flownics.domain.ai.AiDepth
import com.yonizilber.flownics.domain.ai.AiException
import com.yonizilber.flownics.domain.ai.AiModel
import com.yonizilber.flownics.domain.ai.AiProgress
import com.yonizilber.flownics.domain.ai.AiProvider
import com.yonizilber.flownics.domain.ai.AiReply
import com.yonizilber.flownics.domain.ai.AiRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import okio.BufferedSource
import retrofit2.HttpException
import java.io.IOException
import java.io.InterruptedIOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiAiClient @Inject constructor(
    private val service: GeminiApiService,
    private val json: Json,
) : AiClient {

    override val provider = AiProvider.GEMINI

    override suspend fun complete(request: AiRequest, model: AiModel, apiKey: String, onProgress: (AiProgress) -> Unit): AiReply {
        val body = GeminiRequest(
            contents = request.turns.map { turn ->
                // Pictures first, then the words about them.
                val images = turn.images.map { GeminiPart(inlineData = GeminiInlineData(it.mimeType, it.base64)) }
                GeminiContent(role = if (turn.fromUser) "user" else "model", parts = images + GeminiPart(text = turn.text))
            },
            systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = request.system))),
            generationConfig = GeminiGenerationConfig(
                maxOutputTokens = request.maxOutputTokens,
                responseMimeType = request.schema?.let { "application/json" },
                responseSchema = request.schema?.toGeminiSchema(),
                thinkingConfig = GeminiThinkingConfig(
                    thinkingLevel = if (request.depth == AiDepth.LIGHT) "low" else null,
                    includeThoughts = true,
                ),
            ),
            tools = if (request.webSearch) listOf(GeminiTool(googleSearch = GeminiGoogleSearch())) else null,
        )
        val stream = GeminiStream(freeText = request.schema == null, onProgress = onProgress)
        read(stream, keepRaw = false, onProgress) { service.streamGenerateContent(model.id, apiKey, body) }
        return stream.finish(model)
    }

    override fun startTurn(request: AgentRequest, model: AiModel, apiKey: String): AgentTurn = object : AgentTurn {
        // The whole turn as Gemini sees it; the model's own steps go back exactly as they came.
        private val contents = (request.history.map { textContent(if (it.fromUser) "user" else "model", it.text) } +
            textContent("user", request.userText)).toMutableList()

        override suspend fun next(results: List<AgentToolResult>, onProgress: (AiProgress) -> Unit): AgentStep {
            if (results.isNotEmpty()) {
                contents += buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        results.forEach { r ->
                            addJsonObject {
                                putJsonObject("functionResponse") {
                                    put("name", r.call.name)
                                    if (!r.call.id.startsWith("call_")) put("id", r.call.id)
                                    putJsonObject("response") { put(if (r.isError) "error" else "result", r.content) }
                                }
                            }
                        }
                    }
                }
            }
            val body = buildJsonObject {
                putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", request.system) } } }
                put("contents", JsonArray(contents.toList()))
                put(
                    "generationConfig",
                    json.encodeToJsonElement(
                        GeminiGenerationConfig.serializer(),
                        GeminiGenerationConfig(
                            maxOutputTokens = request.maxOutputTokens,
                            thinkingConfig = GeminiThinkingConfig(includeThoughts = true),
                        ),
                    ),
                )
                putJsonArray("tools") {
                    addJsonObject {
                        putJsonArray("functionDeclarations") {
                            request.tools.forEach { tool ->
                                addJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", json.encodeToJsonElement(GeminiSchema.serializer(), tool.schema.toGeminiSchema()))
                                }
                            }
                        }
                    }
                }
            }
            val stream = GeminiStream(freeText = true, onProgress = onProgress)
            read(stream, keepRaw = true, onProgress) { service.streamGenerateContentRaw(model.id, apiKey, body) }
            val step = stream.finishStep(model)
            // As received, except empty text pieces of the stream that carry no signature.
            val parts = stream.rawParts.filterNot { p ->
                p.keys == setOf("text") && (p["text"] as? JsonPrimitive)?.contentOrNull.isNullOrEmpty()
            }
            contents += buildJsonObject {
                put("role", "model")
                put("parts", JsonArray(parts))
            }
            return step
        }
    }

    private fun textContent(role: String, text: String) = buildJsonObject {
        put("role", role)
        putJsonArray("parts") { addJsonObject { put("text", text) } }
    }

    /** Opens the stream and feeds every `data:` chunk to [stream] until it ends. */
    private suspend fun read(
        stream: GeminiStream,
        keepRaw: Boolean,
        onProgress: (AiProgress) -> Unit,
        request: suspend () -> ResponseBody,
    ) {
        open(onProgress, request).readCancellably { source ->
            try {
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith(DATA)) continue
                    val raw = json.parseToJsonElement(line.substring(DATA.length).trim()).jsonObject
                    stream.add(json.decodeFromJsonElement(GeminiResponse.serializer(), raw), if (keepRaw) raw else null)
                }
            } catch (e: InterruptedIOException) {
                throw AiException.Timeout(e, stalled = true)
            } catch (e: IOException) {
                throw AiException.Network(e)
            } catch (e: kotlinx.serialization.SerializationException) {
                throw AiException.BadResponse("Gemini's reply could not be read", e)
            } catch (e: IllegalArgumentException) {
                // A line that isn't a JSON object.
                throw AiException.BadResponse("Gemini's reply could not be read", e)
            }
        }
    }

    /**
     * Starts the stream. Only a refusal before anything was sent is retried (nothing is lost
     * then): overloads and short rate limits, with growing waits, each shown on the waiting card.
     */
    private suspend fun open(onProgress: (AiProgress) -> Unit, request: suspend () -> ResponseBody): ResponseBody {
        var attempt = 0
        while (true) {
            try {
                return request()
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                val error = runCatching {
                    json.decodeFromString<GeminiErrorEnvelope>(e.response()?.errorBody()?.string().orEmpty()).error
                }.getOrNull()
                val wait = GeminiErrors.waitBeforeRetry(attempt, e.code(), error) ?: throw GeminiErrors.toException(e.code(), error)
                attempt++
                onProgress(AiProgress.Retrying(attempt))
                delay(wait)
            } catch (e: InterruptedIOException) {
                throw AiException.Timeout(e, stalled = true)
            } catch (e: IOException) {
                throw AiException.Network(e)
            }
        }
    }

    /**
     * Reads the body on an IO thread. A blocking read doesn't notice coroutine cancellation, so a
     * cancel (the user's, or the stall watchdog's) closes the body, which ends the read at once.
     */
    private suspend fun <T> ResponseBody.readCancellably(read: (BufferedSource) -> T): T = coroutineScope {
        val reading = async(Dispatchers.IO) { use { read(it.source()) } }
        try {
            reading.await()
        } catch (e: CancellationException) {
            close()
            throw e
        }
    }

    private companion object {
        const val DATA = "data:"
    }
}
