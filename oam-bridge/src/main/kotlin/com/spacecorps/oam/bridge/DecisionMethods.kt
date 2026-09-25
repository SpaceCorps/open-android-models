package com.spacecorps.oam.bridge

import com.spacecorps.oam.AgentTool
import com.spacecorps.oam.ToolChoice
import com.spacecorps.oam.ToolExecution
import com.spacecorps.oam.bridge.BridgeCoding.obj
import com.spacecorps.oam.game.ContentGenerator
import com.spacecorps.oam.game.Decision
import com.spacecorps.oam.game.DecisionEngine
import com.spacecorps.oam.game.DecisionRequest
import com.spacecorps.oam.game.Persona
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration

/**
 * `decision/decide`, `decision/decideMany` and `content/generate`.
 *
 * These are one-shot requests (a fresh model request each, no history), so
 * they run concurrently with everything else. Client tools are forwarded to
 * the peer as `tool/call` requests, exactly as for sessions and NPCs.
 */
internal object DecisionMethods {
    fun register(registry: BridgeMethodRegistry, game: GameExtension) {
        registry.register("decision/decide") { request -> decide(request, game) }
        registry.register("decision/decideMany") { request -> decideMany(request, game) }
        registry.register("content/generate", ::generate)
    }

    // MARK: Engine settings

    private val ENGINE_KEYS = setOf("model", "instructions", "temperature", "maxToolRounds", "toolTimeoutSeconds")

    /**
     * `model`, `instructions`, `temperature`, `maxToolRounds` and `toolTimeoutSeconds`, shared by `decide` and
     * `decideMany`. The model is resolved last ([engine]), so parameter mistakes are reported first.
     */
    private class EngineSettings(params: BridgeParams, private val bridge: BridgeEngine) {
        private val spec = BridgeCoding.modelSpec(params["model"])
        private val instructions = params.optionalString("instructions")
        private val temperature = params.optionalDouble("temperature", minimum = 0.0)
        private val maxToolRounds = params.optionalInt("maxToolRounds", minimum = 0) ?: 2
        val toolTimeout: Duration? = toolTimeout(params, bridge)

        fun engine(): DecisionEngine = DecisionEngine(bridge.makeModel(spec), instructions, temperature, maxToolRounds)
    }

    private fun toolTimeout(params: BridgeParams, bridge: BridgeEngine): Duration? {
        val seconds = params.optionalSeconds("toolTimeoutSeconds")
        return if (seconds != null) BridgeCoding.timeout(seconds) else bridge.configuration.defaultToolTimeout
    }

    // MARK: decision/decide

    private val DECISION_KEYS = setOf("situation", "options", "actor", "context", "tools", "toolChoice", "fallbackOptionID")

    private fun decide(request: BridgeRequest, game: GameExtension): BridgeReply {
        val params = request.params
        val settings = EngineSettings(params, request.engine)
        val warnings = params.unknownKeys(DECISION_KEYS + ENGINE_KEYS).toMutableList()
        val (decision, decisionWarnings) = decisionRequest(params, request, game, settings.toolTimeout, emptyMap())
        warnings += decisionWarnings
        val engine = settings.engine()
        return BridgeReply.Deferred {
            val result = LinkedHashMap(GameCoding.json(engine.decide(decision)))
            if (warnings.isNotEmpty()) result["warnings"] = BridgeCoding.strings(warnings)
            JsonObject(result)
        }
    }

    // MARK: decision/decideMany

    private val MANY_KEYS = setOf("requests", "maxConcurrency")

    /**
     * Several independent decisions (a crowd of NPCs). Results come back in
     * request order; one failing decision does not fail the others (its slot
     * holds `{"error": {…}}`).
     */
    private fun decideMany(request: BridgeRequest, game: GameExtension): BridgeReply {
        val params = request.params
        val settings = EngineSettings(params, request.engine)
        val warnings = params.unknownKeys(MANY_KEYS + ENGINE_KEYS).toMutableList()
        val maxConcurrency = params.optionalInt("maxConcurrency", minimum = 1) ?: 2
        val elements = params.optionalArray("requests")
        if (elements.isNullOrEmpty()) throw BridgeError.invalidParams("'requests' must be a non-empty array of decisions.")
        val requests = elements.mapIndexed { index, element ->
            val path = "requests[$index]"
            val obj = element as? JsonObject ?: throw BridgeError.invalidParams("'$path' must be an object.")
            val elementParams = BridgeParams(obj, "$path.")
            warnings += elementParams.unknownKeys(DECISION_KEYS)
            val (decision, decisionWarnings) = decisionRequest(
                elementParams, request, game, settings.toolTimeout, mapOf("index" to JsonPrimitive(index)),
            )
            warnings += decisionWarnings.map { "$path: $it" }
            decision
        }
        val engine = settings.engine()
        return BridgeReply.Deferred {
            val results = decideInOrder(engine, requests, maxConcurrency).map { result ->
                result.fold(
                    onSuccess = { GameCoding.json(it) },
                    onFailure = { obj("error" to BridgeError.normalizing(it).toJson()) },
                )
            }
            val members = linkedMapOf<String, JsonElement>("results" to JsonArray(results))
            if (warnings.isNotEmpty()) members["warnings"] = BridgeCoding.strings(warnings)
            JsonObject(members)
        }
    }

