package com.spacecorps.oam.bridge

import com.spacecorps.oam.Agent
import com.spacecorps.oam.AgentConfiguration
import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentErrorCode
import com.spacecorps.oam.AgentTool
import com.spacecorps.oam.GenerationKind
import com.spacecorps.oam.GenerationRequest
import com.spacecorps.oam.JsonSchema
import com.spacecorps.oam.ToolChoice
import com.spacecorps.oam.ToolPolicy
import com.spacecorps.oam.TranscriptEntry
import com.spacecorps.oam.bridge.BridgeCoding.obj
import com.spacecorps.oam.bridge.BridgeCoding.strings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration

/** `session/…` methods. */
internal object SessionMethods {
    fun register(registry: BridgeMethodRegistry) {
        registry.register("session/create", ::create)
        registry.register("session/respond", ::respond)
        registry.register("session/cancel") { request ->
            val session = request.engine.session(request.params.string("session"))
            BridgeReply.Result(obj("session" to JsonPrimitive(session.id), "cancelled" to JsonPrimitive(session.cancelAll())))
        }
        registry.register("session/delete") { request ->
            val id = request.params.string("session")
            request.engine.removeSession(id) ?: throw BridgeError.sessionNotFound(id)
            BridgeReply.Result(obj("session" to JsonPrimitive(id), "deleted" to JsonPrimitive(true)))
        }
        registry.register("session/list") { request ->
            BridgeReply.Result(obj("sessions" to JsonArray(request.engine.sessions.map { it.summary })))
        }
        registry.register("session/transcript") { request ->
            val session = request.engine.session(request.params.string("session"))
            BridgeReply.Result(obj("session" to JsonPrimitive(session.id), "transcript" to BridgeCoding.json(session.agent.transcript)))
        }
        // Scheduled operations call commit() right before they change the session, so a
        // cancelled operation never reports `cancelled` and then applies anyway.
        registry.register("session/reset") { request ->
            val session = request.engine.session(request.params.string("session"))
            session.schedule {
                BridgeSession.commit()
                session.agent.reset()
                obj("session" to JsonPrimitive(session.id))
            }
        }
        registry.register("session/setInstructions") { request ->
            val session = request.engine.session(request.params.string("session"))
            val instructions = request.params.optionalString("instructions")
            session.schedule {
                BridgeSession.commit()
                session.agent.instructions = instructions
                obj("session" to JsonPrimitive(session.id))
            }
        }
        registry.register("session/setContextNote") { request ->
            val session = request.engine.session(request.params.string("session"))
            val note = request.params.optionalString("note")
            session.schedule {
                BridgeSession.commit()
                session.agent.contextNote = note
                obj("session" to JsonPrimitive(session.id))
            }
        }
        registry.register("session/setTools") { request ->
            val session = request.engine.session(request.params.string("session"))
            val parsed = BridgeCoding.tools(request.params.value("tools"), session.toolTimeout)
            val warnings = parsed.warnings + listOfNotNull(BridgeCoding.toolCountWarning(parsed.tools.size))
            session.schedule {
                BridgeSession.commit()
                session.agent.setTools(parsed.tools)
                obj("session" to JsonPrimitive(session.id), "warnings" to strings(warnings))
            }
        }
        registry.register("session/compact") { request ->
            val session = request.engine.session(request.params.string("session"))
            val keep = request.params.optionalInt("keepRecentTurns", minimum = 0) ?: 2
            val instructions = request.params.optionalString("summaryInstructions")
            session.schedule {
                val summary = compact(session.agent, keep, instructions)
                obj("session" to JsonPrimitive(session.id), "summary" to (summary?.let(::JsonPrimitive) ?: JsonNull))
            }
        }
    }

    // MARK: session/compact

