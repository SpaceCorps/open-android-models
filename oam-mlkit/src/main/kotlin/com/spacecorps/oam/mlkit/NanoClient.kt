package com.spacecorps.oam.mlkit

import com.google.mlkit.genai.common.DownloadStatus
import com.spacecorps.oam.ToolDefinition
import kotlinx.coroutines.flow.Flow

/**
 * The slice of ML Kit's `GenerativeModel` that [GeminiNanoModel] uses, in
 * plain Kotlin types.
 *
 * ML Kit hides the constructors of its response types, so the model logic
 * (prompt assembly, streaming, error mapping, feature detection) talks to
 * this seam instead; [MlKitNanoClient] is the real implementation and unit
 * tests use a fake.
 */
internal interface NanoClient {
    /** `GenerativeModel.checkStatus()`: a `FeatureStatus` value. */
    suspend fun checkStatus(): Int

    /** `GenerativeModel.download()`. */
    fun download(): Flow<DownloadStatus>

    /** `GenerativeModel.warmup()`. */
    suspend fun warmup()

    /** `GenerativeModel.getTokenLimit()`. */
    suspend fun tokenLimit(): Int

    /** `GenerativeModel.getBaseModelName()`. */
    suspend fun baseModelName(): String

    /** `GenerativeModel.isSystemPromptAvailable()`. */
    suspend fun isSystemPromptAvailable(): Boolean

    /** `GenerativeModel.isCachingFeatureAvailable()`. */
    suspend fun isCachingFeatureAvailable(): Boolean

    /** `GenerativeModel.isStructuredOutputFeatureAvailable()`. */
    suspend fun isStructuredOutputFeatureAvailable(): Boolean

    /** `GenerativeModel.isThinkingModeAvailable()`. */
    suspend fun isThinkingModeAvailable(): Boolean

    /** `GenerativeModel.countTokens(request).totalTokens`. */
    suspend fun countTokens(request: NanoRequest): Int

    /** `GenerativeModel.generateContentStream(request)`, reduced to the first candidate of each chunk. */
    fun generateStream(request: NanoRequest): Flow<NanoChunk>

    /** `GenerativeModel.close()`. */
    fun close()
}

/**
 * One ML Kit `GenerateContentRequest`, already validated against ML Kit's
 * limits (see [GeminiNanoModel]).
 *
 * @property text The prompt text (after the prefix).
 * @property systemInstruction `SystemInstruction`, only on devices that support it.
 * @property promptPrefix `PromptPrefix` (implicit prefix caching), only on devices that support it.
 * @property temperature 0…1.
 * @property topK Positive.
 * @property seed Non-negative.
 * @property maxOutputTokens 1…4096.
 * @property enableThinking Thinking mode (nano-v4 and later).
 * @property tools Tool definitions for native tool calling. Always empty until ML Kit ships a tool API
 *   ([NanoFeatures.nativeToolCalling]); [MlKitNanoClient] is where they will be handed over.
 */
internal data class NanoRequest(
    val text: String,
    val systemInstruction: String? = null,
    val promptPrefix: String? = null,
    val temperature: Float? = null,
    val topK: Int? = null,
    val seed: Int? = null,
    val maxOutputTokens: Int? = null,
    val enableThinking: Boolean = false,
    val tools: List<ToolDefinition> = emptyList(),
)

/**
 * One streamed `GenerateContentResponse`: the new text of its first candidate
 * (ML Kit streams deltas) and, on the terminal chunk, the candidate's finish
 * reason (`Candidate.FinishReason`). Thinking-mode chunks carry no candidate
 * and have a `null` [text].
 */
internal data class NanoChunk(val text: String?, val finishReason: Int? = null)
