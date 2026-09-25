package com.spacecorps.oam

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer

/**
 * Token usage. Backends that cannot count (ML Kit reports no usage) get an
 * estimate of about four characters per token.
 */
@Serializable
public data class TokenUsage(
    public val inputTokens: Int = 0,
    public val cachedInputTokens: Int = 0,
    public val outputTokens: Int = 0,
) {
    /** Input plus output tokens. */
    public val totalTokens: Int get() = inputTokens + outputTokens

    /** Element-wise sum. */
    public operator fun plus(other: TokenUsage): TokenUsage = TokenUsage(
        inputTokens + other.inputTokens,
        cachedInputTokens + other.cachedInputTokens,
        outputTokens + other.outputTokens,
    )
}

/**
 * The result of one agent turn.
 *
 * @property text The final reply. For structured turns, the JSON text of [structured].
 * @property structured The structured output, when the turn requested a schema. Keys follow the schema's order.
 * @property toolCalls Tools called during the turn, in completion order.
 * @property usage Tokens used by all model steps of the turn.
 * @property steps Model steps taken (one per model inference).
 */
public data class AgentResponse(
    public val text: String,
    public val structured: JsonElement? = null,
    public val toolCalls: List<ToolRecord> = emptyList(),
    public val usage: TokenUsage = TokenUsage(),
    public val steps: List<ModelStep> = emptyList(),
)

/**
 * Decodes [AgentResponse.structured] into [T].
 *
 * @throws AgentError [AgentErrorCode.GENERATION_FAILED] if there is no structured output or it does not fit [T].
 */
public inline fun <reified T> AgentResponse.decode(): T = decodeWith(serializer())

/** Like [decode] with an explicit [deserializer]. */
public fun <T> AgentResponse.decodeWith(deserializer: DeserializationStrategy<T>): T {
    val value = structured ?: throw AgentError(AgentErrorCode.GENERATION_FAILED, "The response has no structured output.")
    return try {
        OamJson.decodeFromJsonElement(deserializer, value)
    } catch (error: SerializationException) {
        throw AgentError(AgentErrorCode.GENERATION_FAILED, "Could not decode the structured output: ${error.message}", cause = error)
    } catch (error: IllegalArgumentException) {
        throw AgentError(AgentErrorCode.GENERATION_FAILED, "Could not decode the structured output: ${error.message}", cause = error)
    }
}

/** Events emitted while an agent turn runs, in order. */
public sealed interface AgentEvent {
    /** The model is about to run an inference step. */
    public data class ModelStep(public val step: com.spacecorps.oam.ModelStep) : AgentEvent

    /**
     * New reply text. [delta] is the new suffix and [text] the full reply so
     * far. When earlier text is replaced (a retried step), [isReset] is true
     * and [delta] equals [text].
     */
    public data class Text(public val delta: String, public val text: String, public val isReset: Boolean) : AgentEvent

    /**
     * Partially generated structured output (the JSON parsed so far, with
     * open strings and containers closed). A repaired step starts over, so a
     * later partial replaces earlier ones.
     */
    public data class Partial(public val value: JsonElement) : AgentEvent

    /** A local tool started running. */
    public data class ToolCallStarted(public val call: ToolCall) : AgentEvent

    /**
     * An external tool needs the host to run it. Reply with
     * [AgentRun.submit]; the turn waits until you do (or the tool's timeout).
     */
    public data class ToolCallRequested(public val call: ToolCall) : AgentEvent

    /** A tool finished (local or external). */
    public data class ToolCallCompleted(public val record: ToolRecord) : AgentEvent

    /** The turn finished. Always the last event of a successful turn. */
    public data class Completed(public val response: AgentResponse) : AgentEvent
}
