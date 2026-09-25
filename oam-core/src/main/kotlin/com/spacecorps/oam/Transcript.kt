package com.spacecorps.oam

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement

/**
 * A saved conversation: instructions, the context note, tool definitions and
 * the history. It is serializable (see [toJson]), so a game can store it in a
 * save file and resume with `Agent(history = transcript)`.
 *
 * @property instructions The agent's instructions when it was saved.
 * @property contextNote The context note (for example a compaction summary).
 * @property tools The tool definitions when it was saved (handlers are not saved).
 * @property entries The history, oldest first. Only complete turns are ever recorded.
 */
@Serializable
public data class Transcript(
    public val instructions: String? = null,
    public val contextNote: String? = null,
    public val tools: List<ToolDefinition> = emptyList(),
    public val entries: List<TranscriptEntry> = emptyList(),
) {
    /** Number of turns (prompts) in [entries]. */
    public val turnCount: Int get() = entries.count { it is TranscriptEntry.Prompt }

    /** Encodes as JSON with a format marker. */
    public fun toJson(): JsonElement = jsonObjectOf(
        "type" to FORMAT,
        "version" to VERSION,
        "transcript" to OamJson.encodeToJsonElement(serializer(), this),
    )

    /** Encodes as a JSON string (see [toJson]). */
    public fun toJsonString(): String = toJson().toJsonString()

    public companion object {
        /** The `type` marker written by [toJson]. */
        public const val FORMAT: String = "open-android-models.Transcript"

        /** The format version written by [toJson]. */
        public const val VERSION: String = "1.0"

        /**
         * Decodes a transcript written by [toJson] (either the wrapper or the bare transcript object).
         *
         * @throws AgentError [AgentErrorCode.INVALID_REQUEST] if it is not a transcript.
         */
        public fun fromJson(json: JsonElement): Transcript {
            val body = json.objectValue?.takeIf { it["type"]?.stringValue == FORMAT }?.get("transcript") ?: json
            return try {
                OamJson.decodeFromJsonElement(serializer(), body)
            } catch (error: SerializationException) {
                throw AgentError(AgentErrorCode.INVALID_REQUEST, "Not a transcript: ${error.message}", cause = error)
            } catch (error: IllegalArgumentException) {
                throw AgentError(AgentErrorCode.INVALID_REQUEST, "Not a transcript: ${error.message}", cause = error)
            }
        }

        /** Decodes a transcript from JSON text. See [fromJson]. */
        public fun fromJsonString(text: String): Transcript {
            val element = try {
                OamJson.parseToJsonElement(text)
            } catch (error: SerializationException) {
                throw AgentError(AgentErrorCode.INVALID_REQUEST, "Not a transcript: ${error.message}", cause = error)
            }
            return fromJson(element)
        }
    }
}

/** One entry of a [Transcript]. A turn is a [Prompt], any [ToolUse]s, then a [Response]. */
@Serializable
public sealed interface TranscriptEntry {
    /** What the user (player) said. May be empty. */
    @Serializable
    @SerialName("prompt")
    public data class Prompt(public val text: String) : TranscriptEntry

    /** A tool call and its output. */
    @Serializable
    @SerialName("toolUse")
    public data class ToolUse(public val call: ToolCall, public val output: ToolOutput) : TranscriptEntry

    /** The reply. [structured] is set for schema turns ([text] is then its JSON). */
    @Serializable
    @SerialName("response")
    public data class Response(public val text: String, public val structured: JsonElement? = null) : TranscriptEntry
}
