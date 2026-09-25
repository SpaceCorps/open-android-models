package com.spacecorps.oam.game

import com.spacecorps.oam.AgentConfiguration
import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentErrorCode
import com.spacecorps.oam.AgentTool
import com.spacecorps.oam.JsonSchema
import com.spacecorps.oam.ToolChoice
import com.spacecorps.oam.ToolOutput
import com.spacecorps.oam.ToolPolicy
import com.spacecorps.oam.ToolRecord
import com.spacecorps.oam.TranscriptEntry
import com.spacecorps.oam.Transcript
import com.spacecorps.oam.arrayValue
import com.spacecorps.oam.boolValue
import com.spacecorps.oam.objectValue
import com.spacecorps.oam.stringValue
import com.spacecorps.oam.toJsonString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Prompt, schema and tool construction for [NPC]. Everything here is a
 * pure function of the persona, options and memory, which keeps it easy to
 * test and to tune for a small on-device model.
 */
internal object NPCPrompting {
    /** The player's label in the rendered conversation. */
    const val PLAYER_LABEL: String = "Player"

    /** What the prompt says when the player says nothing. */
    const val SILENT_LINE: String = "(The player says nothing.)"

    /** Codes that mean "the model answered, but the answer cannot be used". */
    val UNUSABLE: Set<AgentErrorCode> = setOf(AgentErrorCode.GUARDRAIL_VIOLATION, AgentErrorCode.REFUSAL, AgentErrorCode.GENERATION_FAILED)

    // MARK: Tools

    fun composeTools(user: List<AgentTool>, world: WorldState?, options: NPCOptions, store: MemoryStore): List<AgentTool> {
        val tools = ArrayList(user)
        if (world != null) tools += world.tools(options.worldReadable, options.worldWritable)
        tools += memoryTools(options, store)
        return tools
    }

    /** @throws AgentError [AgentErrorCode.INVALID_REQUEST] for an unusable configuration. */
    fun validate(options: NPCOptions, toolNames: List<String>) {
        val seen = HashSet<String>()
        for (name in toolNames) {
            if (!seen.add(name)) {
                throw AgentError(
                    AgentErrorCode.INVALID_REQUEST,
                    "Duplicate tool name '$name' (built-in world and memory tools use read_world_state, update_world_state, remember_fact and change_relationship).",
                )
            }
        }
        options.groundingTool?.let { grounding ->
            if (grounding !in seen) {
                throw AgentError(AgentErrorCode.INVALID_REQUEST, "groundingTool '$grounding' is not one of the NPC's tools: ${toolNames.joinToString(", ").ifEmpty { "(none)" }}.")
            }
        }
        (options.toolChoice as? ToolChoice.Tool)?.let { choice ->
            if (choice.name !in seen) throw AgentError(AgentErrorCode.INVALID_REQUEST, "toolChoice names unknown tool '${choice.name}'.")
        }
        if (options.maxToolRounds < 0 || options.maxToolCalls < 0) {
            throw AgentError(AgentErrorCode.INVALID_REQUEST, "maxToolRounds and maxToolCalls must not be negative.")
        }
        if (options.maxResponseTokens != null && options.maxResponseTokens < 1) {
            throw AgentError(AgentErrorCode.INVALID_REQUEST, "maximumResponseTokens must be at least 1.")
        }
        if (options.barkMaxTokens < 1) throw AgentError(AgentErrorCode.INVALID_REQUEST, "barkMaximumTokens must be at least 1.")
        if (options.temperature != null && !(options.temperature >= 0.0)) {
            throw AgentError(AgentErrorCode.INVALID_REQUEST, "temperature must not be negative.")
        }
    }

