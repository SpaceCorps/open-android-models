package com.spacecorps.oam.mlkit

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Candidate
import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentErrorCode
import com.spacecorps.oam.FinishReason
import com.spacecorps.oam.GenerationChunk
import com.spacecorps.oam.GenerationRequest
import com.spacecorps.oam.LanguageModel
import com.spacecorps.oam.ModelAvailability
import com.spacecorps.oam.ModelCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Gemini Nano, Android's built-in on-device model, as a [LanguageModel]:
 * the ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt`) running on
 * the AICore system service.
 *
 * ```kotlin
 * val nano = GeminiNanoModel()
 * when (nano.availability()) {
 *     ModelAvailability.Downloadable -> nano.download().collect { println(it) }
 *     is ModelAvailability.Unavailable -> return   // not an AICore device
 *     else -> {}
 * }
 * val agent = Agent(nano, instructions = "You are Mira, an innkeeper.", tools = listOf(menuTool))
 * ```
 *
 * **What it maps.**
 * - [availability]: `checkStatus()` (`AVAILABLE`, `DOWNLOADABLE`, `DOWNLOADING`, `UNAVAILABLE`), with
 *   live byte counts while [download] runs. Unavailable reasons are in [GeminiNanoErrors.Reasons].
 * - [capabilities]: detected once the model is available: `isSystemPromptAvailable()`,
 *   `getTokenLimit()` and `getBaseModelName()` (see [detectFeatures]). Until then it assumes a
 *   nano-v3+ device (system instructions, about 4000 input tokens).
 * - [generate]: `generateContentStream()`, with the system instruction as ML Kit's
 *   `SystemInstruction` where supported and at the top of the prompt otherwise; the prompt
 *   prefix as `PromptPrefix` where prefix caching is supported; temperature (clamped to ML
 *   Kit's 0…1), top-K, seed and the output limit (clamped to 1…4096); thinking mode per
 *   [GeminiNanoOptions.thinkingFor]. Failures are thrown as [AgentError] ([GeminiNanoErrors]).
 * - [countTokens]: `countTokens()`, or `null` when it fails.
 * - [prewarm]: `warmup()`.
 *
 * **Platform rules.** Inference only runs while the app is the top
 * foreground app (otherwise [AgentErrorCode.RATE_LIMITED] from
 * `BACKGROUND_USE_BLOCKED`), AICore enforces per-app quotas, and the safety
 * filters are not configurable. There is no emulator support. Read the
 * [ML Kit GenAI terms](https://developers.google.com/ml-kit/genai-terms)
 * before shipping: among other things they restrict use in services likely to
 * be accessed by people under 18.
 *
 * **Tool calling.** ML Kit has no tool-calling API yet, so
 * `capabilities.nativeToolCalling` is false and [com.spacecorps.oam.Agent]
 * runs its prompt-envelope loop. [NanoFeatures.nativeToolCalling] is where
 * native support will be detected when ML Kit ships it.
 *
 * The model is safe to use from any thread. [close] releases the ML Kit client.
 */
public class GeminiNanoModel internal constructor(
    private val client: NanoClient,
    /** The options this model was created with. */
    public val options: GeminiNanoOptions,
) : LanguageModel, AutoCloseable {

    /** A model over ML Kit's Prompt API client, configured by [options]. */
    public constructor(options: GeminiNanoOptions = GeminiNanoOptions()) : this(MlKitNanoClient.create(options), options)

    private val featureLock = Mutex()
    private val features = MutableStateFlow<NanoFeatures?>(null)
    private val capabilityState = MutableStateFlow(capabilitiesFor(null))
    private val progress = MutableStateFlow<ModelAvailability.Downloading?>(null)

    @Volatile
    private var cachingBroken = false

    /**
     * What the model supports. Before [detectFeatures] has run on an
     * available model this assumes a nano-v3+ device; afterwards it reflects
     * the device. [capabilityUpdates] reports changes.
     */
    override val capabilities: ModelCapabilities get() = capabilityState.value

    /** [capabilities] as a flow. */
    public val capabilityUpdates: StateFlow<ModelCapabilities> = capabilityState.asStateFlow()

    /** Features detected by [detectFeatures], or `null` before detection. */
    public val detectedFeatures: StateFlow<NanoFeatures?> = features.asStateFlow()

    /** The running [download]'s progress, or `null` when no download runs in this process. */
    public val downloadProgress: StateFlow<ModelAvailability.Downloading?> = progress.asStateFlow()

    override suspend fun availability(): ModelAvailability {
        val status = try {
            client.checkStatus()
        } catch (error: CancellationException) {
            throw error
        } catch (error: GenAiException) {
            return ModelAvailability.Unavailable(GeminiNanoErrors.unavailableReason(error.errorCode), GeminiNanoErrors.toAgentError(error).message)
        } catch (error: Exception) {
            return ModelAvailability.Unavailable(GeminiNanoErrors.Reasons.UNKNOWN, error.message ?: error::class.java.name)
        }
        return when (status) {
            FeatureStatus.AVAILABLE -> {
                detect(refresh = false, knownAvailable = true)
                ModelAvailability.Available
            }
            FeatureStatus.DOWNLOADABLE -> ModelAvailability.Downloadable
            FeatureStatus.DOWNLOADING -> progress.value ?: ModelAvailability.Downloading()
            FeatureStatus.UNAVAILABLE -> ModelAvailability.Unavailable(
                GeminiNanoErrors.Reasons.DEVICE_NOT_ELIGIBLE,
                "Gemini Nano is not supported on this device (it needs an AICore device with the Prompt API).",
            )
            else -> ModelAvailability.Unavailable(GeminiNanoErrors.Reasons.UNKNOWN, "Unknown ML Kit feature status $status.")
        }
    }

    /**
     * Detects the device's features (see [NanoFeatures]) and updates
     * [capabilities]. Detection runs once, when the model is available;
     * [refresh] runs it again (for example after an AICore update).
     *
     * @return The features, or `null` while the model is not available.
     */
    public suspend fun detectFeatures(refresh: Boolean = false): NanoFeatures? = detect(refresh, knownAvailable = false)

    private suspend fun detect(refresh: Boolean, knownAvailable: Boolean): NanoFeatures? {
        if (!refresh) features.value?.let { return it }
        return featureLock.withLock {
            if (!refresh) features.value?.let { return@withLock it }
            if (!knownAvailable && optional { client.checkStatus() } != FeatureStatus.AVAILABLE) return@withLock null
            val detected = NanoFeatures(
                baseModelName = optional { client.baseModelName() }?.takeIf { it.isNotBlank() },
                tokenLimit = optional { client.tokenLimit() }?.takeIf { it > 0 },
                systemInstructions = optional { client.isSystemPromptAvailable() } == true,
                prefixCaching = optional { client.isCachingFeatureAvailable() } == true,
                structuredOutput = optional { client.isStructuredOutputFeatureAvailable() } == true,
                thinking = optional { client.isThinkingModeAvailable() } == true,
                // Native tool calling: ML Kit has no API for it yet. Detect it here once it ships.
                nativeToolCalling = false,
            )
            features.value = detected
            capabilityState.value = capabilitiesFor(detected)
            detected
        }
    }

    private fun capabilitiesFor(features: NanoFeatures?): ModelCapabilities = ModelCapabilities(
        nativeToolCalling = features?.nativeToolCalling ?: false,
        systemInstructions = features?.systemInstructions ?: true,
        maxInputTokens = options.maxInputTokens ?: features?.tokenLimit ?: ModelCapabilities.GEMINI_NANO_MAX_INPUT_TOKENS,
        maxOutputTokens = ModelCapabilities.GEMINI_NANO_MAX_OUTPUT_TOKENS,
        modelName = features?.baseModelName ?: DEFAULT_MODEL_NAME,
    )

    /**
     * Downloads Gemini Nano, reporting progress. Completes at once with
     * [DownloadEvent.Completed] when the model is already available; while
     * AICore is already downloading it, this follows that download.
     * [downloadProgress] and [availability] report the progress too.
     *
     * The flow is cold: collect it to download. Cancelling the collector
     * stops listening; whether AICore keeps downloading is up to AICore.
     *
     * @throws AgentError [AgentErrorCode.MODEL_UNAVAILABLE] when the device cannot run
     *   Gemini Nano or the download fails (for example `NOT_ENOUGH_DISK_SPACE`).
     */
    public fun download(): Flow<DownloadEvent> = flow {
        val status = try {
            client.checkStatus()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw unavailable(error)
        }
        when (status) {
            FeatureStatus.AVAILABLE -> {
                emit(DownloadEvent.Completed)
                return@flow
            }
            FeatureStatus.UNAVAILABLE -> throw AgentError(AgentErrorCode.MODEL_UNAVAILABLE, "Gemini Nano is not supported on this device.")
        }
        var total: Long? = null
        try {
            client.download().collect { event ->
                when (event) {
                    is DownloadStatus.DownloadStarted -> {
                        total = event.bytesToDownload.takeIf { it > 0 }
                        progress.value = ModelAvailability.Downloading(0, total)
                        emit(DownloadEvent.Started(event.bytesToDownload))
                    }
                    is DownloadStatus.DownloadProgress -> {
                        progress.value = ModelAvailability.Downloading(event.totalBytesDownloaded, total)
                        emit(DownloadEvent.Progress(event.totalBytesDownloaded, total))
                    }
                    is DownloadStatus.DownloadCompleted -> {
                        progress.value = null
                        detect(refresh = true, knownAvailable = false)
                        emit(DownloadEvent.Completed)
                    }
                    is DownloadStatus.DownloadFailed -> throw unavailable(event.e)
                }
            }
        } finally {
            progress.value = null
        }
    }

    private fun unavailable(error: Throwable): AgentError {
        val mapped = GeminiNanoErrors.toAgentError(error)
        return if (mapped.code == AgentErrorCode.MODEL_UNAVAILABLE) {
            mapped
        } else {
            AgentError(AgentErrorCode.MODEL_UNAVAILABLE, "Gemini Nano download failed: ${mapped.message}", cause = error)
        }
    }

    override suspend fun countTokens(text: String): Int? = optional {
        client.countTokens(NanoRequest(text = text))
    }?.takeIf { it >= 0 }

    override fun generate(request: GenerationRequest): Flow<GenerationChunk> = flow {
        val features = detect(refresh = false, knownAvailable = false)
        var nano = toNanoRequest(request, features)
        // ML Kit streams deltas and puts the finish reason on the terminal chunk.
        val text = StringBuilder()
        var finish: Int? = null
        while (true) {
            var failure: Throwable? = null
            client.generateStream(nano)
                .catch { failure = it } // upstream (ML Kit) failures only
                .collect { chunk ->
                    chunk.finishReason?.let { finish = it }
                    val delta = chunk.text
                    if (!delta.isNullOrEmpty()) {
                        text.append(delta)
                        emit(GenerationChunk(delta, text.toString()))
                    }
                }
            val error = failure ?: break
            if (GeminiNanoErrors.isPlainCancellation(error)) throw error
            val mapped = GeminiNanoErrors.toAgentError(error)
            val prefix = nano.promptPrefix
            if (prefix != null && text.isEmpty() && mapped.mlKitErrorCode == CACHE_PROCESSING_ERROR) {
                // The prefix cache failed before any output: stop using it and send the prefix as prompt text.
                cachingBroken = true
                nano = nano.copy(text = prefix + nano.text, promptPrefix = null)
                continue
            }
            throw mapped
        }
        val reason = when (finish) {
            null, Candidate.FinishReason.STOP -> FinishReason.STOP
            Candidate.FinishReason.MAX_TOKENS -> FinishReason.MAX_TOKENS
            else -> FinishReason.OTHER
        }
        if (text.isEmpty() && reason == FinishReason.OTHER) {
            throw AgentError(AgentErrorCode.GENERATION_FAILED, "Gemini Nano stopped without output.")
        }
        emit(GenerationChunk("", text.toString(), isFinal = true, usage = null, finishReason = reason))
    }

    /** Builds the ML Kit request, honouring the device's features and ML Kit's parameter ranges. */
    internal fun toNanoRequest(request: GenerationRequest, features: NanoFeatures?): NanoRequest {
        val systemSupported = features?.systemInstructions ?: capabilities.systemInstructions
        val cachingSupported = options.prefixCaching && !cachingBroken && features?.prefixCaching == true
        val system = request.systemInstruction?.takeIf { it.isNotEmpty() }
        val prefix = request.promptPrefix?.takeIf { it.isNotEmpty() }
        // Without system-instruction support the instruction leads the prompt, ahead of the prefix,
        // so the cacheable part stays one stable block.
        val leading = buildString {
            if (system != null && !systemSupported) append(system).append("\n\n")
            if (prefix != null) append(prefix)
        }.takeIf { it.isNotEmpty() }
        val (promptPrefix, text) = when {
            leading == null -> null to request.prompt
            cachingSupported -> leading to request.prompt
            else -> null to leading + request.prompt
        }
        return NanoRequest(
            text = text,
            systemInstruction = system?.takeIf { systemSupported },
            promptPrefix = promptPrefix,
            temperature = request.temperature?.toFloat()?.coerceIn(0f, 1f),
            topK = request.topK?.takeIf { it > 0 },
            seed = request.seed?.let { it and Int.MAX_VALUE },
            maxOutputTokens = request.maxOutputTokens?.coerceIn(1, ModelCapabilities.GEMINI_NANO_MAX_OUTPUT_TOKENS),
            enableThinking = features?.thinking == true && request.kind in options.thinkingFor,
            // Native tool calling seam: hand tools to ML Kit once it can take them.
            tools = if (features?.nativeToolCalling == true) request.tools else emptyList(),
        )
    }

    /**
     * Calls ML Kit's `warmup()` and detects features, so the first request
     * starts faster. Best effort: failures are ignored (the next request
     * reports them). The instruction and prefix are not used: ML Kit's warmup
     * takes none, and implicit prefix caching fills on the first request.
     */
    override suspend fun prewarm(systemInstruction: String?, promptPrefix: String?) {
        optional { client.warmup() }
        detect(refresh = false, knownAvailable = false)
    }

    /** Releases the ML Kit client. The model cannot be used afterwards. */
    override fun close() {
        client.close()
    }

    override fun toString(): String = "GeminiNanoModel(${capabilities.modelName})"

    public companion object {
        /** [ModelCapabilities.modelName] until ML Kit reports the base model's name. */
        public const val DEFAULT_MODEL_NAME: String = "gemini-nano"

        private const val CACHE_PROCESSING_ERROR = GenAiException.ErrorCode.CACHE_PROCESSING_ERROR

        /** Runs [block], returning `null` on any failure except cancellation. */
        private suspend fun <T> optional(block: suspend () -> T): T? = try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }
}
