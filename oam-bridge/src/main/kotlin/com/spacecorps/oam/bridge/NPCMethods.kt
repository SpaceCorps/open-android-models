package com.spacecorps.oam.bridge

import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentErrorCode
import com.spacecorps.oam.AgentTool
import com.spacecorps.oam.ToolChoice
import com.spacecorps.oam.Transcript
import com.spacecorps.oam.arrayValue
import com.spacecorps.oam.bridge.BridgeCoding.obj
import com.spacecorps.oam.bridge.BridgeCoding.strings
import com.spacecorps.oam.game.DialogueEvent
import com.spacecorps.oam.game.DialogueStream
import com.spacecorps.oam.game.DialogueTurn
import com.spacecorps.oam.game.NPC
import com.spacecorps.oam.game.NPCMemory
import com.spacecorps.oam.game.NPCOptions
import com.spacecorps.oam.game.NPCSaveState
import com.spacecorps.oam.game.Persona
import com.spacecorps.oam.game.WorldState
import com.spacecorps.oam.stringValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration

/** `npc/…` methods. */
internal object NPCMethods {
    fun register(registry: BridgeMethodRegistry, game: GameExtension) {
        registry.register("npc/create") { request -> create(request, game) }
        registry.register("npc/restore") { request -> restore(request, game) }
        registry.register("npc/talk") { request -> talk(request, game) }
        registry.register("npc/bark") { request -> bark(request, game) }
        registry.register("npc/state") { request -> state(request, game) }
        registry.register("npc/update") { request -> update(request, game) }
        registry.register("npc/reset") { request ->
            val entry = game.npcEntry(request.params.string("npc"))
            val clearMemory = request.params.optionalBool("clearMemory") ?: false
            entry.queue.schedule {
                BridgeSession.commit()
                entry.npc.resetConversation(clearingMemory = clearMemory)
                obj("npc" to JsonPrimitive(entry.id))
            }
        }
        registry.register("npc/cancel") { request ->
            val entry = game.npcEntry(request.params.string("npc"))
            BridgeReply.Result(obj("npc" to JsonPrimitive(entry.id), "cancelled" to JsonPrimitive(entry.queue.cancelAll())))
        }
        registry.register("npc/delete") { request ->
            val id = request.params.string("npc")
            game.removeNpc(id) ?: throw BridgeError.npcNotFound(id)
            BridgeReply.Result(obj("npc" to JsonPrimitive(id), "deleted" to JsonPrimitive(true)))
        }
        registry.register("npc/list") {
            BridgeReply.Result(obj("npcs" to JsonArray(game.npcEntries.map { it.summary })))
        }
    }

    // MARK: npc/create and npc/restore

    /** What an NPC is built from; shared by `npc/create` and `npc/restore`. */
    private class Blueprint(val id: String, val persona: Persona) {
        var memory = NPCMemory()
        var history: Transcript? = null
        var toolDefinitions: List<JsonElement> = emptyList()
        var toolsPath = "tools"
        var options = NPCOptions()
        var toolTimeoutSeconds: Double? = null
        var worldId: String? = null
        var model: BridgeModelSpec = BridgeModelSpec.System
    }

    private val CREATE_KEYS = setOf("npc", "persona", "tools", "world", "options", "memory", "model")

    private suspend fun create(request: BridgeRequest, game: GameExtension): BridgeReply {
        val params = request.params
        val warnings = params.unknownKeys(CREATE_KEYS).toMutableList()
        val id = game.npcId(params.optionalString("npc"))
        val persona = GameCoding.persona(params.value("persona"))
        warnings += persona.warnings
        val blueprint = Blueprint(id, persona.value)
        blueprint.memory = GameCoding.memory(params["memory"])
        blueprint.toolDefinitions = toolDefinitions(params["tools"], "tools")
        val options = GameCoding.npcOptions(params["options"])
        blueprint.options = options.options
        blueprint.toolTimeoutSeconds = options.toolTimeoutSeconds
        warnings += options.warnings
        blueprint.worldId = params.optionalString("world")
        blueprint.model = BridgeCoding.modelSpec(params["model"])
        return build(blueprint, request, game, warnings)
    }

    private val RESTORE_KEYS = setOf("npc", "state", "tools", "world", "options", "model")

