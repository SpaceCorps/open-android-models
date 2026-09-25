package com.spacecorps.oam

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/** Everything a turn needs from the agent, captured when the turn starts. */
internal class TurnSetup(
    val instructions: String?,
    val contextNote: String?,
    val tools: List<AgentTool>,
    val configuration: AgentConfiguration,
    val history: List<TranscriptEntry>,
)

/**
 * Runs one turn: decide → execute → record → decide again, until the model
 * chooses `respond` or the budgets run out; then the respond (or structured)
 * step. New transcript entries are reported through [onEntry] as they
 * happen and returned at the end; the agent commits them only if the turn
 * succeeds.
 */
internal class TurnEngine(
    private val model: LanguageModel,
    private val run: AgentRun,
    private val setup: TurnSetup,
    private val prompt: String,
    private val schema: JsonSchema?,
    private val onEntry: (TranscriptEntry) -> Unit,
) {
    private sealed interface Decision {
        data object Respond : Decision

        class Call(val tool: AgentTool, val arguments: JsonObject) : Decision

        /** Invalid output that survived the repair attempt. A known [tool] gets an error record so the reply can mention it. */
        class Failed(val problem: String, val tool: AgentTool? = null, val arguments: JsonObject? = null) : Decision
    }

    private val config = setup.configuration
    private val policy = run.policy
    private val renderer = PromptRenderer(
        instructions = setup.instructions,
        contextNote = setup.contextNote,
        userLabel = config.userLabel,
        assistantLabel = config.assistantLabel,
        capabilities = model.capabilities,
        context = config.context,
        countTokens = { text -> model.countTokens(text) },
        structuredReply = config.structuredReplyRenderer,
    )
    private val uses = ArrayList<TranscriptEntry.ToolUse>()
    private val entries = ArrayList<TranscriptEntry>()
    private var usage = TokenUsage()
    private var rounds = 0
    private var calls = 0
    private var emittedText = false
    private val attempts get() = if (config.repairsInvalidOutput) 2 else 1

    suspend fun run(): Pair<AgentResponse, List<TranscriptEntry>> {
        val enabled = setup.tools.filter { policy.enabledTools?.contains(it.name) ?: true }
        val forced = (policy.choice as? ToolChoice.Tool)?.let { choice ->
            setup.tools.firstOrNull { it.name == choice.name }
                ?: throw AgentError(AgentErrorCode.INVALID_REQUEST, "ToolChoice.Tool('${choice.name}') names no tool of this agent.")
        }
        var respondNow = policy.choice == ToolChoice.None || (enabled.isEmpty() && forced == null)
        while (!respondNow && rounds < policy.maxToolRounds && calls < policy.maxToolCalls) {
            val first = rounds == 0
            val decision = when {
                first && forced != null -> argumentsStep(forced)
                // Required with a single tool is the same as naming it.
                first && policy.choice == ToolChoice.Required && enabled.size == 1 -> argumentsStep(enabled.single())
                else -> decideStep(enabled, allowRespond = !(first && policy.choice == ToolChoice.Required))
            }
            when (decision) {
                Decision.Respond -> respondNow = true
                is Decision.Call -> {
                    execute(decision.tool, decision.arguments)
                    rounds++
                    calls++
                }
                is Decision.Failed -> {
                    decision.tool?.let { tool ->
                        reject(tool, decision.arguments ?: EmptyJsonObject, decision.problem)
                        rounds++
                        calls++
                    }
                    respondNow = true
                }
            }
        }
        val (text, structured) = if (schema != null) structuredStep(schema) else respondStep() to null
        add(TranscriptEntry.Response(text, structured))
        val response = AgentResponse(text, structured, run.recordsSnapshot, usage, run.stepsSnapshot)
        return response to entries
    }

    private fun add(entry: TranscriptEntry) {
        entries += entry
        onEntry(entry)
    }

    // MARK: Steps

    private suspend fun decideStep(tools: List<AgentTool>, allowRespond: Boolean): Decision {
        var repair: Repair? = null
        var failure: Decision.Failed = Decision.Failed("no decision")
        val names = tools.map { it.name }
        for (attempt in 0 until attempts) {
            val output = infer(
                kind = StepKind.DECIDE,
                mode = if (allowRespond) ToolCallingMode.ALLOWED else ToolCallingMode.REQUIRED,
                offered = names,
                isRepair = attempt > 0,
                task = PromptTask.Decide(tools, allowRespond, repair),
                generationKind = GenerationKind.DECIDE,
                stopWhenComplete = true,
            )
            when (val parsed = StepEnvelope.parse(output, names, allowRespond)) {
                StepEnvelope.Result.Respond -> return Decision.Respond
                is StepEnvelope.Result.Invalid -> {
                    repair = Repair(parsed.problem, output)
                    failure = Decision.Failed(parsed.problem, parsed.tool?.let { name -> tools.firstOrNull { it.name == name } })
                }
                is StepEnvelope.Result.Call -> {
                    val tool = tools.first { it.name == parsed.tool }
                    when (val checked = checkArguments(tool, parsed.candidates)) {
                        // Small models tend to repeat a call whose result they already have; that means they are done.
                        is Checked.Valid -> return if (alreadyCalled(tool, checked.arguments)) Decision.Respond else Decision.Call(tool, checked.arguments)
                        is Checked.Invalid -> {
                            val problem = "the arguments for ${tool.name} are invalid: ${checked.problem}"
                            repair = Repair(problem, output)
                            failure = Decision.Failed(problem, tool, checked.arguments)
                        }
                    }
                }
            }
        }
        return failure
    }

    private suspend fun argumentsStep(tool: AgentTool): Decision {
        if (tool.parameters.isEmptyObject) return Decision.Call(tool, EmptyJsonObject)
        var repair: Repair? = null
        var failure = Decision.Failed("no arguments", tool)
        for (attempt in 0 until attempts) {
            val output = infer(
                kind = StepKind.TOOL_ARGUMENTS,
                mode = ToolCallingMode.REQUIRED,
                offered = listOf(tool.name),
                isRepair = attempt > 0,
                task = PromptTask.ToolArguments(tool, repair),
                generationKind = GenerationKind.TOOL_ARGUMENTS,
                stopWhenComplete = true,
            )
            val found = JsonExtraction.firstObject(output)
            if (found == null) {
                val problem = "the answer was not a JSON object"
                repair = Repair(problem, output)
                failure = Decision.Failed(problem, tool)
                continue
            }
            when (val checked = checkArguments(tool, StepEnvelope.argumentCandidates(found, tool.name))) {
                is Checked.Valid -> return Decision.Call(tool, checked.arguments)
                is Checked.Invalid -> {
                    val problem = "the arguments for ${tool.name} are invalid: ${checked.problem}"
                    repair = Repair(problem, output)
                    failure = Decision.Failed(problem, tool, checked.arguments)
                }
            }
        }
        return failure
    }

    private suspend fun respondStep(): String {
        val cleaner = ReplyCleaner(config.assistantLabel)
        var sent = ""
        var needsReset = false
        val output = infer(
            kind = StepKind.RESPOND,
            mode = ToolCallingMode.DISALLOWED,
            offered = emptyList(),
            isRepair = false,
            task = PromptTask.Respond,
            generationKind = GenerationKind.RESPOND,
            stopWhenComplete = false,
            temperature = config.temperature,
            maxOutputTokens = config.maxResponseTokens,
            onAttempt = {
                sent = ""
                needsReset = emittedText
            },
            onChunk = { chunk ->
                if (config.streamsResponses) {
                    val current = cleaner.visible(chunk.text)
                    if (current.isNotEmpty() && current != sent) {
                        when {
                            needsReset -> run.emit(AgentEvent.Text(current, current, isReset = true))
                            current.startsWith(sent) -> run.emit(AgentEvent.Text(current.substring(sent.length), current, isReset = false))
                            else -> run.emit(AgentEvent.Text(current, current, isReset = true))
                        }
                        needsReset = false
                        sent = current
                        emittedText = true
                    }
                }
            },
        )
        val text = cleaner.final(output)
        if (text != sent && text != sent.trimEnd()) {
            val reset = needsReset || !text.startsWith(sent)
            val delta = if (reset) text else text.substring(sent.length)
            if (delta.isNotEmpty() || reset) run.emit(AgentEvent.Text(delta, text, isReset = reset))
            emittedText = true
        }
        return text
    }

    private suspend fun structuredStep(schema: JsonSchema): Pair<String, JsonElement> {
        var repair: Repair? = null
        var problem = "no output"
        for (attempt in 0 until attempts) {
            var lastPartial: JsonElement? = null
            val output = infer(
                kind = StepKind.STRUCTURED,
                mode = ToolCallingMode.DISALLOWED,
                offered = emptyList(),
                isRepair = attempt > 0,
                task = PromptTask.Structured(schema, repair),
                generationKind = GenerationKind.STRUCTURED,
                stopWhenComplete = true,
                expectsArray = schema.json["type"]?.stringValue == "array",
                temperature = config.temperature,
                maxOutputTokens = config.maxResponseTokens,
                onChunk = { chunk ->
                    if (config.streamsResponses) {
                        val partial = JsonExtraction.firstValue(chunk.text, allowTruncated = true)
                        if (partial != null && partial != lastPartial) {
                            lastPartial = partial
                            run.emit(AgentEvent.Partial(partial))
                        }
                    }
                },
            )
            val value = JsonExtraction.firstValue(output, allowTruncated = true)
            if (value == null) {
                problem = "the answer did not contain JSON"
                repair = Repair(problem, output)
                continue
            }
            val coerced = schema.coerce(value)
            val violations = schema.validate(coerced)
            if (violations.isEmpty()) {
                val ordered = schema.order(coerced)
                run.emit(AgentEvent.Partial(ordered))
                return ordered.toJsonString() to ordered
            }
            problem = "the JSON does not match the required form: ${violations.describe()}"
            repair = Repair(problem, output)
        }
        throw AgentError(AgentErrorCode.GENERATION_FAILED, "The model's structured output stayed invalid after a repair attempt: $problem")
    }

    // MARK: Tools

    private sealed interface Checked {
        class Valid(val arguments: JsonObject) : Checked

        class Invalid(val problem: String, val arguments: JsonObject) : Checked
    }

    private fun alreadyCalled(tool: AgentTool, arguments: JsonObject): Boolean =
        uses.any { it.call.name == tool.name && it.call.arguments == arguments && !it.output.isError }

    private fun checkArguments(tool: AgentTool, candidates: List<JsonObject>): Checked {
        var firstFailure: Checked.Invalid? = null
        for (candidate in candidates) {
            val coerced = tool.parameters.coerce(candidate) as? JsonObject ?: candidate
            val violations = tool.parameters.validate(coerced)
            if (violations.isEmpty()) return Checked.Valid(tool.parameters.order(coerced) as? JsonObject ?: coerced)
            if (firstFailure == null) firstFailure = Checked.Invalid(violations.describe(), coerced)
        }
        return firstFailure ?: Checked.Invalid("no arguments", EmptyJsonObject)
    }

    private suspend fun execute(tool: AgentTool, arguments: JsonObject) {
        val call = ToolCall(name = tool.name, arguments = arguments)
        val started = System.nanoTime()
        val output = when (val execution = tool.execution) {
            is ToolExecution.Local -> {
                run.emit(AgentEvent.ToolCallStarted(call))
                runLocal(execution.handler, call, tool.timeout ?: config.toolTimeout)
            }
            ToolExecution.External -> run.awaitExternal(call, tool.timeout)
        }
        currentCoroutineContext().ensureActive()
        finishCall(call, output, (System.nanoTime() - started) / 1e9)
    }

    /** Records a call the model chose but whose arguments stayed invalid; it never runs. */
    private fun reject(tool: AgentTool, arguments: JsonObject, problem: String) {
        val call = ToolCall(name = tool.name, arguments = arguments)
        finishCall(call, ToolOutput.Error("The call was not made: $problem."), 0.0)
    }

    private fun finishCall(call: ToolCall, output: ToolOutput, seconds: Double) {
        run.record(ToolRecord(call, output, seconds))
        val use = TranscriptEntry.ToolUse(call, output)
        uses += use
        add(use)
    }

    /**
     * Runs a local handler detached from the turn and races it against the
     * timeout and cancellation, so a handler that ignores cancellation cannot
     * hold the turn (it is cancelled and left to finish on its own).
     */
    private suspend fun runLocal(handler: suspend (ToolCall) -> ToolOutput, call: ToolCall, timeout: Duration?): ToolOutput {
        val scope = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
        val result = scope.async {
            try {
                handler(call)
            } catch (_: CancellationException) {
                ToolOutput.Error("The tool call was cancelled.")
            } catch (error: Throwable) {
                ToolOutput.Error(AgentRun.describe(error))
            }
        }
        try {
            return if (timeout == null) {
                result.await()
            } else {
                withTimeoutOrNull(timeout) { result.await() } ?: ToolOutput.Error("Tool '${call.name}' timed out after $timeout.")
            }
        } finally {
            scope.cancel()
        }
    }

    // MARK: Inference

    /**
     * Renders, announces and runs one model step with retries, returning the full output.
     *
     * @param stopWhenComplete Stop reading once the output holds a complete JSON value (saves on-device time).
     * @param onAttempt Called before each attempt (a retry restarts the output).
     * @param onChunk Called for each streamed chunk.
     */
    private suspend fun infer(
        kind: StepKind,
        mode: ToolCallingMode,
        offered: List<String>,
        isRepair: Boolean,
        task: PromptTask,
        generationKind: GenerationKind,
        stopWhenComplete: Boolean,
        temperature: Double? = config.decisionTemperature,
        maxOutputTokens: Int? = config.decisionMaxTokens,
        expectsArray: Boolean = false,
        onAttempt: () -> Unit = {},
        onChunk: (GenerationChunk) -> Unit = {},
    ): String {
        val rendered = renderer.render(setup.history, prompt, uses, task)
        run.emit(
            AgentEvent.ModelStep(
                ModelStep(
                    index = run.stepCount,
                    completedToolRounds = rounds,
                    toolCallingMode = mode,
                    enabledTools = offered,
                    trimmedEntries = rendered.trimmedEntries,
                    kind = kind,
                    isRepair = isRepair,
                ),
            ),
        )
        val request = GenerationRequest(
            prompt = rendered.prompt,
            systemInstruction = rendered.systemInstruction,
            promptPrefix = rendered.promptPrefix,
            temperature = temperature,
            topK = config.topK,
            seed = config.seed,
            maxOutputTokens = maxOutputTokens,
            kind = generationKind,
        )
        return withRetry {
            onAttempt()
            var text = ""
            var reported: TokenUsage? = null
            model.generate(request)
                .transformWhile { chunk ->
                    emit(chunk)
                    !(stopWhenComplete && JsonExtraction.hasCompleteValue(chunk.text, arrays = expectsArray))
                }
                .collect { chunk ->
                    text = chunk.text
                    chunk.usage?.let { reported = it }
                    onChunk(chunk)
                }
            usage += reported ?: TokenUsage(
                inputTokens = rendered.estimatedInputTokens,
                outputTokens = estimateTokens(text),
            )
            text
        }
    }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        val retry = config.retry
        var attempt = 1
        var wait = retry.initialDelay
        while (true) {
            val error = try {
                return block()
            } catch (error: Throwable) {
                // Our own cancellation ends the turn; a cancellation from inside the model is a model failure.
                if (error is CancellationException && !currentCoroutineContext().isActive) throw error
                AgentError.from(error)
            }
            if (attempt >= retry.maxAttempts || !retry.shouldRetry(error)) throw error
            val retryAfter = error.retryAfter
            delay(if (retryAfter != null && retryAfter > wait) retryAfter else wait)
            wait *= 2
            attempt++
        }
    }
}