    fun memoryTools(options: NPCOptions, store: MemoryStore): List<AgentTool> {
        val tools = ArrayList<AgentTool>()
        if (NPCMemoryTool.REMEMBER_FACT in options.memoryTools) {
            tools += AgentTool.local(
                NPCMemoryTool.REMEMBER_FACT.toolName,
                "Remember an important fact about the player or the world for later conversations.",
                JsonSchema.obj("fact" to JsonSchema.string(description = "One short fact, for example 'The player's name is Aria.'")),
            ) { call ->
                val fact = call.string("fact")
                when {
                    fact.isBlank() -> ToolOutput.Error("The fact is empty.")
                    store.stageFact(fact) -> ToolOutput.Text("Remembered.")
                    else -> ToolOutput.Text("You already know that.")
                }
            }
        }
        if (NPCMemoryTool.CHANGE_RELATIONSHIP in options.memoryTools) {
            val limit = options.maxRelationshipChange.coerceAtLeast(1)
            // No minimum/maximum in the schema: without constrained decoding a
            // small model may overshoot ("-50" for a grave insult), and the
            // intent is clear, so the handler clamps instead of re-asking.
            tools += AgentTool.local(
                NPCMemoryTool.CHANGE_RELATIONSHIP.toolName,
                "Change how much you like the player when they clearly please or offend you.",
                JsonSchema.obj(
                    "reason" to JsonSchema.string(description = "Why your feelings changed, in a few words."),
                    "delta" to JsonSchema.integer(description = "From -$limit (the player upset you) to $limit (the player pleased you)."),
                ),
            ) { call ->
                val delta = call.int("delta").coerceIn(-limit, limit)
                val value = store.stageRelationship(delta)
                ToolOutput.Text("You now feel ${NPCMemory.attitude(value)} toward the player ($value).")
            }
        }
        return tools
    }

    // MARK: Instructions

    /**
     * The NPC's system instructions: the persona, memory-tool hints and, for
     * text replies, the emotion-tag rule. Byte-stable for a given persona,
     * options, tool set and secret visibility, so backends can cache it.
     */
    fun instructions(persona: Persona, options: NPCOptions, tools: List<AgentTool>, memory: NPCMemory, textReply: Boolean): String {
        val builtIn = NPCMemoryTool.entries.map { it.toolName }.toSet()
        val factTools = tools.any { it.name !in builtIn }
        val extra = ArrayList<String>()
        TextCleanup.trimmedOrNull(options.extraInstructions)?.let { extra += it }
        if (tools.any { it.name == NPCMemoryTool.REMEMBER_FACT.toolName }) {
            extra += "- When the player tells you something worth remembering, call ${NPCMemoryTool.REMEMBER_FACT.toolName}."
        }
        if (tools.any { it.name == NPCMemoryTool.CHANGE_RELATIONSHIP.toolName }) {
            extra += "- When the player clearly pleases or offends you, call ${NPCMemoryTool.CHANGE_RELATIONSHIP.toolName}."
        }
        if (textReply) extra += "- Begin every spoken reply with your current emotion in square brackets, such as [happy] or [angry]."
        val visibility = options.secretsUnlockAtRelationship?.let { threshold ->
            if (memory.relationship >= threshold) Persona.SecretVisibility.SHAREABLE else Persona.SecretVisibility.HIDDEN
        } ?: Persona.SecretVisibility.GUARDED
        return persona.instructions(extra.takeIf { it.isNotEmpty() }?.joinToString("\n"), factTools, visibility)
    }

    fun memoryNote(memory: NPCMemory, options: NPCOptions): String? =
        memory.note(includeRelationship = NPCMemoryTool.CHANGE_RELATIONSHIP in options.memoryTools)

