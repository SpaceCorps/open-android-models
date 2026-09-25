package com.spacecorps.oam.game

import com.spacecorps.oam.Agent
import com.spacecorps.oam.AgentConfiguration
import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentErrorCode
import com.spacecorps.oam.AgentEvent
import com.spacecorps.oam.AgentResponse
import com.spacecorps.oam.AgentRun
import com.spacecorps.oam.AgentTool
import com.spacecorps.oam.JsonSchema
import com.spacecorps.oam.LanguageModel
import com.spacecorps.oam.ToolCall
import com.spacecorps.oam.ToolChoice
import com.spacecorps.oam.ToolOutput
import com.spacecorps.oam.ToolPolicy
import com.spacecorps.oam.ToolRecord
import com.spacecorps.oam.Transcript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.cancellation.CancellationException

/**
 * A conversational non-player character backed by an on-device model
 * (Gemini Nano through oam-mlkit's `GeminiNanoModel`, or any [LanguageModel]).
 *
 * Each [talk] is one turn: the model may call tools first (game tools,
 * world state, memory, or game-executed external tools), then produces an
 * emotion, a spoken line, suggested player replies and whether the
 * conversation ends.
 *
 * ```kotlin
 * val gorm = NPC(
 *     persona = Persona(name = "Gorm", role = "the village blacksmith", personality = "Gruff but fair."),
 *     model = geminiNano,
 *     tools = listOf(inventoryTool),
 *     world = world,
 *     options = NPCOptions(groundingTool = "check_inventory"),
 * )
 * val turn = gorm.talk("Got any iron swords?")
 * println("${turn.emotion} ${turn.line} ${turn.playerOptions}")
 * ```
 *
 * An NPC keeps its conversation history and [NPCMemory]; older turns are
 * summarized in the background ([NPCOptions.compactAfterTurns]). Its turns
 * and bookkeeping run one at a time in order; different NPCs talk
 * concurrently. Save and restore with [saveState] and [restore].
 *
 * **How a turn is prompted on Gemini Nano.** The model has no roles, so the
 * conversation is rendered as `Player: …` / `Gorm: …` lines. The persona,
 * memory, [NPCOptions.worldContextPaths] summary and the turn's `context`
 * (as `Situation: …`) go in the system instruction; the prompt holds only
 * the player's words, so the agent can quote them cleanly when it asks the
 * model which tool to use.
 *
 * @param persona Who the NPC is.
 * @param model The language model.
 * @param tools Game tools (local or external), for example an inventory lookup.
 *   Keep the total, including world and memory tools, to about three to five.
 * @param world Shared game state. Adds `read_world_state` (and `update_world_state` when
 *   [NPCOptions.worldWritable] is set).
 * @param options Grounding, reply format, memory, compaction and fallbacks.
 * @param memory Initial memory.
 * @param history A prior conversation (from [saveState]).
 * @param scope Where the NPC's work runs. Defaults to a private scope on [Dispatchers.Default];
 *   with a caller's scope, cancelling it cancels the NPC's work. [close] cancels it either way.
 * @throws AgentError [AgentErrorCode.INVALID_REQUEST] for an empty name, duplicate tool names,
 *   an unknown [NPCOptions.groundingTool] or invalid options.
 */