    /**
     * Rebuilds an NPC from an `npc/state` save. Tools, options and world
     * default to the ones stored in the save; a key that is present (even
     * `null` or `[]`) wins.
     */
    private suspend fun restore(request: BridgeRequest, game: GameExtension): BridgeReply {
        val params = request.params
        val warnings = params.unknownKeys(RESTORE_KEYS).toMutableList()
        val stateValue = params.value("state")
        val saved: NPCSaveState = GameCoding.saveState(stateValue)
        // Accept the whole npc/state result as well as its `state` member.
        val stateObject = stateValue as JsonObject
        val extras = if ("persona" in stateObject) stateObject else (stateObject["state"] as? JsonObject).orEmpty()

        val requestedId = params.optionalString("npc") ?: extras["npc"]?.stringValue
        val blueprint = Blueprint(game.npcId(requestedId), saved.persona)
        blueprint.memory = saved.memory
        blueprint.history = saved.transcript

        if (params.hasKey("tools")) {
            blueprint.toolDefinitions = toolDefinitions(params["tools"], "tools")
        } else {
            blueprint.toolDefinitions = toolDefinitions(extras["tools"], "state.tools")
            blueprint.toolsPath = "state.tools"
        }

        val fromParams = params.hasKey("options")
        val options = GameCoding.npcOptions(if (fromParams) params["options"] else extras["options"], if (fromParams) "options" else "state.options")
        blueprint.options = options.options
        blueprint.toolTimeoutSeconds = options.toolTimeoutSeconds
        warnings += options.warnings

        if (params.hasKey("world")) {
            blueprint.worldId = params.optionalString("world")
        } else {
            extras["world"]?.stringValue?.let { savedWorld ->
                if (game.world(savedWorld) != null) {
                    blueprint.worldId = savedWorld
                } else {
                    warnings += "The saved world '$savedWorld' does not exist; the NPC was restored without a world. Pass 'world' to attach one."
                }
            }
        }
        blueprint.model = BridgeCoding.modelSpec(params["model"])
        return build(blueprint, request, game, warnings)
    }

    private fun toolDefinitions(value: JsonElement?, path: String): List<JsonElement> {
        if (value == null || value is JsonNull) return emptyList()
        return value.arrayValue ?: throw BridgeError.invalidParams("'$path' must be an array of tool definitions.")
    }

    private suspend fun build(blueprint: Blueprint, request: BridgeRequest, game: GameExtension, initial: List<String>): BridgeReply {
        val engine = request.engine
        val warnings = initial.toMutableList()
        val seconds = blueprint.toolTimeoutSeconds
        val toolTimeout = if (seconds != null) BridgeCoding.timeout(seconds) else engine.configuration.defaultToolTimeout
        val parsed = BridgeCoding.tools(JsonArray(blueprint.toolDefinitions), toolTimeout, blueprint.toolsPath)
        warnings += parsed.warnings

        val world: WorldState? = blueprint.worldId?.let { game.worldEntry(it).world }
        val model = engine.makeModel(blueprint.model)
        if (blueprint.model is BridgeModelSpec.System) warnings += SessionMethods.unavailableWarning(engine)

        val npc = try {
            NPC(
                persona = blueprint.persona,
                model = model,
                tools = parsed.tools,
                world = world,
                options = blueprint.options,
                memory = blueprint.memory,
                history = blueprint.history,
            )
        } catch (error: AgentError) {
            throw invalidParams(error)
        }
        val entry = NPCEntry(
            blueprint.id, npc, blueprint.worldId, blueprint.model.kind, blueprint.toolDefinitions, toolTimeout,
            engine.configuration.dispatcher,
        )
        val toolNames = entry.modelToolNames
        BridgeCoding.toolCountWarning(toolNames.size)?.let { warnings += it }
        try {
            game.insertNpc(entry)
        } catch (error: BridgeError) {
            entry.close()
            throw error
        }
        return BridgeReply.Result(obj("npc" to JsonPrimitive(entry.id), "tools" to strings(toolNames), "warnings" to strings(warnings)))
    }

