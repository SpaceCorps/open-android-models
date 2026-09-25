package com.spacecorps.oam.game

import com.spacecorps.oam.ToolChoice
import com.spacecorps.oam.arrayValue
import com.spacecorps.oam.intValue
import com.spacecorps.oam.stringValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Built-in memory tools an [NPC] can offer the model.
 *
 * @property wireName The name used in JSON (`"rememberFact"`, as in open-apple-models' bridge).
 * @property toolName The tool name the model sees.
 */
public enum class NPCMemoryTool(public val wireName: String, public val toolName: String) {
    /** `remember_fact(fact)`: the model stores a fact in [NPCMemory.facts]. */
    REMEMBER_FACT("rememberFact", "remember_fact"),

    /** `change_relationship(reason, delta)`: the model adjusts [NPCMemory.relationship]. */
    CHANGE_RELATIONSHIP("changeRelationship", "change_relationship"),
    ;

    public companion object {
        /** Both memory tools. */
        public val ALL: Set<NPCMemoryTool> = entries.toSet()

        /**
         * Parses a name leniently: the [wireName], the [toolName] or the enum
         * name, ignoring case and underscores. Returns `null` if nothing matches.
         */
        public fun matching(name: String): NPCMemoryTool? {
            val key = name.replace("_", "").lowercase()
            return entries.firstOrNull { tool -> listOf(tool.wireName, tool.toolName, tool.name).any { it.replace("_", "").lowercase() == key } }
        }
    }
}

/**
 * How an [NPC] asks the model for its reply.
 *
 * Gemini Nano has no constrained decoding for runtime schemas (and its
 * safety filters treat JSON and plain text alike), so on Android the
 * trade-off is about *valid output*, not guardrails: a structured reply is
 * richer, a plain-text reply cannot be malformed.
 */
@Serializable
public enum class NPCReplyFormat {
    /**
     * The default. First a structured reply (`{emotion, line, player_options,
     * ends_conversation}`) through oam-core's structured output, which coerces
     * and validates the JSON and re-asks once when it is invalid. If the output
     * is still invalid after that repair (the turn fails with `generation_failed`),
     * or the safety filters block the turn or the model refuses, the turn is
     * retried **once** as a plain-text reply tagged with an emotion
     * (`[angry] Get out!`): tools off, results of tools already called passed
     * in as facts, so the retry stays grounded and side-effecting tools do not
     * run twice. A retried turn has no suggested replies. Only if the retry is
     * blocked too does the NPC use a canned fallback line.
     */
    @SerialName("automatic")
    AUTOMATIC,

    /**
     * Structured replies only: emotion, line, suggested player replies and
     * whether the conversation ends. A turn whose output stays unusable goes
     * straight to a fallback line.
     */
    @SerialName("structured")
    STRUCTURED,

    /**
     * Plain text that starts with an emotion tag (`[angry] Get out!`). No
     * suggested replies, and [DialogueTurn.endsConversation] is always false,
     * but the output can never be malformed, it streams straight from the
     * model and turns are fastest.
     */
    @SerialName("text")
    TEXT,
}

/**
 * Behavior settings for an [NPC]. Serializable with defaults (see
 * [GameJson]), so a game can load them from JSON with only the fields it
 * wants to change. JSON keys match open-apple-models' `NPCOptions`.
 *
 * @property toolChoice Tool policy for the first model step of each turn when [groundingTool] is not set.
 *   The default, [ToolChoice.Explicit], makes the model either call a tool or state that it needs none
 *   (on Android [ToolChoice.Auto] behaves the same: every tool decision is an explicit step).
 *   [ToolChoice.Required] always calls a tool first.
 * @property groundingTool A tool the NPC must call first on every turn (for example `check_inventory` for a
 *   shopkeeper, `read_world_state` for a quest giver). Overrides [toolChoice]. Only the tool's arguments are
 *   generated, so it costs one short model step plus the tool. Afterwards the model may still use tools until
 *   [maxToolRounds] is reached; with `maxToolRounds = 1` it replies right after the lookup, which saves a decide
 *   step per turn (about 0.7–1 s on a ~3B model).
 * @property maxToolRounds Maximum tool rounds (decide, then call) in one turn.
 * @property maxToolCalls Maximum tool calls in one turn.
 * @property worldReadable World paths the NPC may read through `read_world_state` (when it has a world);
 *   `""` is everything, an empty list omits the tool.
 * @property worldWritable World paths the NPC may change through `update_world_state`. Empty (the default) omits the tool.
 * @property worldContextPaths World paths summarized into every turn (see [WorldState.summary]). Grounds the NPC in
 *   small, always-relevant facts (time of day, player name, quest stage) without a tool round.
 * @property memoryTools Built-in memory tools to offer (none by default: each tool costs prompt tokens and the model
 *   may spend a tool round on it).
 * @property maxFacts Most facts kept in memory; older facts are dropped first.
 * @property maxRelationshipChange Largest relationship change one `change_relationship` call may make (larger ones are clamped).
 * @property secretsUnlockAtRelationship [Persona.secrets] are left out of the instructions until
 *   [NPCMemory.relationship] reaches this value, then offered as shareable (default 50). `null` always includes them
 *   with a rule to keep them, but small models leak guarded secrets readily, so prefer a threshold.
 * @property replyFormat See [NPCReplyFormat]. Default: [NPCReplyFormat.AUTOMATIC].
 * @property emotions Emotions the model may choose from (all by default; structured replies only).
 * @property playerOptionCount Number of suggested player replies per turn (0–4; structured replies only).
 *   0 removes the field from the reply, which makes turns a little faster.
 * @property canEndConversation Whether the model may end the conversation ([DialogueTurn.endsConversation]).
 * @property extraInstructions Extra lines appended to the persona's instructions.
 * @property compactAfterTurns Once the history holds this many turns, older turns are summarized into
 *   [NPCMemory.summary] in the background. 0 disables compaction (the history is then only trimmed to fit).
 * @property keepRecentTurns Turns kept verbatim when compacting.
 * @property fallbackOnGuardrail When a turn's reply is unusable, return an in-character fallback line
 *   ([DialogueTurn.isFallback]) instead of throwing. Unusable means: the safety filters blocked it
 *   (`guardrail_violation`), the model refused (`refusal`), or its output stayed invalid (`generation_failed`),
 *   in each case after the [NPCReplyFormat.AUTOMATIC] text retry. The failed exchange is not added to the history.
 * @property fallbackLines Fallback lines, used in rotation. Empty uses [DEFAULT_FALLBACK_LINES].
 * @property temperature Sampling temperature for replies (`null` = model default).
 * @property maxResponseTokens Output limit for the reply step (`null` = model default). Too low a limit can cut a
 *   structured reply short.
 * @property barkMaxTokens Output limit for [NPC.bark].
 */
@Serializable
public data class NPCOptions(
    @Serializable(with = ToolChoiceSerializer::class)
    public val toolChoice: ToolChoice = ToolChoice.Explicit,
    public val groundingTool: String? = null,
    public val maxToolRounds: Int = 2,
    public val maxToolCalls: Int = 6,
    public val worldReadable: List<String> = listOf(""),
    public val worldWritable: List<String> = emptyList(),
    public val worldContextPaths: List<String> = emptyList(),
    @Serializable(with = MemoryToolsSerializer::class)
    public val memoryTools: Set<NPCMemoryTool> = emptySet(),
    public val maxFacts: Int = 12,
    public val maxRelationshipChange: Int = 10,
    public val secretsUnlockAtRelationship: Int? = 50,
    public val replyFormat: NPCReplyFormat = NPCReplyFormat.AUTOMATIC,
    public val emotions: List<Emotion> = Emotion.entries,
    public val playerOptionCount: Int = 3,
    public val canEndConversation: Boolean = true,
    public val extraInstructions: String? = null,
    public val compactAfterTurns: Int = 8,
    public val keepRecentTurns: Int = 2,
    public val fallbackOnGuardrail: Boolean = true,
    public val fallbackLines: List<String> = emptyList(),
    public val temperature: Double? = null,
    @SerialName("maximumResponseTokens")
    public val maxResponseTokens: Int? = null,
    @SerialName("barkMaximumTokens")
    public val barkMaxTokens: Int = 48,
) {
    /** [playerOptionCount] limited to 0–4. */
    internal val effectivePlayerOptionCount: Int get() = playerOptionCount.coerceIn(0, MAX_PLAYER_OPTIONS)

    public companion object {
        /** Neutral lines used when [fallbackLines] is empty. */
        public val DEFAULT_FALLBACK_LINES: List<String> = listOf(
            "Let's talk about something else.",
            "I'd rather not speak of that.",
            "Hmm. Ask me something else.",
        )

        /** The most suggested player replies a turn can ask for. */
        public const val MAX_PLAYER_OPTIONS: Int = 4
    }
}

/**
 * Writes memory tools as a list of wire names (`["rememberFact", "changeRelationship"]`).
 * Reads that, `"all"`, `"none"`, a single name, tool names (`remember_fact`) or open-apple-models' bit mask (1, 2, 3).
 */
internal object MemoryToolsSerializer : KSerializer<Set<NPCMemoryTool>> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Set<NPCMemoryTool>) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("memoryTools can only be written as JSON.")
        json.encodeJsonElement(JsonArray(NPCMemoryTool.entries.filter { it in value }.map { JsonPrimitive(it.wireName) }))
    }

    override fun deserialize(decoder: Decoder): Set<NPCMemoryTool> {
        val json = decoder as? JsonDecoder ?: throw SerializationException("memoryTools can only be read from JSON.")
        return parse(json.decodeJsonElement())
    }

    fun parse(element: JsonElement): Set<NPCMemoryTool> {
        if (element is JsonNull) return emptySet()
        element.intValue?.let { mask ->
            return NPCMemoryTool.entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
        }
        val names = element.stringValue?.let { name ->
            when (name.lowercase()) {
                "all" -> return NPCMemoryTool.ALL
                "none", "" -> return emptySet()
                else -> listOf(element)
            }
        } ?: element.arrayValue ?: throw SerializationException(
            "memoryTools must be a list such as [\"rememberFact\", \"changeRelationship\"], \"all\" or \"none\".",
        )
        return names.map { item ->
            val name = item.stringValue ?: throw SerializationException("memoryTools entries must be names; got $item.")
            NPCMemoryTool.matching(name) ?: throw SerializationException(
                "Unknown memory tool '$name'; known: ${NPCMemoryTool.entries.joinToString { "\"${it.wireName}\"" }}.",
            )
        }.toSet()
    }
}