public class NPC(
    persona: Persona,
    public val model: LanguageModel,
    tools: List<AgentTool> = emptyList(),
    public val world: WorldState? = null,
    options: NPCOptions = NPCOptions(),
    memory: NPCMemory = NPCMemory(),
    history: Transcript? = null,
    scope: CoroutineScope? = null,
) : AutoCloseable {
    /** The prepared model request(s) of one turn. */
    private data class Attempt(
        val prompt: String,
        val schema: JsonSchema?,
        val policy: ToolPolicy,
        val instructions: String,
        val note: String?,
        val persona: Persona,
        val options: NPCOptions,
        val worldSummary: String?,
        val situation: String?,
    )

    private val lock = Any()
    private val workScope: CoroutineScope = run {
        val context = scope?.coroutineContext ?: Dispatchers.Default
        CoroutineScope(context + SupervisorJob(context[Job]))
    }
    private val queue = SerialQueue(workScope)
    private val memoryStore = MemoryStore(memory)
    private val agent: Agent

    private var currentPersona: Persona = persona
    private var currentOptions: NPCOptions = options
    private var userTools: List<AgentTool> = tools.toList()
    private var toolsNeedRebuild = false
    private var fallbackCount = 0

    init {
        requireName(persona)
        val composed = NPCPrompting.composeTools(tools, world, options, memoryStore)
        NPCPrompting.validate(options, composed.map { it.name })
        agent = Agent(
            model = model,
            instructions = NPCPrompting.instructions(persona, options, composed, memory, textReply = options.replyFormat == NPCReplyFormat.TEXT),
            tools = composed,
            configuration = NPCPrompting.configuration(options, persona),
            history = history,
            scope = workScope,
        )
        agent.contextNote = NPCPrompting.memoryNote(memory, options)
    }

    // MARK: Talking

    /**
     * Runs one conversation turn and returns the NPC's reply.
     *
     * @param playerLine What the player says (empty means the player says nothing).
     * @param context What is happening right now ("The player just paid 45 gold."). Shown to the model for this turn only.
     * @param toolChoice Overrides the grounding policy for this turn (for example [ToolChoice.None] for a goodbye).
     * @param externalTools Runs external tool calls (see [AgentTool.external]).
     * @return The reply. When the reply is unusable and [NPCOptions.fallbackOnGuardrail] is on, a fallback
     *   reply with [DialogueTurn.isFallback] set.
     * @throws AgentError model unavailable, cancelled, context overflow, a tool the NPC lacks, …
     */
    public suspend fun talk(
        playerLine: String,
        context: String? = null,
        toolChoice: ToolChoice? = null,
        externalTools: (suspend (ToolCall) -> ToolOutput)? = null,
    ): DialogueTurn = talkStream(playerLine, context, toolChoice).turn(externalTools)

    /**
     * Starts a conversation turn and streams its events: the emotion, line
     * text as it is generated (for a typewriter effect), tool activity, and
     * finally the complete [DialogueTurn]. The turn is queued behind the
     * NPC's earlier work and runs even if nobody collects the events.
     */
    public fun talkStream(playerLine: String, context: String? = null, toolChoice: ToolChoice? = null): DialogueStream {
        val stream = DialogueStream()
        val job = queue.enqueue { perform(playerLine, context, toolChoice, stream) }
        job.invokeOnCompletion { cause ->
            // Never started, or stopped by close(): end the stream (a no-op once it has finished).
            if (cause != null) stream.fail(AgentError(AgentErrorCode.CANCELLED, "The turn was cancelled.", cause = cause))
        }
        return stream
    }

    /**
     * A short ambient line for the current situation ("Sun's up, lad. Time to
     * sharpen these blades."). Uses a separate one-off request: no history,
     * memory, secrets or tools, one sentence and [NPCOptions.barkMaxTokens],
     * so it is fast and can run while a conversation turn is in progress.
     *
     * @param situation What is happening, for example "A customer walks past the forge in the rain."
     * @throws AgentError including [AgentErrorCode.GUARDRAIL_VIOLATION]: barks are flavor, so games
     *   typically skip the bark on error.
     */
    public suspend fun bark(situation: String): String {
        val (persona, options) = synchronized(lock) { currentPersona to currentOptions }
        val instructions = persona.copy(maxSentences = 1)
            .instructions(options.extraInstructions, usesTools = false, secretVisibility = Persona.SecretVisibility.HIDDEN)
        // Framed as a silent dialogue moment, like a turn, rather than as a
        // command ("Say one line…"), which trips safety filters more often.
        val prompt = NPCPrompting.barkPrompt(situation, world?.summary(options.worldContextPaths))
        val configuration = AgentConfiguration(
            temperature = options.temperature,
            maxResponseTokens = options.barkMaxTokens,
            userLabel = NPCPrompting.PLAYER_LABEL,
            assistantLabel = persona.name.trim(),
            streamsResponses = false,
        )
        val response = coroutineScope {
            Agent(model, instructions, configuration = configuration, scope = this).use { oneOff ->
                oneOff.respond(prompt, ToolPolicy(choice = ToolChoice.None))
            }
        }
        val line = TextCleanup.singleLine(response.text, persona.name)
        if (line.isEmpty()) throw AgentError(AgentErrorCode.GENERATION_FAILED, "The model produced an empty bark.")
        return line
    }

    // MARK: State

    /**
     * The NPC's character sheet. Changes apply from the next turn.
     *
     * @throws AgentError [AgentErrorCode.INVALID_REQUEST] when set to a persona without a name.
     */
    public var persona: Persona
        get() = synchronized(lock) { currentPersona }
        set(value) {
            requireName(value)
            synchronized(lock) { currentPersona = value }
        }

    /** Current options; change them with [setOptions]. */
    public val options: NPCOptions get() = synchronized(lock) { currentOptions }

    /**
     * Replaces the options (applies from the next turn).
     *
     * @throws AgentError [AgentErrorCode.INVALID_REQUEST] if [NPCOptions.groundingTool] names an unknown tool
     *   or the options are invalid; nothing changes then.
     */
    public fun setOptions(options: NPCOptions) {
        val (tools, persona) = synchronized(lock) { userTools to currentPersona }
        val composed = NPCPrompting.composeTools(tools, world, options, memoryStore)
        NPCPrompting.validate(options, composed.map { it.name })
        NPCPrompting.configuration(options, persona)
        synchronized(lock) {
            currentOptions = options
            toolsNeedRebuild = true
        }
    }

    /** The game tools passed at creation or with [setTools] (without the built-in world and memory tools). */
    public val tools: List<AgentTool> get() = synchronized(lock) { userTools }

    /**
     * Replaces the game tools (applies from the next turn).
     *
     * @throws AgentError [AgentErrorCode.INVALID_REQUEST] for duplicate names or when the grounding tool
     *   would no longer exist; nothing changes then.
     */
    public fun setTools(tools: List<AgentTool>) {
        val options = synchronized(lock) { currentOptions }
        val composed = NPCPrompting.composeTools(tools, world, options, memoryStore)
        NPCPrompting.validate(options, composed.map { it.name })
        synchronized(lock) {
            userTools = tools.toList()
            toolsNeedRebuild = true
        }
    }

    /** What the NPC remembers. Setting it applies from the next turn. */
    public var memory: NPCMemory
        get() = memoryStore.memory
        set(value) {
            memoryStore.memory = value
        }

    /**
     * Atomically replaces the memory with [transform] of it (applies from the next turn).
     *
     * ```kotlin
     * npc.updateMemory { it.remembering("Aria returned the lost ring.").adjustingRelationship(20) }
     * ```
     *
     * @return The new memory.
     */
    public fun updateMemory(transform: (NPCMemory) -> NPCMemory): NPCMemory = memoryStore.update(transform)

    /** The conversation, including instructions, the context note and tool calls. */
    public val transcript: Transcript get() = agent.transcript

    /** Conversation turns currently kept verbatim (older ones are summarized). */
    public val turnCount: Int get() = NPCPrompting.turnCount(agent.history)

    /** True while a turn runs. */
    public val isTalking: Boolean get() = agent.isResponding

    /**
     * A snapshot for saving. A turn in progress is left out. Background
     * compaction may be finishing right after a turn; use [settledState]
     * when the save must reflect it.
     */
    public fun saveState(): NPCSaveState =
        NPCSaveState(persona = persona, memory = memory, transcript = NPCPrompting.completeTurns(agent.transcript))

    /** Waits for queued turns and background compaction, then returns [saveState]. Prefer this for save files. */
    public suspend fun settledState(): NPCSaveState = queue.perform { saveState() }

    /** Waits until queued turns and background work (such as compaction) have finished. */
    public suspend fun waitUntilIdle() {
        queue.waitUntilIdle()
    }

    /**
     * Summarizes all but the most recent turns into [NPCMemory.summary] now
     * (normally automatic; see [NPCOptions.compactAfterTurns]).
     *
     * @return The new summary, or `null` if there was too little history.
     * @throws AgentError if the model call fails (nothing changes then).
     */
    public suspend fun compact(): String? {
        val keep = options.keepRecentTurns.coerceAtLeast(0)
        return queue.perform { runCompaction(keep) }.getOrThrow()
    }

    /**
     * Clears the conversation history, after any turn in progress.
     *
     * @param clearingMemory Also forget facts, relationship and summary.
     */
    public suspend fun resetConversation(clearingMemory: Boolean = false) {
        queue.perform {
            agent.reset()
            if (clearingMemory) memoryStore.memory = NPCMemory()
        }
    }

    /** Loads model resources ahead of the first turn to cut its latency (for example when the player approaches). */
    public suspend fun prewarm() {
        agent.prewarm()
    }

    /** Cancels running and queued turns and background work. The NPC cannot talk afterwards. */
    override fun close() {
        workScope.cancel()
        agent.close()
    }

    // MARK: Turn execution

    private suspend fun perform(playerLine: String, context: String?, toolChoice: ToolChoice?, stream: DialogueStream) {
        if (stream.isCancelled) {
            stream.fail(AgentError(AgentErrorCode.CANCELLED, "The turn was cancelled before it started."))
            return
        }
        var attempt = try {
            prepareTurn(playerLine, context, toolChoice)
        } catch (error: AgentError) {
            stream.fail(error)
            return
        }
        memoryStore.discardPending()
        val tracker = LineTracker(attempt.persona.name)
        val records = ArrayList<ToolRecord>()
        var retriedAsText = false
        while (true) {
            agent.instructions = attempt.instructions
            agent.contextNote = attempt.note
            val schema = attempt.schema
            val run = if (schema != null) agent.run(attempt.prompt, schema, attempt.policy) else agent.run(attempt.prompt, attempt.policy)
            if (!stream.attach(run)) {
                try {
                    run.response()
                } catch (_: AgentError) {
                    // Cancelled as expected.
                }
                stream.fail(AgentError(AgentErrorCode.CANCELLED, "The turn was cancelled."))
                return
            }
            val error = try {
                val response = collect(run, stream, tracker, records)
                complete(response, attempt, records, tracker, stream)
                return
            } catch (error: CancellationException) {
                memoryStore.discardPending()
                stream.fail(AgentError(AgentErrorCode.CANCELLED, "The turn was cancelled.", cause = error))
                throw error
            } catch (error: Throwable) {
                AgentError.from(error)
            }
            if (stream.isCancelled) {
                memoryStore.discardPending()
                stream.fail(if (error.code == AgentErrorCode.CANCELLED) error else AgentError(AgentErrorCode.CANCELLED, "The turn was cancelled.", cause = error))
                return
            }
            val unusable = error.code in NPCPrompting.UNUSABLE
            if (unusable && attempt.options.replyFormat == NPCReplyFormat.AUTOMATIC && schema != null && !retriedAsText) {
                // The structured reply stayed invalid, was blocked or refused: retry once as a
                // tagged plain-text line, tools off. Memory changes staged by tools that already
                // ran stay staged and are committed if the retry succeeds.
                retriedAsText = true
                attempt = textRetry(attempt, records)
                continue
            }
            memoryStore.discardPending()
            if (!(unusable && attempt.options.fallbackOnGuardrail)) {
                stream.fail(error)
                return
            }
            val turn = DialogueTurn(
                line = nextFallbackLine(attempt.options),
                emotion = attempt.persona.defaultEmotion,
                toolCalls = records.toList(),
                relationship = memoryStore.memory.relationship,
                isFallback = true,
            )
            tracker.finish(turn.line, turn.emotion, stream::emit)
            stream.finish(turn)
            return
        }
    }

    /** Forwards one agent turn's events to the stream and returns its response. */
    private suspend fun collect(run: AgentRun, stream: DialogueStream, tracker: LineTracker, records: MutableList<ToolRecord>): AgentResponse {
        var response: AgentResponse? = null
        run.events.collect { event ->
            when (event) {
                is AgentEvent.Partial -> tracker.consume(event.value, stream::emit)
                is AgentEvent.Text -> NPCPrompting.partialTextReply(event.text)?.let { tracker.consume(it, stream::emit) }
                is AgentEvent.ToolCallStarted -> stream.emit(DialogueEvent.ToolCall(event.call))
                is AgentEvent.ToolCallRequested -> stream.emit(DialogueEvent.ExternalToolCall(event.call))
                is AgentEvent.ToolCallCompleted -> {
                    records += event.record
                    stream.emit(DialogueEvent.ToolResult(event.record))
                }
                is AgentEvent.Completed -> response = event.response
                is AgentEvent.ModelStep -> Unit
            }
        }
        return response ?: throw AgentError(AgentErrorCode.GENERATION_FAILED, "The turn ended without a reply.")
    }

    /** Commits memory, builds the turn and hands it over. */
    private fun complete(response: AgentResponse, attempt: Attempt, records: List<ToolRecord>, tracker: LineTracker, stream: DialogueStream) {
        val memory = memoryStore.commit(attempt.options.maxFacts)
        val reply = if (attempt.schema == null) {
            NPCPrompting.parseTextReply(response.text, attempt.persona)
        } else {
            NPCPrompting.parseReply(response.structured, attempt.persona, attempt.options)
        }
        var turn = DialogueTurn(
            line = reply.line,
            emotion = reply.emotion,
            playerOptions = reply.playerOptions,
            endsConversation = reply.endsConversation,
            toolCalls = records.toList(),
            relationship = memory.relationship,
            isFallback = false,
            usage = response.usage,
        )
        if (turn.line.isEmpty()) turn = turn.copy(line = nextFallbackLine(attempt.options), isFallback = true)
        // Queue compaction before handing the turn over, so anything the caller
        // queues next (a turn, a save) runs after it.
        scheduleCompactionIfNeeded(attempt.options)
        tracker.finish(turn.line, turn.emotion, stream::emit)
        stream.finish(turn)
    }

    /** Brings the agent's tools and settings up to date and prepares the turn's first request. */
    private fun prepareTurn(playerLine: String, context: String?, toolChoice: ToolChoice?): Attempt {
        val (persona, options, tools, rebuild) = synchronized(lock) {
            val snapshot = TurnState(currentPersona, currentOptions, userTools, toolsNeedRebuild)
            toolsNeedRebuild = false
            snapshot
        }
        if (rebuild) agent.setTools(NPCPrompting.composeTools(tools, world, options, memoryStore))
        agent.configuration = NPCPrompting.configuration(options, persona)
        val available = agent.tools
        val memory = memoryStore.memory
        var choice = toolChoice ?: options.groundingTool?.let { ToolChoice.Tool(it) } ?: options.toolChoice
        if (available.isEmpty()) choice = ToolChoice.None
        if (choice is ToolChoice.Tool && available.none { it.name == choice.name }) {
            throw AgentError(AgentErrorCode.INVALID_REQUEST, "Tool '${choice.name}' is not available to ${persona.name}.")
        }
        val textReply = options.replyFormat == NPCReplyFormat.TEXT
        val worldSummary = world?.summary(options.worldContextPaths)
        return Attempt(
            prompt = NPCPrompting.prompt(playerLine),
            schema = if (textReply) null else NPCPrompting.replySchema(persona, options),
            policy = ToolPolicy(choice, options.maxToolRounds, options.maxToolCalls),
            instructions = NPCPrompting.instructions(persona, options, available, memory, textReply),
            note = NPCPrompting.contextNote(memory, options, worldSummary, context),
            persona = persona,
            options = options,
            worldSummary = worldSummary,
            situation = context,
        )
    }

    private data class TurnState(val persona: Persona, val options: NPCOptions, val tools: List<AgentTool>, val rebuild: Boolean)

    /** The plain-text retry of a turn: tagged text reply, tools off, results of tools already called as facts. */
    private fun textRetry(attempt: Attempt, records: List<ToolRecord>): Attempt {
        val memory = memoryStore.memory
        return attempt.copy(
            schema = null,
            policy = ToolPolicy(choice = ToolChoice.None),
            instructions = NPCPrompting.instructions(attempt.persona, attempt.options, agent.tools, memory, textReply = true),
            note = NPCPrompting.contextNote(memory, attempt.options, attempt.worldSummary, attempt.situation, records),
        )
    }

    private fun nextFallbackLine(options: NPCOptions): String {
        val pool = options.fallbackLines.mapNotNull(TextCleanup::trimmedOrNull).ifEmpty { NPCOptions.DEFAULT_FALLBACK_LINES }
        val index = synchronized(lock) { fallbackCount++ }
        return pool[index % pool.size]
    }

    // MARK: Compaction

    private fun scheduleCompactionIfNeeded(options: NPCOptions) {
        if (options.compactAfterTurns <= 0) return
        val keep = options.keepRecentTurns.coerceAtLeast(0)
        if (turnCount < maxOf(options.compactAfterTurns, keep + 1)) return
        queue.enqueue { runCompaction(keep) }
    }

    private suspend fun runCompaction(keep: Int): Result<String?> {
        val (persona, options) = synchronized(lock) { currentPersona to currentOptions }
        // Seed the summarizer with the previous summary only (not the whole
        // memory block), then restore the memory note.
        agent.contextNote = memoryStore.memory.summary
        try {
            val summary = agent.compactHistory(
                keepRecentTurns = keep,
                summaryInstructions = NPCPrompting.summaryInstructions(persona),
                userLabel = NPCPrompting.PLAYER_LABEL,
                assistantLabel = persona.name.trim(),
            )
            TextCleanup.trimmedOrNull(summary)?.let { text -> memoryStore.update { it.copy(summary = text) } }
            return Result.success(summary)
        } catch (error: AgentError) {
            return Result.failure(error)
        } finally {
            agent.contextNote = NPCPrompting.memoryNote(memoryStore.memory, options)
        }
    }

    public companion object {
        /** Name of the built-in memory tool that stores a fact. */
        public const val REMEMBER_FACT_TOOL_NAME: String = "remember_fact"

        /** Name of the built-in memory tool that adjusts the relationship. */
        public const val CHANGE_RELATIONSHIP_TOOL_NAME: String = "change_relationship"

        /**
         * Restores an NPC saved with [saveState]. Tools, the world and options
         * are code, not part of the save: pass them again.
         *
         * @throws AgentError as the [NPC] constructor.
         */
        public fun restore(
            saved: NPCSaveState,
            model: LanguageModel,
            tools: List<AgentTool> = emptyList(),
            world: WorldState? = null,
            options: NPCOptions = NPCOptions(),
            scope: CoroutineScope? = null,
        ): NPC = NPC(saved.persona, model, tools, world, options, saved.memory, saved.transcript, scope)

        private fun requireName(persona: Persona) {
            if (persona.name.isBlank()) throw AgentError(AgentErrorCode.INVALID_REQUEST, "A persona needs a name.")
        }
    }
}