/**
 * Cleans the reply as it streams: drops leading whitespace and a leading
 * speaker label (`Mira: …`, `**Mira:** …`) that small models like to add,
 * holding back text while it could still be such a label.
 */
internal class ReplyCleaner(label: String) {
    private val toolRecord = Regex("\\[[A-Za-z_][\\w.-]*(?: \\{[^\\n]*?\\})? → [^\\n]*?\\]")
    private val labels = listOf(label, "Assistant").distinct().map { it.lowercase() }

    fun visible(raw: String): String {
        val text = raw.trimStart()
        val lower = text.lowercase().trimStart('*', '_')
        for (label in labels) {
            val marker = "$label:"
            if (lower.length < marker.length + 1 && marker.startsWith(lower.trimEnd('*', '_'))) return "" // might still become a label
            if (lower.startsWith(marker) || lower.startsWith("$label**:") || lower.startsWith("$label:**")) {
                val cut = text.indexOf(':') + 1
                return text.substring(cut).trimStart('*', '_', ' ', '\t', '\n')
            }
        }
        return text
    }

    fun final(raw: String): String {
        // An echoed tool record in our own `[name {…} → …]` format is never part of a reply.
        var text = visible(raw).replace(toolRecord, " ").replace(Regex("[ \t]{2,}"), " ").trim()
        if (text.length >= 2 && text.first() == '"' && text.last() == '"' && text.count { it == '"' } == 2) {
            text = text.substring(1, text.length - 1).trim()
        }
        return text
    }
}
