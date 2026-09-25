package com.spacecorps.oam

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A text-generation backend: Gemini Nano through ML Kit (`GeminiNanoModel`
 * in oam-mlkit), a scripted model for tests ([com.spacecorps.oam.testing.ScriptedLanguageModel]),
 * or any OpenAI-compatible server for development ([com.spacecorps.oam.openai.OpenAICompatibleModel]).
 *
 * The contract is deliberately small because Gemini Nano's is: one prompt
 * in (with an optional system instruction), streamed text out. There are no
 * roles, no chat session and no native tool calling; [Agent] builds all of
 * that on top.
 */
public interface LanguageModel {
    /** What the model supports. */
    public val capabilities: ModelCapabilities

    /** Whether the model can run now, or must be downloaded first. */
    public suspend fun availability(): ModelAvailability

    /**
     * Counts the tokens [text] uses, or returns `null` when the backend
     * cannot count; callers then estimate about four characters per token.
     */
    public suspend fun countTokens(text: String): Int?

    /**
     * Generates text for [request] as a stream of chunks. Every chunk carries
     * the new [GenerationChunk.delta] and the full [GenerationChunk.text] so
     * far; the last chunk has [GenerationChunk.isFinal] set and, when known,
     * the token usage.
     *
     * Failures are thrown from the flow, preferably as [AgentError] with the
     * matching [AgentErrorCode] (for example [AgentErrorCode.RATE_LIMITED]
     * for a quota, so [RetryPolicy] can back off). Cancelling the collector
     * must stop generation.
     */
    public fun generate(request: GenerationRequest): Flow<GenerationChunk>

    /**
     * Loads model resources ahead of the first request to cut latency. The
     * default does nothing.
     *
     * @param systemInstruction The system instruction later requests will use.
     * @param promptPrefix A prompt prefix later requests will start with, for backends with prefix caching.
     */
    public suspend fun prewarm(systemInstruction: String? = null, promptPrefix: String? = null) {}
}

/**
 * What a [LanguageModel] supports.
 *
 * @property nativeToolCalling The backend takes tool definitions and returns
 *   tool calls itself. False for Gemini Nano today; [Agent] then runs its
 *   prompt-envelope tool loop. When it becomes true the agent will hand
 *   tools to the backend instead, without changing its public API.
 * @property systemInstructions The backend accepts a separate system
 *   instruction (nano-v3 and later). Otherwise [Agent] puts the instruction
 *   at the top of the prompt.
 * @property maxInputTokens Input budget per request; history is trimmed to fit.
 * @property maxOutputTokens Maximum tokens per response.
 * @property modelName A display name, for example `"nano-v4"`.
 */
@Serializable
public data class ModelCapabilities(
    public val nativeToolCalling: Boolean = false,
    public val systemInstructions: Boolean = true,
    public val maxInputTokens: Int = GEMINI_NANO_MAX_INPUT_TOKENS,
    public val maxOutputTokens: Int = GEMINI_NANO_MAX_OUTPUT_TOKENS,
    public val modelName: String? = null,
) {
    public companion object {
        /** Gemini Nano accepts inputs below about 4000 tokens. */
        public const val GEMINI_NANO_MAX_INPUT_TOKENS: Int = 4000

        /** Gemini Nano's output cap since ML Kit GenAI beta3. */
        public const val GEMINI_NANO_MAX_OUTPUT_TOKENS: Int = 4096
    }
}

/** Whether a model can run. */
@Serializable
public sealed interface ModelAvailability {
    /** True when requests can run now. */
    public val isAvailable: Boolean get() = this is Available

    /** The model is ready. */
    @Serializable
    @SerialName("available")
    public data object Available : ModelAvailability

    /** The device supports the model, but it must be downloaded first. */
    @Serializable
    @SerialName("downloadable")
    public data object Downloadable : ModelAvailability

    /** The model is downloading. Byte counts are included when known. */
    @Serializable
    @SerialName("downloading")
    public data class Downloading(
        public val bytesDownloaded: Long? = null,
        public val totalBytes: Long? = null,
    ) : ModelAvailability

