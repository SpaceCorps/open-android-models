package com.spacecorps.oam

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Settings for an [Agent].
 *
 * @property toolPolicy Default tool policy for each turn (overridable per turn).
 * @property context How history is fitted into the input limit.
 * @property temperature Sampling temperature for replies and structured output (`null` = model default).
 * @property topK Top-K sampling for every step (`null` = model default).
 * @property seed Random seed for every step (`null` = model default).
 * @property maxResponseTokens Output limit for replies and structured output (`null` = model default).
 * @property decisionTemperature Temperature for decide and tool-argument steps. Low values follow the JSON format best.
 * @property decisionMaxTokens Output limit for decide and tool-argument steps.
 * @property toolTimeout Time limit for local tools without their own timeout (`null` = none).
 * @property retry Retries for transient model failures, per model step.
 * @property streamsResponses Emit reply text and partial structured output as it is generated.
 *   When false, the reply arrives as one [AgentEvent.Text].
 * @property repairsInvalidOutput Re-ask once, quoting the problem, when a decision, tool arguments or
 *   structured output is invalid.
 * @property userLabel Label of the user's lines in the rendered conversation (for an NPC: `"Player"`).
 * @property assistantLabel Label of the agent's lines (for an NPC: its name).
 * @property structuredReplyRenderer How an earlier structured reply appears in the rendered conversation
 *   (for an NPC: its spoken line). Default: `key: value` pairs. Never JSON, which small models copy.
 */
public data class AgentConfiguration(
    public val toolPolicy: ToolPolicy = ToolPolicy.Default,
    public val context: ContextPolicy = ContextPolicy.Default,
    public val temperature: Double? = null,
    public val topK: Int? = null,
    public val seed: Int? = null,
    public val maxResponseTokens: Int? = null,
    public val decisionTemperature: Double? = 0.0,
    public val decisionMaxTokens: Int = 256,
    public val toolTimeout: Duration? = 60.seconds,
    public val retry: RetryPolicy = RetryPolicy.Default,
    public val streamsResponses: Boolean = true,
    public val repairsInvalidOutput: Boolean = true,
    public val userLabel: String = "User",
    public val assistantLabel: String = "Assistant",
    public val structuredReplyRenderer: ((JsonElement) -> String)? = null,
) {
    init {
        require(decisionMaxTokens >= 16) { "decisionMaxTokens must be at least 16." }
        require(maxResponseTokens == null || maxResponseTokens >= 1) { "maxResponseTokens must be at least 1." }
        require(temperature == null || temperature >= 0) { "temperature must not be negative." }
        require(decisionTemperature == null || decisionTemperature >= 0) { "decisionTemperature must not be negative." }
        require(userLabel.isNotBlank() && assistantLabel.isNotBlank()) { "Labels must not be blank." }
    }
}

/**
 * A conversational agent over a [LanguageModel] with runtime-defined tools,
 * a controlled tool loop, streaming events, external (host-executed) tools
 * and structured output. The Android counterpart of open-apple-models' `Agent`.
 *
 * ```kotlin
 * val agent = Agent(
 *     model = geminiNano,
 *     instructions = "You are Gorm, a grumpy blacksmith. Reply in one or two sentences.",
 *     tools = listOf(inventoryTool),
 * )
 * val reply = agent.respond("Got any iron swords?", ToolPolicy(choice = ToolChoice.Required))
 * ```
 *
 * **Tool calling without native support.** Each turn alternates *decide*
 * steps, in which the model answers with a JSON step envelope
 * (`{"action": "check_inventory", "arguments": {…}}` or
 * `{"action": "respond"}`), and tool executions, until the model chooses
 * `respond` or the [ToolPolicy] budgets run out; a final *respond* step then
 * writes the (streamed) reply, or the JSON for a schema turn. Envelopes are
 * parsed leniently, arguments are coerced and validated against the tool's
 * schema, and invalid output gets one repair attempt.
 *
 * **Turns are serialized**: starting a turn while another runs queues it. A
 * failed or cancelled turn leaves no trace in the history. Transient model
 * errors are retried per step with backoff ([RetryPolicy]), so a tool never
 * runs twice.
 *
 * @param model The language model.
 * @param instructions System instructions (persona, rules). Keep them byte-stable: backends cache them.
 * @param tools Tools available to the model. Gemini Nano does best with three to five.
 * @param configuration Loop, context and sampling settings.
 * @param history A saved conversation to resume. Its entries and context note are restored;
 *   [instructions] and [tools] are taken from the parameters.
 * @param scope Where turns run. Defaults to a private scope on [Dispatchers.Default],
 *   cancelled by [close]. With a caller's scope, cancelling it cancels the turns.
 * @throws AgentError [AgentErrorCode.INVALID_REQUEST] for duplicate tool names.
 */
