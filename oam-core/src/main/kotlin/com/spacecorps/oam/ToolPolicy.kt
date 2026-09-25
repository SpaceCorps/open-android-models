package com.spacecorps.oam

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How the model may use tools during one turn.
 *
 * Gemini Nano has no native tool calling, so every tool decision is an
 * explicit model step that outputs a JSON *step envelope*
 * (`{"action": "<tool>" | "respond", "arguments": {…}}`). See [Agent] for the loop.
 *
 * JSON form (as on open-apple-models' wire): `"auto"`, `"none"`,
 * `"required"`, `"explicit"` or `{"tool": "name"}`; see [toJson] and [fromJson].
 */
public sealed interface ToolChoice {
    /**
     * The model decides at every step between the enabled tools and
     * `respond`. On Android this is the same as [Explicit]: prompt-based tool
     * use needs an explicit decision anyway.
     */
    public data object Auto : ToolChoice

    /** Tools are off for this turn; the model replies directly. */
    public data object None : ToolChoice

    /** The first step must call a tool (only tools are offered); later steps decide freely. */
    public data object Required : ToolChoice

    /**
     * The first step must make an explicit decision between the tools and
     * `respond`. More reliable grounding than a free reply, without forcing a
     * pointless tool call for small talk.
     */
    public data object Explicit : ToolChoice

    /**
     * The first step calls the named tool: the decision is skipped and the
     * model only writes the arguments (no model step at all for a tool
     * without parameters). Later steps decide freely.
     */
    public data class Tool(public val name: String) : ToolChoice

    /** The wire form. */
    public fun toJson(): JsonElement = when (this) {
        Auto -> JsonPrimitive("auto")
        None -> JsonPrimitive("none")
        Required -> JsonPrimitive("required")
        Explicit -> JsonPrimitive("explicit")
        is Tool -> jsonObjectOf("tool" to name)
    }

    public companion object {
        /**
         * Parses the wire form. Also accepts `"any"` (as required) and
         * OpenAI's `{"type": "function", "function": {"name": …}}` and `{"name": …}`.
         *
         * @throws IllegalArgumentException for anything else.
         */
        public fun fromJson(json: JsonElement): ToolChoice {
            json.stringValue?.let { text ->
                return when (text) {
                    "auto" -> Auto
                    "none" -> None
                    "required", "any" -> Required
                    "explicit" -> Explicit
                    else -> throw IllegalArgumentException("Expected \"auto\", \"none\", \"required\", \"explicit\" or {\"tool\": name}, got \"$text\".")
                }
            }
            val obj = json as? JsonObject ?: throw IllegalArgumentException("Expected a tool choice string or object, got ${json.toJsonString()}.")
            val name = obj["tool"]?.stringValue ?: obj["name"]?.stringValue ?: obj["function"]?.objectValue?.get("name")?.stringValue
                ?: throw IllegalArgumentException("A tool choice object needs \"tool\": name.")
            return Tool(name)
        }
    }
}

/**
 * Per-turn limits on the tool loop.
 *
 * @property choice How tools may be used.
 * @property maxToolRounds Tool rounds (decide → call) per turn. Once reached,
 *   the model has to reply.
 * @property maxToolCalls Tool calls per turn. Once reached, the model has to reply.
 * @property enabledTools Restricts the tools offered this turn (`null` = all).
 */
public data class ToolPolicy(
    public val choice: ToolChoice = ToolChoice.Auto,
    public val maxToolRounds: Int = 4,
    public val maxToolCalls: Int = 12,
    public val enabledTools: Set<String>? = null,
) {
    init {
        require(maxToolRounds >= 0) { "maxToolRounds must not be negative." }
        require(maxToolCalls >= 0) { "maxToolCalls must not be negative." }
    }

    public companion object {
        /** Auto, 4 rounds, 12 calls, all tools. */
        public val Default: ToolPolicy = ToolPolicy()
    }
}

/**
 * How conversation history is fitted into the model's input limit.
 *
 * @property trimsHistory Hide the oldest turns from the model when the
 *   prompt would exceed the input limit. The transcript keeps them.
 * @property reservedTokens Safety margin below [ModelCapabilities.maxInputTokens]
 *   (token counts are often estimates).
 * @property minimumRecentTurns The most recent completed turns that are never trimmed, even if the
 *   prompt then exceeds the limit (the backend reports [AgentErrorCode.CONTEXT_SIZE_EXCEEDED]).
 * @property maxToolOutputChars Longer tool outputs are shortened in the prompt.
 */
public data class ContextPolicy(
    public val trimsHistory: Boolean = true,
    public val reservedTokens: Int = 256,
    public val minimumRecentTurns: Int = 1,
    public val maxToolOutputChars: Int = 1500,
) {
    init {
        require(reservedTokens >= 0) { "reservedTokens must not be negative." }
        require(minimumRecentTurns >= 0) { "minimumRecentTurns must not be negative." }
        require(maxToolOutputChars >= 50) { "maxToolOutputChars must be at least 50." }
    }

    public companion object {
        /** Trim, keep 256 tokens spare, keep the last turn, cut tool outputs at 1500 characters. */
        public val Default: ContextPolicy = ContextPolicy()
    }
}

/** Whether a model step could call tools (open-apple-models' `toolCallingMode`). */
@Serializable
public enum class ToolCallingMode {
    /** Tools and `respond` were offered. */
    @SerialName("allowed")
    ALLOWED,

    /** A tool call was required (only tools offered, or one tool's arguments requested). */
    @SerialName("required")
    REQUIRED,

    /** No tools: the reply (or structured output) step. */
    @SerialName("disallowed")
    DISALLOWED,
}

/** What a model step produced. */
@Serializable
public enum class StepKind {
    /** A decision (step envelope). */
    @SerialName("decide")
    DECIDE,

    /** The arguments of one named tool ([ToolChoice.Tool]). */
    @SerialName("toolArguments")
    TOOL_ARGUMENTS,

    /** The natural-language reply. */
    @SerialName("respond")
    RESPOND,

    /** The schema-shaped reply. */
    @SerialName("structured")
    STRUCTURED,
}

/**
 * One model inference within a turn.
 *
 * @property index Zero-based step index within the turn.
 * @property completedToolRounds Tool rounds completed before this step.
 * @property toolCallingMode Whether tools could be called.
 * @property enabledTools Tools offered in this step.
 * @property trimmedEntries History entries hidden from the model to fit the input limit.
 * @property kind What the step produced.
 * @property isRepair True when the step re-asks after invalid output.
 */
@Serializable
public data class ModelStep(
    public val index: Int,
    public val completedToolRounds: Int,
    public val toolCallingMode: ToolCallingMode,
    public val enabledTools: List<String>,
    public val trimmedEntries: Int,
    public val kind: StepKind,
    public val isRepair: Boolean = false,
)