    /**
     * The model cannot run.
     *
     * @property reason A stable machine-readable reason, such as
     *   `"device_not_supported"`, `"aicore_unavailable"`, `"unreachable"` or `"unknown"`.
     * @property detail A human-readable explanation.
     */
    @Serializable
    @SerialName("unavailable")
    public data class Unavailable(public val reason: String, public val detail: String? = null) : ModelAvailability
}

/**
 * One request to a [LanguageModel].
 *
 * The full prompt is [promptPrefix] followed by [prompt]. The prefix is the
 * part that stays the same across requests (a backend with prefix caching,
 * such as ML Kit's `promptPrefix`, can reuse its computation); backends
 * without caching simply concatenate the two.
 *
 * @property prompt The prompt text after the prefix.
 * @property systemInstruction The system instruction, byte-stable across a
 *   session's requests. `null` when the backend lacks system instruction
 *   support (the agent then folds it into the prompt) or there is none.
 * @property promptPrefix A cacheable leading part of the prompt.
 * @property temperature Sampling temperature; `null` for the backend default.
 * @property topK Top-K sampling; `null` for the backend default.
 * @property seed Random seed; `null` for the backend default.
 * @property maxOutputTokens Output token limit; `null` for the backend default.
 * @property kind What the agent is asking for (for logs, tests and backend tuning).
 * @property tools Tool definitions, only for backends with [ModelCapabilities.nativeToolCalling].
 * @property turn The agent's current turn (the user's prompt and tool uses so far). Informational, for
 *   scripted models and logs; real backends must rely on the rendered prompt only.
 */
@Serializable
public data class GenerationRequest(
    public val prompt: String,
    public val systemInstruction: String? = null,
    public val promptPrefix: String? = null,
    public val temperature: Double? = null,
    public val topK: Int? = null,
    public val seed: Int? = null,
    public val maxOutputTokens: Int? = null,
    public val kind: GenerationKind = GenerationKind.TEXT,
    public val tools: List<ToolDefinition> = emptyList(),
    public val turn: TurnSnapshot? = null,
) {
    /** [promptPrefix] followed by [prompt]. */
    public val fullPrompt: String get() = (promptPrefix ?: "") + prompt
}

/**
 * The state of the agent's current turn when a model step runs.
 *
 * @property prompt What the user said this turn.
 * @property toolUses Tool calls made so far this turn, with their outputs.
 */
@Serializable
public data class TurnSnapshot(
    public val prompt: String,
    public val toolUses: List<TranscriptEntry.ToolUse> = emptyList(),
)

/** Why the agent calls the model. */
@Serializable
public enum class GenerationKind {
    /** Choose the next action as a JSON step envelope. */
    @SerialName("decide")
    DECIDE,

    /** Produce the JSON arguments of one named tool. */
    @SerialName("toolArguments")
    TOOL_ARGUMENTS,

    /** Write the natural-language reply. */
    @SerialName("respond")
    RESPOND,

    /** Write a JSON value matching a schema. */
    @SerialName("structured")
    STRUCTURED,

    /** Summarize older conversation for history compaction. */
    @SerialName("summary")
    SUMMARY,

    /** A plain request made outside the agent loop. */
    @SerialName("text")
    TEXT,
}

/**
 * A piece of streamed output.
 *
 * @property delta Text added since the previous chunk.
 * @property text All text so far.
 * @property isFinal True on the last chunk.
 * @property usage Token usage, on the final chunk when the backend reports it.
 * @property finishReason Why generation stopped, on the final chunk when known.
 */
public data class GenerationChunk(
    public val delta: String,
    public val text: String,
    public val isFinal: Boolean = false,
    public val usage: TokenUsage? = null,
    public val finishReason: FinishReason? = null,
)

/** Why a model stopped generating. */
public enum class FinishReason {
    /** The model finished its answer. */
    STOP,

    /** The output token limit was reached (JSON may be truncated). */
    MAX_TOKENS,

    /** Anything else, such as a safety stop. */
    OTHER,
}

/** Rough token estimate used when a backend cannot count: about four characters per token. */
public fun estimateTokens(text: String): Int = (text.length + 3) / 4
