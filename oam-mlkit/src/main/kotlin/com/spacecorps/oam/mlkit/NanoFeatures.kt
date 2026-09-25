package com.spacecorps.oam.mlkit

/**
 * What the device's Gemini Nano supports, as reported by ML Kit once the
 * model is available. A check that fails counts as unsupported.
 *
 * @property baseModelName `getBaseModelName()`, for example the nano version; `null` if unknown.
 * @property tokenLimit `getTokenLimit()`: the request (input) token limit; `null` if unknown.
 * @property systemInstructions `isSystemPromptAvailable()` (nano-v3 and later).
 * @property prefixCaching `isCachingFeatureAvailable()`.
 * @property structuredOutput `isStructuredOutputFeatureAvailable()`. ML Kit's structured output only
 *   takes classes compiled with its KSP schema compiler, not runtime JSON Schemas, so the agent validates
 *   JSON itself; this flag is informational.
 * @property thinking `isThinkingModeAvailable()` (nano-v4 and later).
 * @property nativeToolCalling Whether ML Kit can take tool definitions and return tool calls itself.
 *   Always false: ML Kit GenAI (through 1.0.0-beta4) has no tool-calling API. When it ships, detection
 *   goes here and [GeminiNanoModel] reports it through `ModelCapabilities.nativeToolCalling`.
 */
public data class NanoFeatures(
    public val baseModelName: String? = null,
    public val tokenLimit: Int? = null,
    public val systemInstructions: Boolean = false,
    public val prefixCaching: Boolean = false,
    public val structuredOutput: Boolean = false,
    public val thinking: Boolean = false,
    public val nativeToolCalling: Boolean = false,
)

/** Progress of a Gemini Nano download, from [GeminiNanoModel.download]. */
public sealed interface DownloadEvent {
    /** The download began; [totalBytes] is the size to fetch. */
    public data class Started(public val totalBytes: Long) : DownloadEvent

    /**
     * Bytes arrived.
     *
     * @property bytesDownloaded Bytes downloaded so far.
     * @property totalBytes The download's size, when a [Started] event reported it.
     */
    public data class Progress(public val bytesDownloaded: Long, public val totalBytes: Long?) : DownloadEvent {
        /** 0…1, or `null` when the size is unknown. */
        public val fraction: Float?
            get() = totalBytes?.takeIf { it > 0 }?.let { (bytesDownloaded.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
    }

    /** The model is downloaded and ready (also emitted at once when it already was). */
    public data object Completed : DownloadEvent
}
