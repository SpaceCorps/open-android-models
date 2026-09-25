package com.spacecorps.oam

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** What a model step must produce. */
internal sealed interface PromptTask {
    data class Decide(val tools: List<AgentTool>, val allowRespond: Boolean, val repair: Repair? = null) : PromptTask

    data class ToolArguments(val tool: AgentTool, val repair: Repair? = null) : PromptTask

    data object Respond : PromptTask

    data class Structured(val schema: JsonSchema, val repair: Repair? = null) : PromptTask
}

/** Why the previous attempt of a step was rejected. */
internal data class Repair(val problem: String, val previousOutput: String)

/** The parts of a [GenerationRequest] for one step. */
internal data class RenderedPrompt(
    val systemInstruction: String?,
    val promptPrefix: String?,
    val prompt: String,
    val trimmedEntries: Int,
    val estimatedInputTokens: Int,
)

/**
 * Renders the agent's state into Gemini Nano's single-prompt format.
 *
 * Gemini Nano has no roles and no chat session, so the conversation is
 * written as labelled lines (`Player: …`, `Mira: …`, `[check_menu → {…}]`)
 * followed by a short task section for the step. The system instruction
 * (instructions plus context note) is byte-stable across the turn's steps
 * and across turns so backends can cache it; the decide step's tool list is
 * passed as a stable prompt prefix for the same reason. History is trimmed
 * oldest turn first to fit the model's input limit.
 */