    /**
     * Runs the decisions with at most [maxConcurrency] at once, starting them in request order (a
     * permit is taken before each one is launched), so a script or a rate-limited model sees them
     * in the order the client sent them. Failures are isolated per decision.
     */
    private suspend fun decideInOrder(engine: DecisionEngine, requests: List<DecisionRequest>, maxConcurrency: Int): List<Result<Decision>> =
        coroutineScope {
            val permits = Semaphore(maxConcurrency)
            requests.map { request ->
                permits.acquire()
                async {
                    try {
                        Result.success(engine.decide(request))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Result.failure(error)
                    } finally {
                        permits.release()
                    }
                }
            }.awaitAll()
        }

    // MARK: Parsing

    /**
     * Parses one decision. Options, the fallback and a named tool choice are
     * validated here, so mistakes fail with `invalid_params` before any model call.
     */
    private fun decisionRequest(
        params: BridgeParams,
        request: BridgeRequest,
        game: GameExtension,
        toolTimeout: Duration?,
        toolContext: Map<String, JsonElement>,
    ): Pair<DecisionRequest, List<String>> {
        val warnings = ArrayList<String>()
        val situation = GameCoding.text(params.value("situation")) ?: ""
        val options = GameCoding.decisionOptions(params.value("options"), params.name("options"))
        val ids = options.map { it.id }

        var actor: Persona? = null
        params["actor"]?.let { value ->
            val npcId = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            actor = if (npcId != null) {
                // An NPC id: decide as that character.
                game.npcEntry(npcId).npc.persona
            } else {
                GameCoding.persona(value, params.name("actor")).also { warnings += it.warnings }.value
            }
        }

        var tools: List<AgentTool> = emptyList()
        params["tools"]?.let { value ->
            val parsed = ForwardedTools.tools(value, params.name("tools"), request, toolContext, toolTimeout)
            tools = parsed.tools
            warnings += parsed.warnings
        }
        BridgeCoding.toolCountWarning(tools.size)?.let { warnings += it }

        var toolChoice: ToolChoice = ToolChoice.Auto
        params["toolChoice"]?.let { value ->
            toolChoice = BridgeCoding.toolChoice(value, params.name("toolChoice"))
            val choice = toolChoice
            if (choice is ToolChoice.Tool && tools.none { it.name == choice.name }) {
                throw BridgeError.invalidParams("'${params.name("toolChoice")}' names tool '${choice.name}', which is not in '${params.name("tools")}'.")
            }
        }

        val fallback = params.optionalString("fallbackOptionID")?.trim()
        if (fallback != null && fallback !in ids) {
            throw BridgeError.invalidParams(
                "'${params.name("fallbackOptionID")}' must be one of the option ids (${ids.joinToString(", ")}); got '$fallback'.",
            )
        }

        val decision = DecisionRequest(
            situation = situation,
            options = options,
            actor = actor,
            context = params["context"],
            tools = tools,
            toolChoice = toolChoice,
            fallbackOptionId = fallback,
        )
        return decision to warnings
    }

    // MARK: content/generate

    private val GENERATE_KEYS = setOf("prompt", "schema", "instructions", "context", "tools", "toolTimeoutSeconds", "model", "temperature")

    private fun generate(request: BridgeRequest): BridgeReply {
        val params = request.params
        val warnings = params.unknownKeys(GENERATE_KEYS).toMutableList()
        val prompt = params.string("prompt")
        val schema = BridgeCoding.schema(params.value("schema"))
        warnings += BridgeCoding.checkSchema(schema, "schema")
        val instructions = params.optionalString("instructions")
        val spec = BridgeCoding.modelSpec(params["model"])
        val temperature = params.optionalDouble("temperature", minimum = 0.0)
        var tools: List<AgentTool> = emptyList()
        params["tools"]?.let { value ->
            val parsed = ForwardedTools.tools(value, "tools", request, emptyMap(), toolTimeout(params, request.engine))
            tools = parsed.tools
            warnings += parsed.warnings
        }
        val context = params["context"]
        val generator = ContentGenerator(model = request.engine.makeModel(spec), temperature = temperature)
        return BridgeReply.Deferred {
            val content = generator.generate(prompt, schema, instructions, context, tools)
            val members = linkedMapOf("content" to content)
            if (warnings.isNotEmpty()) members["warnings"] = BridgeCoding.strings(warnings)
            JsonObject(members)
        }
    }
}

/**
 * Client tools for APIs that take ready-made [AgentTool]s (decisions,
 * content): each becomes a local tool whose handler sends `tool/call` to the
 * peer and returns its answer. If the call times out or the request is
 * cancelled first, the peer gets `tool/cancel`.
 */
internal object ForwardedTools {
    fun tools(
        value: JsonElement,
        path: String,
        request: BridgeRequest,
        context: Map<String, JsonElement>,
        defaultTimeout: Duration?,
    ): BridgeCoding.ParsedTools {
        // Local tools without a limit would get the agent's default; "no limit" must stay no limit.
        val parsed = BridgeCoding.tools(value, defaultTimeout ?: Duration.INFINITE, path)
        val engine = request.engine
        val base = LinkedHashMap(context)
        base["requestId"] = request.id?.value ?: JsonNull
        val tools = parsed.tools.map { definition ->
            definition.withExecution(
                ToolExecution.Local { call ->
                    val pending = engine.sendRequest("tool/call", JsonObject(base + ("call" to BridgeCoding.json(call))))
                    val result = pending.awaitResult {
                        engine.notify(
                            "tool/cancel",
                            JsonObject(
                                base + mapOf(
                                    "id" to JsonPrimitive(pending.id),
                                    "callId" to JsonPrimitive(call.id),
                                    "reason" to JsonPrimitive("The tool call timed out or its request was cancelled."),
                                ),
                            ),
                        )
                    }
                    BridgeCoding.toolOutput(result)
                },
            )
        }
        return BridgeCoding.ParsedTools(tools, parsed.warnings)
    }
}