    /**
     * [Agent.compactHistory], but cancellable: the summary is generated in
     * the calling coroutine (so cancelling it stops the model call) and the
     * history is only replaced if the operation was not cancelled meanwhile.
     * The core method runs on the agent's own queue and always finishes, so a
     * cancelled `session/compact` would still rewrite the history.
     *
     * Runs inside the session's queue ([BridgeSession.schedule]), which keeps
     * turns from running while the summary is written.
     */
    private suspend fun compact(agent: Agent, keep: Int, summaryInstructions: String?): String? {
        val entries = agent.history
        val starts = entries.indices.filter { entries[it] is TranscriptEntry.Prompt }
        if (starts.size <= keep || entries.isEmpty()) return null
        val cut = if (keep <= 0) entries.size else starts[starts.size - keep]
        if (cut == 0) return null
        val configuration = agent.configuration
        val prompt = buildString {
            agent.contextNote?.takeIf { it.isNotBlank() }?.let { append("Summary so far:\n").append(it).append("\n\n") }
            append("Conversation to fold into the summary:\n")
            append(Agent.render(entries.subList(0, cut), configuration.userLabel, configuration.assistantLabel))
        }
        val instructions = summaryInstructions ?: Agent.SUMMARY_INSTRUCTIONS
        val systemInstructions = agent.model.capabilities.systemInstructions
        val generation = GenerationRequest(
            prompt = if (systemInstructions) prompt else "$instructions\n\n$prompt",
            systemInstruction = if (systemInstructions) instructions else null,
            temperature = configuration.decisionTemperature,
            maxOutputTokens = SUMMARY_MAX_TOKENS,
            kind = GenerationKind.SUMMARY,
        )
        var summary = ""
        agent.model.generate(generation).collect { summary = it.text }
        summary = summary.trim()
        if (summary.isEmpty()) throw AgentError(AgentErrorCode.GENERATION_FAILED, "The summary was empty.")
        BridgeSession.commit()
        agent.contextNote = summary
        agent.replaceHistory(entries.subList(cut, entries.size).toList())
        return summary
    }

    private const val SUMMARY_MAX_TOKENS = 400

    // MARK: session/create

    private val CREATE_KEYS = setOf("session", "instructions", "tools", "options", "history", "model")

    /** open-apple-models' options, plus the Android-only conversation labels. */
    private val OPTION_KEYS = setOf(
        "toolChoice", "maxToolRounds", "maxToolCalls", "enabledTools", "temperature", "maxResponseTokens",
        "sampling", "toolTimeoutSeconds", "trimHistory", "reservedResponseTokens", "maxAttempts",
        "userLabel", "assistantLabel",
    )

    private suspend fun create(request: BridgeRequest): BridgeReply {
        val engine = request.engine
        val params = request.params
        val warnings = params.unknownKeys(CREATE_KEYS).toMutableList()

        val id = params.optionalString("session")?.let { requested ->
            if (!BridgeCoding.isValidId(requested)) throw BridgeError.invalidParams("'session' must be 1-128 printable characters; got '$requested'.")
            if (!engine.isSessionIdAvailable(requested)) throw BridgeError.sessionExists(requested)
            requested
        } ?: engine.makeSessionId()

        val options = params.optionalNested("options")
        if (options != null) warnings += options.unknownKeys(OPTION_KEYS)
        val setup = agentConfiguration(options, engine)
        warnings += setup.warnings

        val history = params["history"]?.let { BridgeCoding.transcript(it) }
        val saved = history?.let(BridgeCoding::savedSetup)

        // Instructions and tools default to the ones saved in `history`, so {"history": …} alone
        // resumes a conversation. A key that is present (even null or []) always wins.
        var instructions = params.optionalString("instructions")
        if (!params.hasKey("instructions") && saved != null) instructions = saved.instructions

        var tools: List<AgentTool> = emptyList()
        val toolsValue = params["tools"]
        if (toolsValue != null) {
            val parsed = BridgeCoding.tools(toolsValue, setup.toolTimeout)
            tools = parsed.tools
            warnings += parsed.warnings
        } else if (!params.hasKey("tools") && saved != null) {
            val restored = BridgeCoding.clientTools(saved.tools, setup.toolTimeout)
            tools = restored.tools
            warnings += restored.warnings
        }
        val choice = setup.configuration.toolPolicy.choice
        if (choice is ToolChoice.Tool && tools.none { it.name == choice.name }) {
            throw BridgeError.invalidParams("'options.toolChoice' names tool '${choice.name}', which is not in 'tools'.")
        }
        BridgeCoding.toolCountWarning(tools.size)?.let { warnings += it }

        val spec = BridgeCoding.modelSpec(params["model"])
        val model = engine.makeModel(spec)
        if (spec is BridgeModelSpec.System) warnings += unavailableWarning(engine)

        val agent = Agent(model, instructions, tools, setup.configuration, history)
        val session = BridgeSession(id, agent, spec.kind, setup.toolTimeout, context = engine.configuration.dispatcher)
        try {
            engine.insert(session)
        } catch (error: BridgeError) {
            session.close()
            throw error
        }
        return BridgeReply.Result(obj("session" to JsonPrimitive(id), "warnings" to strings(warnings)))
    }

    /** An agent configuration and client-tool time limit from `session/create`'s `options`. */
    private class Setup(val configuration: AgentConfiguration, val toolTimeout: Duration?, val warnings: List<String>)