    /** NPC configuration errors (`invalid_request`) are parameter errors here. */
    private fun invalidParams(error: AgentError): BridgeError =
        if (error.code == AgentErrorCode.INVALID_REQUEST) BridgeError.invalidParams(error.message) else BridgeError.from(error)

    // MARK: npc/talk

    private val TALK_KEYS = setOf("npc", "line", "context", "stream", "toolChoice")

    private fun talk(request: BridgeRequest, game: GameExtension): BridgeReply {
        val params = request.params
        val entry = game.npcEntry(params.string("npc"))
        val line = params.string("line")
        val context = GameCoding.text(params["context"])
        val stream = params.optionalBool("stream") ?: false
        // A tool named here is checked when the turn starts (an earlier queued npc/update may add it),
        // failing with invalid_request.
        val toolChoice: ToolChoice? = params["toolChoice"]?.let { BridgeCoding.toolChoice(it) }
        val warnings = params.unknownKeys(TALK_KEYS)
        val driver = DialogueDriver(request.engine, request.id?.value ?: JsonNull, obj("npc" to JsonPrimitive(entry.id)), stream)
        return entry.queue.schedule {
            val dialogue = entry.npc.talkStream(line, context, toolChoice)
            val turn = try {
                driver.drive(dialogue)
            } catch (error: Throwable) {
                // Report a failed or cancelled turn only once the NPC has rolled it back,
                // so the client never observes partial state.
                dialogue.cancel()
                withContext(NonCancellable) { entry.npc.waitUntilIdle() }
                throw error
            }
            SessionMethods.result("npc" to JsonPrimitive(entry.id), GameCoding.json(turn), warnings)
        }
    }

    // MARK: npc/bark

    private fun bark(request: BridgeRequest, game: GameExtension): BridgeReply {
        val entry = game.npcEntry(request.params.string("npc"))
        val situation = GameCoding.text(request.params["situation"]) ?: ""
        // Barks use a separate one-off request, so they do not wait for turns.
        return BridgeReply.Deferred {
            val line = entry.npc.bark(situation)
            obj("npc" to JsonPrimitive(entry.id), "line" to JsonPrimitive(line))
        }
    }

    // MARK: npc/state

    private fun state(request: BridgeRequest, game: GameExtension): BridgeReply {
        val entry = game.npcEntry(request.params.string("npc"))
        val settle = request.params.optionalBool("settle") ?: true
        fun result(saved: NPCSaveState): JsonObject {
            val state = LinkedHashMap(GameCoding.json(saved))
            state.putAll(entry.saveExtras)
            return obj("npc" to JsonPrimitive(entry.id), "state" to JsonObject(state))
        }
        if (!settle) return BridgeReply.Result(result(entry.npc.saveState()))
        // After this NPC's earlier requests, and after background compaction.
        return entry.queue.schedule { result(entry.npc.settledState()) }
    }

    // MARK: npc/update

    private val UPDATE_KEYS = setOf("npc", "persona", "options", "memory", "tools")

    /**
     * Merge-patches the persona, options and memory (RFC 7386: `null` resets
     * a field) and replaces the client tools. Applied in order with the NPC's
     * other requests; takes effect from the next turn. All or nothing.
     */
    private fun update(request: BridgeRequest, game: GameExtension): BridgeReply {
        val params = request.params
        val entry = game.npcEntry(params.string("npc"))
        val personaPatch = params.optionalObject("persona")
        val optionsPatch = params.optionalObject("options")
        val memoryPatch = params.optionalObject("memory")
        val newDefinitions = if (params.hasKey("tools")) toolDefinitions(params["tools"], "tools") else null
        val defaultTimeout = request.engine.configuration.defaultToolTimeout
        val unknown = params.unknownKeys(UPDATE_KEYS)

        return entry.queue.schedule {
            val warnings = unknown.toMutableList()
            val npc = entry.npc
            val persona = personaPatch?.let { patch ->
                GameCoding.persona(GameCoding.merged(patch, GameCoding.json(npc.persona))).also { warnings += it.warnings }.value
            }
            val memory = memoryPatch?.let { GameCoding.memory(GameCoding.merged(it, GameCoding.json(npc.memory))) }
            var options: NPCOptions? = null
            var toolTimeout: Duration? = entry.toolTimeout
            if (optionsPatch != null) {
                val current = LinkedHashMap(GameCoding.json(npc.options))
                current["toolTimeoutSeconds"] = BridgeCoding.number(BridgeCoding.seconds(toolTimeout))
                val parsed = GameCoding.npcOptions(GameCoding.merged(optionsPatch, JsonObject(current)))
                options = parsed.options
                if ("toolTimeoutSeconds" in optionsPatch) {
                    val seconds = parsed.toolTimeoutSeconds
                    toolTimeout = if (seconds != null) BridgeCoding.timeout(seconds) else defaultTimeout
                }
                warnings += parsed.warnings
            }
            // Tools are rebuilt when replaced or when their time limit changed.
            val definitions = newDefinitions ?: entry.toolDefinitions
            val tools: List<AgentTool>? = if (newDefinitions != null || toolTimeout != entry.toolTimeout) {
                BridgeCoding.tools(JsonArray(definitions), toolTimeout).also { warnings += it.warnings }.tools
            } else {
                null
            }

            BridgeSession.commit()
            try {
                replaceToolsAndOptions(tools, options, npc)
            } catch (error: AgentError) {
                throw invalidParams(error)
            }
            persona?.let { npc.persona = it }
            memory?.let { npc.memory = it }
            entry.update(definitions, toolTimeout)
            obj("npc" to JsonPrimitive(entry.id), "warnings" to strings(warnings))
        }
    }