    /**
     * The per-turn context note (appended to the system instruction): memory,
     * the world summary and the one-turn situation, then, for a text retry,
     * the facts tools already returned.
     *
     * The player's words stay the turn's prompt on their own, so the
     * conversation reads `Player: <line>` and the agent can quote the line
     * cleanly when it asks the model what to do next.
     */
    fun contextNote(memory: NPCMemory, options: NPCOptions, worldSummary: String?, situation: String?, facts: List<ToolRecord> = emptyList()): String? {
        val parts = ArrayList<String>()
        memoryNote(memory, options)?.let { parts += it }
        TextCleanup.trimmedOrNull(worldSummary)?.let { parts += "Game state:\n$it" }
        TextCleanup.trimmedOrNull(situation)?.let { parts += "Situation: $it" }
        factsNote(facts)?.let { parts += it }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** Results of tools that already ran, for the plain-text retry of a turn (so it stays grounded without re-running them). */
    fun factsNote(records: List<ToolRecord>): String? {
        val results = records.filter { !it.output.isError }.take(MAX_RETRY_FACTS)
        if (results.isEmpty()) return null
        return "Facts you just looked up:\n" + results.joinToString("\n") { record ->
            val arguments = if (record.call.arguments.isEmpty()) "" else " " + record.call.arguments.toJsonString()
            "- ${record.call.name}$arguments gave: ${record.output.modelText.trim().take(MAX_FACT_CHARS)}"
        }
    }

    fun summaryInstructions(persona: Persona): String =
        "You keep ${persona.name}'s memory of talking with the player in a video game. " +
            "Merge the new conversation into the summary so far. When ${persona.name}'s turns list fields, only the \"line\" was spoken aloud. " +
            "Keep names, promises, deals, items, prices, facts learned and how ${persona.name} feels about the player. " +
            "Write at most 100 words of plain prose in the third person."

    fun configuration(options: NPCOptions, persona: Persona): AgentConfiguration = try {
        AgentConfiguration(
            toolPolicy = ToolPolicy(options.toolChoice, options.maxToolRounds, options.maxToolCalls),
            temperature = options.temperature,
            maxResponseTokens = options.maxResponseTokens,
            userLabel = PLAYER_LABEL,
            assistantLabel = persona.name.trim(),
            structuredReplyRenderer = ::spokenLine,
        )
    } catch (error: IllegalArgumentException) {
        throw AgentError(AgentErrorCode.INVALID_REQUEST, error.message ?: "Invalid NPC options.", cause = error)
    }

    /** How an earlier structured reply appears in the conversation: just the line that was spoken. */
    fun spokenLine(reply: JsonElement): String = reply.objectValue?.get("line")?.stringValue ?: reply.stringValue ?: ""

    // MARK: Prompt and schema

    /** The turn's prompt: the player's words (framed as `Player: …` by the conversation), or a silent-turn marker. */
    fun prompt(playerLine: String): String = TextCleanup.trimmedOrNull(playerLine) ?: SILENT_LINE

    /** The prompt of a bark: a silent dialogue moment in the given situation. */
    fun barkPrompt(situation: String, worldSummary: String?): String {
        val lines = ArrayList<String>()
        TextCleanup.trimmedOrNull(worldSummary)?.let { lines += "Game state:\n$it" }
        lines += "Situation: ${TextCleanup.trimmedOrNull(situation) ?: "An ordinary moment."}"
        lines += "$PLAYER_LABEL: $SILENT_LINE"
        return lines.joinToString("\n")
    }

    /**
     * The structured reply schema. Property order matters: the model writes
     * the emotion first (setting the tone), then the line, then suggestions.
     * Counts and lengths are described, not enforced: without constrained
     * decoding a small model may give two suggestions instead of three, which
     * is harmless and not worth a repair step.
     */
    fun replySchema(persona: Persona, options: NPCOptions): JsonSchema {
        val emotions = options.emotions.ifEmpty { Emotion.entries }.distinct().map { it.wireName }
        val name = persona.name.trim()
        val sentences = persona.maxSentences.coerceAtLeast(1)
        val properties = LinkedHashMap<String, JsonSchema>()
        properties["emotion"] = JsonSchema.string(
            description = "How $name feels right now, given their personality and what the player just said. Use neutral only when no feeling fits.",
            enum = emotions,
        )
        properties["line"] = JsonSchema.string(
            description = "What $name says out loud, in character. At most $sentences short ${if (sentences == 1) "sentence" else "sentences"}. " +
                "If $name looked something up for this message, name the specific facts found (items, prices, numbers).",
        )
        val count = options.effectivePlayerOptionCount
        if (count > 0) {
            properties["player_options"] = JsonSchema.array(
                JsonSchema.string(),
                description = "$count short, different ${if (count == 1) "reply" else "replies"} the player could say next.",
            )
        }
        if (options.canEndConversation) {
            properties["ends_conversation"] = JsonSchema.boolean(description = "true only if $name says goodbye or refuses to talk any more; otherwise false.")
        }
        return JsonSchema.obj(properties)
    }

    /** A parsed reply. */
    data class Reply(val line: String, val emotion: Emotion, val playerOptions: List<String>, val endsConversation: Boolean)

    private val listMarker = Regex("^\\s*(?:[-*•]|\\d{1,2}[.)])\\s+")

    /** Reads the structured reply defensively: any field may be missing or odd. */
    fun parseReply(value: JsonElement?, persona: Persona, options: NPCOptions): Reply {
        val reply = value?.objectValue
        val emotion = reply?.get("emotion")?.stringValue?.let(Emotion::matching) ?: persona.defaultEmotion
        val line = TextCleanup.spokenLine(reply?.get("line")?.stringValue.orEmpty(), persona.name)
        val suggestions = ArrayList<String>()
        for (item in reply?.get("player_options")?.arrayValue.orEmpty()) {
            val text = item.stringValue ?: continue
            // Strip list markers ("1. ", "- ") and labels the model may add.
            val cleaned = TextCleanup.spokenLine(text.replaceFirst(listMarker, ""), PLAYER_LABEL)
            if (cleaned.isEmpty() || suggestions.any { it.equals(cleaned, ignoreCase = true) }) continue
            suggestions += cleaned
        }
        return Reply(
            line = line,
            emotion = emotion,
            playerOptions = suggestions.take(options.effectivePlayerOptionCount),
            endsConversation = options.canEndConversation && reply?.get("ends_conversation")?.boolValue == true,
        )
    }

    /** A plain-text reply split into its leading `[emotion]` tag and the spoken text. */
    data class TagSplit(val tag: String?, val rest: String, val pending: Boolean)

    /** Splits a plain-text reply; [TagSplit.pending] is true while an opening tag is still streaming. */
    fun splitEmotionTag(text: String): TagSplit {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("[")) return TagSplit(null, trimmed, false)
        val close = trimmed.indexOf(']')
        if (close < 0) {
            // A tag longer than a word or two is not a tag.
            return if (trimmed.length > MAX_TAG_CHARS) TagSplit(null, trimmed, false) else TagSplit(null, "", true)
        }
        return TagSplit(trimmed.substring(1, close), trimmed.substring(close + 1).trimStart(), false)
    }