internal class PromptRenderer(
    private val instructions: String?,
    private val contextNote: String?,
    private val userLabel: String,
    private val assistantLabel: String,
    private val capabilities: ModelCapabilities,
    private val context: ContextPolicy,
    private val countTokens: suspend (String) -> Int?,
    private val structuredReply: ((JsonElement) -> String)? = null,
) {
    private val tokenCache = HashMap<String, Int>()

    /** The system instruction: instructions and context note. */
    val systemText: String? = composeSystem(instructions, contextNote)

    suspend fun render(
        history: List<TranscriptEntry>,
        prompt: String,
        uses: List<TranscriptEntry.ToolUse>,
        task: PromptTask,
    ): RenderedPrompt {
        val systemField = if (capabilities.systemInstructions) systemText else null
        // Without system instruction support, the instruction leads the (stable) prompt prefix.
        val inlineSystem = if (capabilities.systemInstructions) null else systemText
        val prefix = inlineSystem?.let { it + "\n\n" }
        val lastMessage = prompt.trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() }?.let { if (it.length > LAST_MESSAGE_CHARS) it.take(LAST_MESSAGE_CHARS) + "…" else it }
        val turns = turns(history)

        // A first plain request needs no transcript framing.
        val plain = turns.isEmpty() && uses.isEmpty() && prompt.isNotBlank() &&
            (task is PromptTask.Respond || task is PromptTask.Structured)
        val taskText = taskSection(task, lastMessage, plain, hasToolResults = uses.isNotEmpty())

        fun compose(keptTurns: List<Turn>): String = buildString {
            if (plain) {
                append(prompt.trim())
                if (task !is PromptTask.Respond) append("\n\n").append(taskText)
                return@buildString
            }
            append("Conversation:\n")
            keptTurns.forEach { append(it.text) }
            if (prompt.isNotBlank()) append(userLabel).append(": ").append(prompt.trim()).append('\n')
            uses.forEach { append(toolLine(it)).append('\n') }
            append('\n').append(taskText)
        }

        val fixedSystemTokens = systemField?.let { tokens(it, precise = false) } ?: 0
        val prefixTokens = prefix?.let { tokens(it, precise = false) } ?: 0
        var kept = turns
        var text = compose(kept)
        var estimate = fixedSystemTokens + prefixTokens + estimateTokens(text)
        val budget = capabilities.maxInputTokens - context.reservedTokens
        var trimmed = 0
        if (context.trimsHistory && turns.isNotEmpty() && estimate > budget * PRECISE_THRESHOLD) {
            // Count precisely (per block, cached across steps) only near the limit.
            val system = systemField?.let { tokens(it, precise = true) } ?: 0
            val prefixCount = prefix?.let { tokens(it, precise = true) } ?: 0
            val turnTokens = turns.map { tokens(it.text, precise = true) }
            val rest = tokens(compose(emptyList()), precise = true)
            var total = system + prefixCount + rest + turnTokens.sum()
            val keepAtLeast = minOf(context.minimumRecentTurns, turns.size)
            var start = 0
            while (total > budget && start < turns.size - keepAtLeast) {
                total -= turnTokens[start]
                trimmed += turns[start].entryCount
                start++
            }
            kept = turns.drop(start)
            text = compose(kept)
            estimate = total
        }
        return RenderedPrompt(systemField, prefix, text, trimmed, estimate)
    }

    // MARK: Sections

    private fun toolCatalog(task: PromptTask.Decide): String = buildString {
        append(assistantLabel).append(" can use these tools:\n")
        for (tool in task.tools) {
            append("- ").append(tool.name).append(": ").append(tool.description.trim().ifEmpty { "(no description)" })
            append(" Arguments: ").append(tool.parameters.render()).append('\n')
        }
        if (task.allowRespond) {
            append(assistantLabel).append(" can also ").append(AgentTool.RESPOND_ACTION).append(": reply to ").append(userLabel).append(" without a tool.\n")
        }
    }

    /** A concrete envelope for the format line (small models copy abstract templates literally). */
    private fun example(tool: AgentTool): String {
        val arguments = tool.parameters.propertyNames.joinToString(", ", "{", "}") { "\"$it\": …" }
        return "{\"action\": \"${tool.name}\", \"arguments\": $arguments}"
    }

    private fun taskSection(task: PromptTask, lastMessage: String?, plain: Boolean, hasToolResults: Boolean): String = when (task) {
        is PromptTask.Decide -> buildString {
            append(toolCatalog(task)).append('\n')
            lastMessage?.let { append(userLabel).append("'s last message: \"").append(it).append("\"\n") }
            if (task.allowRespond) {
                append("What does ").append(assistantLabel).append(" do next? Use a tool only if the last message needs its information ")
                append("or asks for what it does. Choose ").append(AgentTool.RESPOND_ACTION)
                append(" for small talk, or if the conversation and the tool results in [brackets] already have what is needed.\n")
            } else {
                append(assistantLabel).append(" must use one of the tools now.\n")
            }
            append("Answer with one JSON object and nothing else, like ").append(example(task.tools.first()))
            if (task.allowRespond) append(" or {\"action\": \"").append(AgentTool.RESPOND_ACTION).append("\", \"arguments\": {}}")
            append('.')
            task.repair?.let { append(repairNote(it)) }
        }
        is PromptTask.ToolArguments -> buildString {
            append(assistantLabel).append(" calls the tool ").append(task.tool.name)
            task.tool.description.trim().takeIf { it.isNotEmpty() }?.let { append(" (").append(it.removeSuffix(".")).append(')') }
            append(".\nWrite its arguments as one JSON object with these keys:\n").append(task.tool.parameters.renderFields())
            append("\nAnswer with the JSON object only.")
            task.repair?.let { append(repairNote(it)) }
        }
        // Never mention the [bracketed] tool lines here: the model then echoes them into the reply.
        PromptTask.Respond -> buildString {
            append("Write ").append(assistantLabel).append("'s reply to ").append(userLabel).append("'s last message")
            if (hasToolResults) append(", using the facts from the tool results above in your own words")
            append(". Answer with only what ").append(assistantLabel).append(" says.")
        }
        is PromptTask.Structured -> buildString {
            val fields = task.schema.renderFields()
            val keyed = fields.startsWith("- ")
            val shape = if (keyed) "one JSON object with these keys:\n" else "JSON of this form:\n"
            when {
                plain -> append("Answer with ").append(shape)
                // Quoting the message and asking for a *new* reply stops small models from copying their previous one.
                lastMessage != null -> append("Write ").append(assistantLabel).append("'s new reply to ").append(userLabel)
                    .append("'s last message (\"").append(lastMessage).append("\") as ").append(shape)
                else -> append("Write ").append(assistantLabel).append("'s next reply as ").append(shape)
            }
            append(fields)
            append(if (keyed) "\nAnswer with the JSON object only." else "\nAnswer with the JSON only.")
            task.repair?.let { append(repairNote(it)) }
        }
    }

    private fun repairNote(repair: Repair): String {
        val previous = repair.previousOutput.trim().replace(Regex("\\s+"), " ").let { if (it.length > REPAIR_ECHO_CHARS) it.take(REPAIR_ECHO_CHARS) + "…" else it }
        return "\nYour previous answer was invalid: ${repair.problem}.\nPrevious answer: $previous\nAnswer again, correctly."
    }

    // MARK: History

    private class Turn(val text: String, val entryCount: Int)

    private fun turns(history: List<TranscriptEntry>): List<Turn> {
        val groups = ArrayList<MutableList<TranscriptEntry>>()
        for (entry in history) {
            if (entry is TranscriptEntry.Prompt || groups.isEmpty()) groups.add(ArrayList())
            groups.last().add(entry)
        }
        return groups.map { group -> Turn(group.joinToString("") { line(it) + "\n" }, group.size) }
    }

    private fun line(entry: TranscriptEntry): String = when (entry) {
        is TranscriptEntry.Prompt -> "$userLabel: ${entry.text.trim()}"
        is TranscriptEntry.Response -> "$assistantLabel: ${replyText(entry)}"
        is TranscriptEntry.ToolUse -> toolLine(entry)
    }

    /**
     * A structured reply is shown as `key: value` prose, not JSON: a small
     * model asked for JSON copies the last JSON it sees in the conversation.
     */
    private fun replyText(entry: TranscriptEntry.Response): String {
        val structured = entry.structured ?: return entry.text.trim()
        structuredReply?.let { return it(structured).trim() }
        return describeStructured(structured)
    }

    private fun toolLine(use: TranscriptEntry.ToolUse): String {
        val arguments = if (use.call.arguments.isEmpty()) "" else " " + use.call.arguments.toJsonString()
        var output = use.output.modelText.trim()
        if (output.length > context.maxToolOutputChars) output = output.take(context.maxToolOutputChars) + "… (truncated)"
        return "[${use.call.name}$arguments → $output]"
    }

    private suspend fun tokens(text: String, precise: Boolean): Int {
        if (!precise) return estimateTokens(text)
        tokenCache[text]?.let { return it }
        val counted = countTokens(text) ?: estimateTokens(text)
        tokenCache[text] = counted
        return counted
    }

    companion object {
        private const val PRECISE_THRESHOLD = 0.6
        private const val REPAIR_ECHO_CHARS = 240
        private const val LAST_MESSAGE_CHARS = 300

        /** `emotion: happy; line: Welcome!` for objects, compact JSON otherwise. */
        fun describeStructured(value: JsonElement): String {
            val obj = value as? JsonObject ?: return value.toJsonString()
            return obj.entries.joinToString("; ") { (key, child) -> "$key: " + (child.stringValue ?: child.toJsonString()) }
        }

        fun composeSystem(instructions: String?, note: String?): String? {
            val parts = listOfNotNull(instructions?.trim()?.takeIf { it.isNotEmpty() }, note?.trim()?.takeIf { it.isNotEmpty() })
            return if (parts.isEmpty()) null else parts.joinToString("\n\n")
        }

        /** Renders entries as labelled plain text (summaries, logs). */
        fun renderEntries(entries: List<TranscriptEntry>, userLabel: String, assistantLabel: String): String =
            entries.joinToString("\n") { entry ->
                when (entry) {
                    is TranscriptEntry.Prompt -> "$userLabel: ${entry.text.trim()}"
                    is TranscriptEntry.Response -> "$assistantLabel: ${entry.structured?.let(::describeStructured) ?: entry.text.trim()}"
                    is TranscriptEntry.ToolUse -> {
                        val arguments = if (entry.call.arguments.isEmpty()) "" else " " + entry.call.arguments.toJsonString()
                        "[${entry.call.name}$arguments → ${entry.output.modelText.trim()}]"
                    }
                }
            }
    }
}
