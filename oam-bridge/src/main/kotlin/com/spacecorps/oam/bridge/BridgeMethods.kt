package com.spacecorps.oam.bridge

import kotlinx.serialization.json.JsonElement

/**
 * What a method handler returns.
 *
 * Handlers run one at a time, in the order messages arrive, so a client can
 * pipeline `session/create` and `session/respond` without waiting. That
 * makes the handler itself the place for validation and anything
 * order-sensitive; slow work (model turns, waiting for other turns) must be
 * returned as [Deferred] so the next message can be processed.
 */
public sealed interface BridgeReply {
    /** The result, ready now. */
    public class Result(public val value: JsonElement) : BridgeReply

    /**
     * Work that finishes later. It runs concurrently with other requests; its
     * result (or thrown error) becomes the response. Cancelled on `shutdown`
     * and [BridgeEngine.close], and when an in-process [BridgeEngine.call] is cancelled.
     */
    public class Deferred(public val work: suspend () -> JsonElement) : BridgeReply
}

/**
 * A method implementation. Errors are converted with
 * [BridgeError.normalizing]: throw a [BridgeError] for exact control, or any
 * error (for example an [com.spacecorps.oam.AgentError]) for automatic mapping.
 */
public typealias BridgeMethodHandler = suspend (BridgeRequest) -> BridgeReply

/**
 * The table of methods a [BridgeEngine] serves.
 *
 * ```kotlin
 * registry.register("quest/narrate") { request ->
 *     val quest = store.quest(request.params.string("quest"))
 *     val run = quest.agent.run(request.params.string("text"))   // queued in arrival order
 *     val stream = request.params.optionalBool("stream") ?: false
 *     BridgeReply.Deferred {
 *         val response = request.drive(run, stream, jsonObjectOf("quest" to quest.id), eventMethod = "quest/event")
 *         JsonObject(mapOf("quest" to JsonPrimitive(quest.id)) + BridgeCoding.json(response))
 *     }
 * }
 * ```
 *
 * Not thread-safe on its own: extensions fill it during [BridgeExtension.register],
 * and [BridgeEngine.register] guards later changes.
 */
public class BridgeMethodRegistry {
    private val handlers = HashMap<String, BridgeMethodHandler>()

    /** Registers (or replaces) a method. Names use `namespace/verb`. */
    public fun register(method: String, handler: BridgeMethodHandler) {
        require(method.isNotEmpty()) { "Method names must not be empty." }
        handlers[method] = handler
    }

    /** Removes a method. */
    public fun unregister(method: String) {
        handlers.remove(method)
    }

    /** Registered method names, sorted. */
    public val methods: List<String> get() = handlers.keys.sorted()

    /** The handler for [method], or `null`. */
    public fun handler(method: String): BridgeMethodHandler? = handlers[method]
}

/**
 * A set of methods plugged into a [BridgeEngine] through
 * [BridgeConfiguration.extensions] (the game methods are one: [GameExtension]).
 *
 * Extensions keep their own state (guard it; handlers and deferred work run
 * on several threads) and reuse the engine's helpers: [drive] runs an agent
 * turn with streaming and client tools, [BridgeCoding] parses and encodes the
 * shared JSON shapes, and [BridgeEngine.makeModel] resolves `model` parameters.
 *
 * The engine owns its extensions; an extension instance belongs to one engine.
 */
public interface BridgeExtension {
    /**
     * Registers the extension's methods. Called once, while the engine is
     * constructed. Built-in methods are registered first, so an extension may
     * override them.
     */
    public fun register(registry: BridgeMethodRegistry, engine: BridgeEngine)

    /**
     * Notification methods the extension sends (for example `npc/event`),
     * listed in `initialize`'s `capabilities.notifications`.
     */
    public val notificationMethods: List<String> get() = emptyList()

    /** Cancels the extension's work. Called on `shutdown` and when the engine closes. */
    public suspend fun shutdown() {}
}

/**
 * One incoming request or notification, as seen by a method handler.
 *
 * @property engine The engine serving the request.
 * @property method The method name.
 * @property id The request id; `null` for notifications (no response is sent).
 * @property params The by-name parameters (empty when absent).
 */
public class BridgeRequest(
    public val engine: BridgeEngine,
    public val method: String,
    public val id: JsonRpcId?,
    public val params: BridgeParams,
) {
    /** Whether the peer expects no response. */
    public val isNotification: Boolean get() = id == null

    /** Sends a notification to the peer. */
    public fun notify(method: String, params: JsonElement) {
        engine.notify(method, params)
    }
}
