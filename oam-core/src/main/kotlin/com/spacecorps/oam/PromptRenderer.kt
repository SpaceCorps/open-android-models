package com.spacecorps.oam

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
        val prefix = buildString {
            inlineSystem?.let { append(it).append("\n\n") }
            (task as? PromptTask.Decide)?.let { append(toolCatalog(it)) }
        }.ifEmpty { null }
        val taskText = taskSection(task)
        val turns = turns(history)

        // A first plain request needs no transcript framing.
        val plain = turns.isEmpty() && uses.isEmpty() && prompt.isNotBlank() &&
            (task is PromptTask.Respond || task is PromptTask.Structured)

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
        append("Tools:\n")
        for (tool in task.tools) {
            append("- ").append(tool.name).append(": ").append(tool.description.trim().ifEmpty { "(no description)" })
            append(" Arguments: ").append(tool.parameters.render()).append('\n')
        }
        if (task.allowRespond) {
            append("- ").append(AgentTool.RESPOND_ACTION).append(": Reply to ").append(userLabel)
                .append(" now, without a tool. Arguments: {}\n")
        }
        append('\n')
    }

    private fun taskSection(task: PromptTask): String = when (task) {
        is PromptTask.Decide -> buildString {
            val names = task.tools.map { it.name } + if (task.allowRespond) listOf(AgentTool.RESPOND_ACTION) else emptyList()
            if (task.allowRespond) {
                append("Decide what ").append(assistantLabel).append(" does next. Use a tool to look something up or to act. ")
                append("Choose ").append(AgentTool.RESPOND_ACTION).append(" if the conversation and the [tool results] above already have what is needed.\n")
            } else {
                append(assistantLabel).append(" must use one of the tools now.\n")
            }
            append("Answer with one JSON object only: {\"action\": \"")
            append(names.joinToString(" | ")).append("\", \"arguments\": {…}}")
            task.repair?.let { append(repairNote(it)) }
        }
        is PromptTask.ToolArguments -> buildString {
            append(assistantLabel).append(" calls the tool ").append(task.tool.name)
            task.tool.description.trim().takeIf { it.isNotEmpty() }?.let { append(" (").append(it.removeSuffix(".")).append(')') }
            append(".\nWrite its arguments as one JSON object of this form:\n").append(task.tool.parameters.render())
            append("\nAnswer with the JSON object only.")
            task.repair?.let { append(repairNote(it)) }
        }
        PromptTask.Respond -> "Write $assistantLabel's reply to $userLabel's last message. Answer with the reply text only."
        is PromptTask.Structured -> buildString {
            append("Answer with one JSON object of this form:\n").append(task.schema.render())
            append("\nAnswer with the JSON object only.")
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
        is TranscriptEntry.Response -> "$assistantLabel: ${entry.text.trim()}"
        is TranscriptEntry.ToolUse -> toolLine(entry)
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

        fun composeSystem(instructions: String?, note: String?): String? {
            val parts = listOfNotNull(instructions?.trim()?.takeIf { it.isNotEmpty() }, note?.trim()?.takeIf { it.isNotEmpty() })
            return if (parts.isEmpty()) null else parts.joinToString("\n\n")
        }

        /** Renders entries as labelled plain text (summaries, logs). */
        fun renderEntries(entries: List<TranscriptEntry>, userLabel: String, assistantLabel: String): String =
            entries.joinToString("\n") { entry ->
                when (entry) {
                    is TranscriptEntry.Prompt -> "$userLabel: ${entry.text.trim()}"
                    is TranscriptEntry.Response -> "$assistantLabel: ${entry.text.trim()}"
                    is TranscriptEntry.ToolUse -> {
                        val arguments = if (entry.call.arguments.isEmpty()) "" else " " + entry.call.arguments.toJsonString()
                        "[${entry.call.name}$arguments → ${entry.output.modelText.trim()}]"
                    }
                }
            }
    }
}
