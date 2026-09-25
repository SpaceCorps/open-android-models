package com.spacecorps.oam.mlkit

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelConfig
import com.google.mlkit.genai.prompt.PromptPrefix
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generationConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [NanoClient] over ML Kit's `GenerativeModel` (`com.google.mlkit:genai-prompt`). */
internal class MlKitNanoClient(private val model: GenerativeModel) : NanoClient {
    override suspend fun checkStatus(): Int = model.checkStatus()

    override fun download(): Flow<DownloadStatus> = model.download()

    override suspend fun warmup() = model.warmup()

    override suspend fun tokenLimit(): Int = model.getTokenLimit()

    override suspend fun baseModelName(): String = model.getBaseModelName()

    override suspend fun isSystemPromptAvailable(): Boolean = model.isSystemPromptAvailable()

    override suspend fun isCachingFeatureAvailable(): Boolean = model.isCachingFeatureAvailable()

    override suspend fun isStructuredOutputFeatureAvailable(): Boolean = model.isStructuredOutputFeatureAvailable()

    override suspend fun isThinkingModeAvailable(): Boolean = model.isThinkingModeAvailable()

    override suspend fun countTokens(request: NanoRequest): Int = model.countTokens(request.toMlKit()).totalTokens

    override fun generateStream(request: NanoRequest): Flow<NanoChunk> =
        model.generateContentStream(request.toMlKit()).map { response ->
            val candidate = response.candidates.firstOrNull()
            NanoChunk(candidate?.text, candidate?.finishReason)
        }

    override fun close() = model.close()

    companion object {
        /** A client from `Generation.getClient`, configured by [options]. */
        fun create(options: GeminiNanoOptions): MlKitNanoClient {
            if (options.preference == null && options.releaseStage == null && options.workerExecutor == null) {
                return MlKitNanoClient(Generation.getClient())
            }
            val config = generationConfig {
                if (options.preference != null || options.releaseStage != null) {
                    modelConfig = ModelConfig.builder().apply {
                        options.releaseStage?.let { releaseStage = it.mlKitValue }
                        options.preference?.let { preference = it.mlKitValue }
                    }.build()
                }
                options.workerExecutor?.let { workerExecutor = it }
            }
            return MlKitNanoClient(Generation.getClient(config))
        }

        /** The ML Kit request for [this]. Values were already brought into ML Kit's ranges. */
        internal fun NanoRequest.toMlKit(): GenerateContentRequest {
            val builder = GenerateContentRequest.Builder(TextPart(text))
            systemInstruction?.let { builder.systemInstruction = SystemInstruction(it) }
            promptPrefix?.let { builder.promptPrefix = PromptPrefix(it) }
            temperature?.let { builder.temperature = it }
            topK?.let { builder.topK = it }
            seed?.let { builder.seed = it }
            maxOutputTokens?.let { builder.maxOutputTokens = it }
            builder.candidateCount = 1
            if (enableThinking) builder.enableThinking = true
            // Native tool calling seam: ML Kit (through 1.0.0-beta4) has no tool API, so `tools` is
            // always empty here. When it ships, hand the definitions over at this point and map the
            // returned tool calls in generateStream.
            return builder.build()
        }
    }
}