    private fun agentConfiguration(options: BridgeParams?, engine: BridgeEngine): Setup {
        val defaultTimeout = engine.configuration.defaultToolTimeout
        if (options == null) return Setup(AgentConfiguration(), defaultTimeout, emptyList())
        val warnings = ArrayList<String>()
        var configuration = AgentConfiguration(toolPolicy = BridgeCoding.toolPolicy(options, ToolPolicy.Default))
        options.optionalDouble("temperature", minimum = 0.0)?.let { configuration = configuration.copy(temperature = it) }
        options.optionalInt("maxResponseTokens", minimum = 1)?.let { configuration = configuration.copy(maxResponseTokens = it) }
        options["sampling"]?.let { value ->
            val sampling = BridgeCoding.sampling(value, "options.sampling")
            configuration = configuration.copy(topK = sampling.topK ?: configuration.topK, seed = sampling.seed ?: configuration.seed)
            warnings += sampling.warnings
        }
        val seconds = options.optionalSeconds("toolTimeoutSeconds")
        val toolTimeout = if (seconds != null) BridgeCoding.timeout(seconds) else defaultTimeout
        options.optionalBool("trimHistory")?.let { configuration = configuration.copy(context = configuration.context.copy(trimsHistory = it)) }
        options.optionalInt("reservedResponseTokens", minimum = 0)?.let {
            configuration = configuration.copy(context = configuration.context.copy(reservedTokens = it))
        }
        options.optionalInt("maxAttempts", minimum = 1)?.let { configuration = configuration.copy(retry = configuration.retry.copy(maxAttempts = it)) }
        options.label("userLabel")?.let { configuration = configuration.copy(userLabel = it) }
        options.label("assistantLabel")?.let { configuration = configuration.copy(assistantLabel = it) }
        return Setup(configuration, toolTimeout, warnings)
    }

    private fun BridgeParams.label(key: String): String? {
        val label = optionalString(key) ?: return null
        if (label.isBlank()) throw BridgeError.invalidParams("Parameter '${name(key)}' must not be blank.")
        return label.trim()
    }

    /** A warning when the system model cannot run now (sessions still work once it can). */
    internal suspend fun unavailableWarning(engine: BridgeEngine): List<String> {
        val availability = engine.configuration.modelAvailability()
        if (availability.available) return emptyList()
        return listOf(
            "The system model is unavailable (${availability.reason ?: "unknown"}); turns will fail with model_unavailable until it is ready.",
        )
    }

    // MARK: session/respond

    private val RESPOND_KEYS = setOf("session", "prompt", "schema", "stream", "toolChoice", "maxToolRounds", "maxToolCalls", "enabledTools")

    private fun respond(request: BridgeRequest): BridgeReply {
        val params = request.params
        val session = request.engine.session(params.string("session"))
        val prompt = params.string("prompt")
        val stream = params.optionalBool("stream") ?: false
        val policy = BridgeCoding.toolPolicy(params, session.agent.configuration.toolPolicy)
        val warnings = params.unknownKeys(RESPOND_KEYS).toMutableList()
        val schema: JsonSchema? = params["schema"]?.let { value ->
            BridgeCoding.schema(value).also { warnings += BridgeCoding.checkSchema(it, "schema") }
        }
        val context = obj("session" to JsonPrimitive(session.id))
        return session.schedule {
            // Checked when the turn starts, against the tools in force then
            // (an earlier pipelined session/setTools may add the tool).
            val choice = policy.choice
            if (choice is ToolChoice.Tool && session.agent.tools.none { it.name == choice.name }) {
                throw BridgeError.invalidParams("'toolChoice' names tool '${choice.name}', which session '${session.id}' does not have.")
            }
            val run = if (schema != null) session.agent.run(prompt, schema, policy) else session.agent.run(prompt, policy)
            val response = try {
                request.drive(run, stream, context)
            } catch (error: Throwable) {
                // Report a failed or cancelled turn only once the agent has rolled it back,
                // so the client never observes partial state.
                run.cancel()
                withContext(NonCancellable) { session.agent.waitUntilIdle() }
                throw error
            }
            result("session" to JsonPrimitive(session.id), BridgeCoding.json(response), warnings)
        }
    }

    /** `{first, …body, "warnings"?}`: warnings only when there are any. */
    internal fun result(first: Pair<String, JsonElement>, body: JsonObject, warnings: List<String>): JsonObject {
        val members = linkedMapOf(first)
        members.putAll(body)
        if (warnings.isNotEmpty()) members["warnings"] = strings(warnings)
        return JsonObject(members)
    }
}
