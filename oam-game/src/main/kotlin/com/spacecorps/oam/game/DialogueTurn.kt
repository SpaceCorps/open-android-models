package com.spacecorps.oam.game

import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentErrorCode
import com.spacecorps.oam.TokenUsage
import com.spacecorps.oam.ToolRecord
import com.spacecorps.oam.Transcript
import com.spacecorps.oam.objectValue
import com.spacecorps.oam.toJsonString
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement

/**
 * One reply from an [NPC]. Serializable (see [GameJson]).
 *
 * @property line What the NPC says, cleaned: no speaker label and no wrapping quotes.
 * @property emotion How the NPC feels saying it.
 * @property playerOptions Short replies the player might choose next (may be empty).
 * @property endsConversation Whether the NPC ended the conversation.
 * @property toolCalls Tools executed during this turn, in completion order (including any
 *   run before a retry or a fallback: their side effects happened).
 * @property relationship The NPC's attitude toward the player after this turn (`-100..100`).
 * @property isFallback True when the reply was unusable (blocked by the safety filters, refused, or
 *   invalid) and a line from [NPCOptions.fallbackLines] was used instead.
 * @property usage Tokens used by this turn (estimated when the backend does not report usage).
 */
@Serializable
public data class DialogueTurn(
    public val line: String,
    public val emotion: Emotion = Emotion.NEUTRAL,
    public val playerOptions: List<String> = emptyList(),
    public val endsConversation: Boolean = false,
    public val toolCalls: List<ToolRecord> = emptyList(),
    public val relationship: Int = 0,
    public val isFallback: Boolean = false,
    public val usage: TokenUsage = TokenUsage(),
)

/**
 * Events streamed by [NPC.talkStream], in order.
 *
 * For a typewriter effect, append [LineDelta] text and replace the displayed
 * text on [LineReset]. By the time [Completed] arrives, the deltas and
 * resets add up to exactly [DialogueTurn.line].
 */
public sealed interface DialogueEvent {
    /**
     * The NPC's emotion for this turn, sent as soon as it is known (before
     * most of the line) so a portrait or animation can react early. Sent at
     * least once per turn; a later one replaces an earlier one.
     */
    public data class Emotion(public val emotion: com.spacecorps.oam.game.Emotion) : DialogueEvent

    /** New text to append to the displayed line. */
    public data class LineDelta(public val text: String) : DialogueEvent

    /**
     * Replace the displayed line with [text] (rare: the model rewrote its
     * output, a retry started over, or a fallback line replaced a blocked one).
     */
    public data class LineReset(public val text: String) : DialogueEvent

    /** A local tool (a game tool with a handler, or a built-in world or memory tool) started running. */
    public data class ToolCall(public val call: com.spacecorps.oam.ToolCall) : DialogueEvent

    /**
     * An external tool needs the game to run it. Reply with
     * [DialogueStream.submit]; the turn waits until you do.
     */
    public data class ExternalToolCall(public val call: com.spacecorps.oam.ToolCall) : DialogueEvent

    /** A tool (local or external) finished. */
    public data class ToolResult(public val record: ToolRecord) : DialogueEvent

    /** The turn finished. Always the last event of a successful turn. */
    public data class Completed(public val turn: DialogueTurn) : DialogueEvent
}

/**
 * Everything needed to restore an [NPC]: who it is, what it remembers and
 * the conversation so far. Store [toJsonString] in a save file and pass the
 * decoded state to [NPC.restore]. Tools, the world and options are code, so
 * they are passed again when restoring.
 *
 * @property version Format version, for migrations.
 * @property persona Who the NPC is.
 * @property memory What it remembers.
 * @property transcript The conversation, trimmed to complete turns.
 */
@Serializable
public data class NPCSaveState(
    public val version: Int = CURRENT_VERSION,
    public val persona: Persona,
    public val memory: NPCMemory = NPCMemory(),
    public val transcript: Transcript = Transcript(),
) {
    /** Encodes as a JSON object (see [GameJson]). */
    public fun toJson(): JsonElement = GameJson.encodeToJsonElement(serializer(), this)

    /** Encodes as JSON text. */
    public fun toJsonString(): String = toJson().toJsonString()

    public companion object {
        /** The format version this library writes. */
        public const val CURRENT_VERSION: Int = 1

        /**
         * Decodes a save state written by [toJson].
         *
         * @throws AgentError [AgentErrorCode.INVALID_REQUEST] if it is not a save state.
         */
        public fun fromJson(json: JsonElement): NPCSaveState {
            if (json.objectValue == null) throw AgentError(AgentErrorCode.INVALID_REQUEST, "An NPC save state must be a JSON object.")
            val state = try {
                GameJson.decodeFromJsonElement(serializer(), json)
            } catch (error: SerializationException) {
                throw AgentError(AgentErrorCode.INVALID_REQUEST, "Not an NPC save state: ${error.message}", cause = error)
            } catch (error: IllegalArgumentException) {
                throw AgentError(AgentErrorCode.INVALID_REQUEST, "Not an NPC save state: ${error.message}", cause = error)
            }
            if (state.persona.name.isBlank()) throw AgentError(AgentErrorCode.INVALID_REQUEST, "The saved persona has no name.")
            return state
        }

        /** Decodes a save state from JSON text. See [fromJson]. */
        public fun fromJsonString(text: String): NPCSaveState {
            val element = try {
                GameJson.parseToJsonElement(text)
            } catch (error: SerializationException) {
                throw AgentError(AgentErrorCode.INVALID_REQUEST, "Not an NPC save state: ${error.message}", cause = error)
            }
            return fromJson(element)
        }
    }
}