public class Agent(
    public val model: LanguageModel,
    instructions: String? = null,
    tools: List<AgentTool> = emptyList(),
    configuration: AgentConfiguration = AgentConfiguration(),
    history: Transcript? = null,
    scope: CoroutineScope? = null,
) : AutoCloseable {
    private val lock = Any()
    private val ownsScope = scope == null
    private val scope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tail: Job? = null

    private var currentInstructions: String? = instructions
    private var currentNote: String? = history?.contextNote
    private var currentTools: List<AgentTool> = validated(tools)
    private var currentConfiguration: AgentConfiguration = configuration
    private val entries = ArrayList<TranscriptEntry>(history?.entries.orEmpty())
    private var inProgress: MutableList<TranscriptEntry>? = null
    private var usage = TokenUsage()

    // MARK: Turns

    /** Starts a turn and returns its event stream. */
    public fun run(prompt: String, policy: ToolPolicy? = null): AgentRun = start(prompt, null, policy)

    /**
     * Starts a turn whose final output must match [schema]. Tools may be
     * called first; the answer is then generated as JSON, coerced, validated
     * (with one repair attempt) and ordered by the schema.
     */
    public fun run(prompt: String, schema: JsonSchema, policy: ToolPolicy? = null): AgentRun {
        try {
            schema.check()
        } catch (error: InvalidSchemaException) {
            return failed(AgentError.from(error), policy)
        }
        return start(prompt, schema, policy)
    }

    /**
     * Runs a turn to completion.
     *
     * @param externalTools Runs external tool calls; see [AgentRun.response].
     * @throws AgentError if the turn fails.
     */
    public suspend fun respond(
        prompt: String,
        policy: ToolPolicy? = null,
        externalTools: (suspend (ToolCall) -> ToolOutput)? = null,
    ): AgentResponse = run(prompt, policy).response(externalTools)

    /**
     * Runs a structured turn to completion; the result is in [AgentResponse.structured].
     *
     * @throws AgentError if the turn fails, including output that stayed invalid after repair.
     */
    public suspend fun respond(
        prompt: String,
        schema: JsonSchema,
        policy: ToolPolicy? = null,
        externalTools: (suspend (ToolCall) -> ToolOutput)? = null,
    ): AgentResponse = run(prompt, schema, policy).response(externalTools)

    // MARK: State

    /**
     * The conversation, including instructions, context note and tool
     * definitions. Serializable: save it and pass it back as `history`.
     * During a turn it includes the turn's progress so far.
     */
    public val transcript: Transcript
        get() = synchronized(lock) {
            Transcript(currentInstructions, currentNote, currentTools.map { it.definition }, entries + inProgress.orEmpty())
        }

    /** Completed history entries (without the running turn). */
    public val history: List<TranscriptEntry> get() = synchronized(lock) { entries.toList() }

    /** System instructions. Changes apply from the next turn. */
    public var instructions: String?
        get() = synchronized(lock) { currentInstructions }
        set(value) = synchronized(lock) { currentInstructions = value }

    /**
     * Extra context appended to the instructions, such as a summary written by
     * [compactHistory] or facts a game wants the model to keep in mind.
     * Changes apply from the next turn.
     */
    public var contextNote: String?
        get() = synchronized(lock) { currentNote }
        set(value) = synchronized(lock) { currentNote = value }

    /** The agent's tools. Use [ToolPolicy.enabledTools] to narrow them per turn. */
    public val tools: List<AgentTool> get() = synchronized(lock) { currentTools }

    /**
     * Replaces the tool set (applies from the next turn).
     *
     * @throws AgentError [AgentErrorCode.INVALID_REQUEST] for duplicate names.
     */
    public fun setTools(tools: List<AgentTool>) {
        val checked = validated(tools)
        synchronized(lock) { currentTools = checked }
    }

    /** Settings. Changes apply from the next turn. */
    public var configuration: AgentConfiguration
        get() = synchronized(lock) { currentConfiguration }
        set(value) = synchronized(lock) { currentConfiguration = value }

    /** Tokens used by all completed turns (estimated where the model does not report usage). */
    public val totalUsage: TokenUsage get() = synchronized(lock) { usage }

    /** True while a turn runs. */
    public val isResponding: Boolean get() = synchronized(lock) { inProgress != null }

    /** Replaces the history (for example after editing it). Applies after any queued turns. */
    public suspend fun replaceHistory(entries: List<TranscriptEntry>) {
        enqueue {
            synchronized(lock) {
                this.entries.clear()
                this.entries.addAll(entries)
            }
        }.join()
    }

    /** Clears the history and [contextNote], keeping instructions and tools. Applies after any queued turns. */
    public suspend fun reset() {
        enqueue {
            synchronized(lock) {
                entries.clear()
                currentNote = null
            }
        }.join()
    }

    /**
     * Summarizes all but the most recent turns into [contextNote] (merging an
     * existing note) and drops them from the history, so long conversations
     * fit the input limit while keeping their gist. Runs after any queued
     * turns, as one extra model call.
     *
     * @param keepRecentTurns Turns kept verbatim.
     * @param summaryInstructions System instruction for the summarizer.
     * @return The new summary, or `null` if there was nothing to compact.
     * @throws AgentError if the model call fails (nothing changes then).
     */
    public suspend fun compactHistory(
        keepRecentTurns: Int = 2,
        summaryInstructions: String? = null,
        userLabel: String? = null,
        assistantLabel: String? = null,
    ): String? {
        val result = CompletableDeferred<String?>()
        val job = enqueue {
            try {
                result.complete(compact(keepRecentTurns, summaryInstructions, userLabel, assistantLabel))
            } catch (error: CancellationException) {
                result.completeExceptionally(AgentError(AgentErrorCode.CANCELLED, "The compaction was cancelled.", cause = error))
                throw error
            } catch (error: Throwable) {
                result.completeExceptionally(AgentError.from(error))
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) result.completeExceptionally(AgentError(AgentErrorCode.CANCELLED, "The compaction was cancelled.", cause = cause))
        }
        return try {
            result.await()
        } catch (error: CancellationException) {
            job.cancel()
            throw error
        }
    }

    private suspend fun compact(keep: Int, summaryInstructions: String?, userLabel: String?, assistantLabel: String?): String? {
        val (snapshot, previous, config) = synchronized(lock) { Triple(entries.toList(), currentNote, currentConfiguration) }
        val starts = snapshot.indices.filter { snapshot[it] is TranscriptEntry.Prompt }
        if (starts.size <= keep || snapshot.isEmpty()) return null
        val cut = if (keep <= 0) snapshot.size else starts[starts.size - keep]
        if (cut == 0) return null
        val older = renderEntries(snapshot.subList(0, cut), userLabel ?: config.userLabel, assistantLabel ?: config.assistantLabel)
        val prompt = buildString {
            previous?.takeIf { it.isNotBlank() }?.let { append("Summary so far:\n").append(it).append("\n\n") }
            append("Conversation to fold into the summary:\n").append(older)
        }
        val request = GenerationRequest(
            prompt = if (model.capabilities.systemInstructions) prompt else (summaryInstructions ?: SUMMARY_INSTRUCTIONS) + "\n\n" + prompt,
            systemInstruction = if (model.capabilities.systemInstructions) summaryInstructions ?: SUMMARY_INSTRUCTIONS else null,
            temperature = config.decisionTemperature,
            maxOutputTokens = SUMMARY_MAX_TOKENS,
            kind = GenerationKind.SUMMARY,
        )
        var summary = ""
        model.generate(request).collect { summary = it.text }
        summary = summary.trim()
        if (summary.isEmpty()) throw AgentError(AgentErrorCode.GENERATION_FAILED, "The summary was empty.")
        synchronized(lock) {
            // Only drop what was summarized; entries added meanwhile cannot exist (the queue is serial).
            repeat(cut) { entries.removeAt(0) }
            currentNote = summary
        }
        return summary
    }

    /**
     * Waits until every queued or running turn has finished, including a
     * cancelled turn that is still rolling back.
     */
    public suspend fun waitUntilIdle() {
        enqueue {}.join()
    }

    /** Loads model resources ahead of the first turn (see [LanguageModel.prewarm]). */
    public suspend fun prewarm() {
        val system = synchronized(lock) { PromptRenderer.composeSystem(currentInstructions, currentNote) }
        model.prewarm(systemInstruction = system)
    }

    /** Cancels running and queued turns. If the agent created its own scope, it is shut down. */
    override fun close() {
        if (ownsScope) {
            scope.cancel()
        } else {
            synchronized(lock) { tail }?.cancel()
        }
    }

    // MARK: Internals

    private fun start(prompt: String, schema: JsonSchema?, policy: ToolPolicy?): AgentRun {
        val run = AgentRun(policy ?: configuration.toolPolicy)
        val job = enqueue { perform(run, prompt, schema) }
        job.invokeOnCompletion { cause ->
            // A turn cancelled before it could start (scope cancelled, queue torn down).
            run.finish(Result.failure(AgentError(AgentErrorCode.CANCELLED, "The turn was cancelled.", cause = cause)))
        }
        run.attach(job)
        return run
    }

    private fun failed(error: AgentError, policy: ToolPolicy?): AgentRun =
        AgentRun(policy ?: configuration.toolPolicy).also { it.finish(Result.failure(error)) }

    /** Runs [work] after everything queued before it, in arrival order. */
    private fun enqueue(work: suspend () -> Unit): Job = synchronized(lock) {
        val previous = tail
        val job = scope.launch(start = CoroutineStart.LAZY) {
            previous?.join()
            work()
        }
        tail = job
        job.start()
        job
    }

    private suspend fun perform(run: AgentRun, prompt: String, schema: JsonSchema?) {
        if (!run.markStarted()) return
        val setup = synchronized(lock) {
            inProgress = mutableListOf(TranscriptEntry.Prompt(prompt))
            TurnSetup(currentInstructions, currentNote, currentTools, currentConfiguration, entries.toList())
        }
        try {
            val engine = TurnEngine(model, run, setup, prompt, schema) { entry -> synchronized(lock) { inProgress?.add(entry) } }
            val (response, newEntries) = engine.run()
            synchronized(lock) {
                entries += TranscriptEntry.Prompt(prompt)
                entries += newEntries
                usage += response.usage
                inProgress = null
            }
            run.finish(Result.success(response))
        } catch (error: Throwable) {
            synchronized(lock) { inProgress = null }
            val failure = if (error is CancellationException) {
                AgentError(AgentErrorCode.CANCELLED, "The turn was cancelled.", cause = error)
            } else {
                AgentError.from(error)
            }
            run.finish(Result.failure(failure))
            if (error is CancellationException) throw error
        }
    }

    private fun validated(tools: List<AgentTool>): List<AgentTool> {
        val seen = HashSet<String>()
        for (tool in tools) {
            if (!seen.add(tool.name)) throw AgentError(AgentErrorCode.INVALID_REQUEST, "Duplicate tool name '${tool.name}'.")
        }
        return tools.toList()
    }

    public companion object {
        /** Default system instruction for [compactHistory]. */
        public const val SUMMARY_INSTRUCTIONS: String =
            "You maintain a running summary of a conversation for a character who must remember it. " +
                "Merge the new conversation into the existing summary. Keep names, promises, facts learned, " +
                "items exchanged, decisions and the relationship's tone. Write at most 120 words in plain prose."

        private const val SUMMARY_MAX_TOKENS = 400

        /** Renders entries as labelled plain text (for summaries and logs). */
        public fun render(entries: List<TranscriptEntry>, userLabel: String = "User", assistantLabel: String = "Assistant"): String =
            renderEntries(entries, userLabel, assistantLabel)

        private fun renderEntries(entries: List<TranscriptEntry>, userLabel: String, assistantLabel: String): String =
            PromptRenderer.renderEntries(entries, userLabel, assistantLabel)
    }
}
