package com.yonizilber.flownics.data.remote.gemini

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Streaming

// Same shape as Lectura's client. The key travels in a header, never as ?key=, because OkHttp's
// logging prints request URLs.
interface GeminiApiService {
    /**
     * The answer as server-sent events, each `data:` line one [GeminiResponse] chunk. Streaming
     * shows progress and tells a slow answer from a stuck one; the body must be closed by the caller.
     */
    @Streaming
    @POST("v1beta/models/{model}:streamGenerateContent?alt=sse")
    suspend fun streamGenerateContent(
        @Path("model") model: String,
        @Header("x-goog-api-key") apiKey: String,
        @Body request: GeminiRequest,
    ): ResponseBody

    /**
     * The same endpoint with a hand-built body: an agent turn sends the model's earlier parts back
     * exactly as they came (thought signatures included), which typed DTOs would drop.
     */
    @Streaming
    @POST("v1beta/models/{model}:streamGenerateContent?alt=sse")
    suspend fun streamGenerateContentRaw(
        @Path("model") model: String,
        @Header("x-goog-api-key") apiKey: String,
        @Body request: JsonObject,
    ): ResponseBody
}

@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiContent? = null,
    val generationConfig: GeminiGenerationConfig? = null,
    // Gemini 3 accepts structured output together with search grounding in one call.
    val tools: List<GeminiTool>? = null,
)

@Serializable
data class GeminiContent(val role: String? = null, val parts: List<GeminiPart> = emptyList())

@Serializable
data class GeminiPart(
    val text: String? = null,
    // Set on reasoning summaries, which are not part of the answer.
    val thought: Boolean? = null,
    val functionCall: GeminiFunctionCall? = null,
)

@Serializable
data class GeminiFunctionCall(val name: String, val args: JsonObject? = null, val id: String? = null)

@Serializable
data class GeminiGenerationConfig(
    val maxOutputTokens: Int? = null,
    val responseMimeType: String? = null,
    val responseSchema: GeminiSchema? = null,
    val thinkingConfig: GeminiThinkingConfig? = null,
)

// "low" is accepted by every Gemini 3 model offered here; null leaves the model's own default.
// [includeThoughts] streams short summaries of the reasoning (marked `thought`, never shown as the
// answer): without them a long think sends nothing and can't be told apart from a stuck call.
@Serializable
data class GeminiThinkingConfig(val thinkingLevel: String? = null, val includeThoughts: Boolean? = null)

// The OpenAPI-style subset Gemini's responseSchema accepts.
@Serializable
data class GeminiSchema(
    val type: String,
    val description: String? = null,
    val properties: Map<String, GeminiSchema>? = null,
    val required: List<String>? = null,
    // Keeps the fields in the order the prompt describes them.
    val propertyOrdering: List<String>? = null,
    val items: GeminiSchema? = null,
    val enum: List<String>? = null,
    val format: String? = null,
)

// The JSON key is snake_case even though the rest of this API is camelCase.
@Serializable
data class GeminiTool(@SerialName("google_search") val googleSearch: GeminiGoogleSearch? = null)

@Serializable
class GeminiGoogleSearch

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    val promptFeedback: GeminiPromptFeedback? = null,
    val usageMetadata: GeminiUsage? = null,
    val modelVersion: String? = null,
    // A failure after the stream started arrives as a chunk of its own.
    val error: GeminiError? = null,
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null,
    // "STOP" is a normal end. "MAX_TOKENS" means the JSON is cut off; check before parsing.
    val finishReason: String? = null,
    val groundingMetadata: GeminiGroundingMetadata? = null,
)

@Serializable
data class GeminiPromptFeedback(val blockReason: String? = null)

@Serializable
data class GeminiGroundingMetadata(
    val webSearchQueries: List<String> = emptyList(),
    val groundingChunks: List<GeminiGroundingChunk> = emptyList(),
)

@Serializable
data class GeminiGroundingChunk(val web: GeminiWebChunk? = null)

@Serializable
data class GeminiWebChunk(val uri: String? = null, val title: String? = null)

@Serializable
data class GeminiUsage(
    val promptTokenCount: Long = 0,
    val candidatesTokenCount: Long = 0,
    // Reasoning is billed as output but reported separately.
    val thoughtsTokenCount: Long = 0,
    // Search results fed back to the model, billed as input.
    val toolUsePromptTokenCount: Long = 0,
)

@Serializable
data class GeminiErrorEnvelope(val error: GeminiError? = null)

@Serializable
data class GeminiError(val code: Int = 0, val message: String = "", val status: String = "")