    /** The partial reply of a streaming text turn, shaped like structured output, or `null` while a tag is incomplete. */
    fun partialTextReply(text: String): JsonObject? {
        val split = splitEmotionTag(text)
        if (split.pending) return null
        val fields = LinkedHashMap<String, JsonElement>()
        split.tag?.let { fields["emotion"] = JsonPrimitive(it) }
        fields["line"] = JsonPrimitive(split.rest)
        return JsonObject(fields)
    }

    fun parseTextReply(text: String, persona: Persona): Reply {
        val split = splitEmotionTag(text)
        return Reply(
            line = TextCleanup.spokenLine(split.rest, persona.name),
            emotion = split.tag?.let(Emotion::matching) ?: persona.defaultEmotion,
            playerOptions = emptyList(),
            endsConversation = false,
        )
    }

    // MARK: History

    fun turnCount(entries: List<TranscriptEntry>): Int = entries.count { it is TranscriptEntry.Prompt }

    /** Drops a trailing turn that has no response yet. */
    fun completeTurns(transcript: Transcript): Transcript {
        val entries = transcript.entries
        val lastPrompt = entries.indexOfLast { it is TranscriptEntry.Prompt }
        if (lastPrompt < 0) return transcript
        val answered = entries.subList(lastPrompt + 1, entries.size).any { it is TranscriptEntry.Response }
        return if (answered) transcript else transcript.copy(entries = entries.subList(0, lastPrompt).toList())
    }

    private const val MAX_RETRY_FACTS = 6
    private const val MAX_FACT_CHARS = 400
    private const val MAX_TAG_CHARS = 24
}
