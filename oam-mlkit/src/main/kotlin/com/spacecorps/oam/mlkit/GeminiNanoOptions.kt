package com.spacecorps.oam.mlkit

import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.spacecorps.oam.GenerationKind
import java.util.concurrent.ExecutorService

/**
 * How [GeminiNanoModel] configures ML Kit's Prompt API client.
 *
 * @property preference Which Gemini Nano variant to prefer on devices that
 *   ship more than one (nano-v4: Fast is E2B, Full is E4B). `null` keeps ML
 *   Kit's default, which is [Preference.FULL] for the stable release stage
 *   and [Preference.FAST] for the preview stage.
 * @property releaseStage Stable or preview models. `null` keeps ML Kit's
 *   default ([ReleaseStage.STABLE]). Preview models are for the AICore
 *   Developer Preview: the ML Kit GenAI terms forbid them in production.
 * @property thinkingFor Request kinds that run with thinking mode on, for
 *   example `setOf(GenerationKind.DECIDE)` for more careful tool choices.
 *   Only honoured where ML Kit reports thinking support (nano-v4 and later);
 *   it adds latency, so the default is off everywhere.
 * @property prefixCaching Send `GenerationRequest.promptPrefix` as ML Kit's
 *   `PromptPrefix`, so AICore can cache the stable part of the prompt
 *   (implicit prefix caching). Only honoured where ML Kit reports caching
 *   support; otherwise, or when false, the prefix is sent as ordinary prompt text.
 * @property maxInputTokens Overrides the input budget reported to the agent.
 *   `null` uses ML Kit's `getTokenLimit()` once the model is available (and
 *   about 4000 tokens before that).
 * @property workerExecutor Executor for ML Kit's background work
 *   (`GenerationConfig.workerExecutor`). `null` keeps ML Kit's own.
 */
public data class GeminiNanoOptions(
    public val preference: Preference? = null,
    public val releaseStage: ReleaseStage? = null,
    public val thinkingFor: Set<GenerationKind> = emptySet(),
    public val prefixCaching: Boolean = true,
    public val maxInputTokens: Int? = null,
    public val workerExecutor: ExecutorService? = null,
) {
    init {
        require(maxInputTokens == null || maxInputTokens >= 256) { "maxInputTokens must be at least 256." }
    }

    /** ML Kit's `ModelPreference`. */
    public enum class Preference(internal val mlKitValue: Int) {
        /** The faster, smaller variant (`ModelPreference.FAST`). */
        FAST(ModelPreference.FAST),

        /** The larger, more capable variant (`ModelPreference.FULL`). */
        FULL(ModelPreference.FULL),
    }

    /** ML Kit's `ModelReleaseStage`. */
    public enum class ReleaseStage(internal val mlKitValue: Int) {
        /** Models released to all users (`ModelReleaseStage.STABLE`). */
        STABLE(ModelReleaseStage.STABLE),

        /** Developer-preview models (`ModelReleaseStage.PREVIEW`); not for production. */
        PREVIEW(ModelReleaseStage.PREVIEW),
    }
}