    /**
     * Replaces tools and options together. Each setter validates the grounding
     * tool against the other half, so when both change, the grounding is
     * cleared first; on failure the previous configuration is restored.
     */
    private fun replaceToolsAndOptions(tools: List<AgentTool>?, options: NPCOptions?, npc: NPC) {
        if (tools == null) {
            options?.let(npc::setOptions)
            return
        }
        if (options == null) {
            npc.setTools(tools)
            return
        }
        val previousTools = npc.tools
        val previousOptions = npc.options
        try {
            npc.setOptions(ungrounded(previousOptions))
            npc.setTools(tools)
            npc.setOptions(options)
        } catch (error: AgentError) {
            runCatching { npc.setOptions(ungrounded(previousOptions)) }
            runCatching { npc.setTools(previousTools) }
            runCatching { npc.setOptions(previousOptions) }
            throw error
        }
    }

    private fun ungrounded(options: NPCOptions): NPCOptions =
        options.copy(groundingTool = null, toolChoice = if (options.toolChoice is ToolChoice.Tool) ToolChoice.Auto else options.toolChoice)
}

/**
 * Bridges one NPC turn ([DialogueStream]) to the peer, like the session turn
 * driver: external tool calls become `tool/call` requests, streamed events
 * become `npc/event` notifications, and cancelling the calling coroutine
 * cancels the turn.
 */
internal class DialogueDriver(
    private val engine: BridgeEngine,
    requestId: JsonElement,
    context: JsonObject,
    private val stream: Boolean,
) {
    private val tools = ToolForwarder(engine, requestId, context)

    suspend fun drive(dialogue: DialogueStream): DialogueTurn {
        val outcome: Result<DialogueTurn> = try {
            var turn: DialogueTurn? = null
            dialogue.events.collect { event ->
                if (stream) GameCoding.json(event)?.let { engine.notify("npc/event", tools.params("event" to it)) }
                when (event) {
                    is DialogueEvent.ExternalToolCall -> tools.forward(event.call) { output, callId -> dialogue.submit(output, callId) }
                    // Still waiting on the peer means the call timed out.
                    is DialogueEvent.ToolResult -> tools.completed(event.record.call.id, event.record.output.modelText)
                    is DialogueEvent.Completed -> {
                        // The turn is in the NPC's history now: report it even if a cancellation arrives before the response.
                        WorkQueue.markCommitted()
                        turn = event.turn
                    }
                    else -> Unit
                }
            }
            turn?.let { Result.success(it) } ?: Result.failure(BridgeError.internalError("The dialogue turn ended without a reply."))
        } catch (_: CancellationException) {
            dialogue.cancel()
            Result.failure(BridgeError.cancelled("The turn was cancelled."))
        } catch (error: Throwable) {
            Result.failure(BridgeError.normalizing(error))
        }
        tools.finish()
        return outcome.getOrThrow()
    }
}
