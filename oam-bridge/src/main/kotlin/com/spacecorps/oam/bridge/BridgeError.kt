package com.spacecorps.oam.bridge

import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentErrorCode
import com.spacecorps.oam.InvalidSchemaException
import com.spacecorps.oam.intValue
import com.spacecorps.oam.objectValue
import com.spacecorps.oam.stringValue
import com.spacecorps.oam.game.WorldStateError
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.time.Duration

/**
 * A JSON-RPC error object: thrown by method handlers and returned to peers.
 *
 * Every error carries `data.code`, a stable snake_case string (for
 * application errors, the [AgentErrorCode.wireName]), so clients can branch
 * without memorizing numeric codes. Codes and names are identical to
 * open-apple-models' bridge (see `docs/PROTOCOL.md`, section 8).
 *
 * @property code The numeric JSON-RPC error code.
 * @property data Extra information; always an object containing at least `code` for errors this library creates.
 */
public class BridgeError(
    public val code: Int,
    message: String,
    public val data: JsonObject? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    override val message: String get() = super.message ?: ""

    /** The string code in `data.code`, if any. */
    public val name: String? get() = data?.get("code")?.stringValue

    /** The JSON-RPC `error` member: `{"code", "message", "data"?}`. */
    public fun toJson(): JsonObject {
        val members = linkedMapOf<String, JsonElement>("code" to JsonPrimitive(code), "message" to JsonPrimitive(message))
        data?.let { members["data"] = it }
        return JsonObject(members)
    }

    override fun toString(): String = "$code ${name ?: "error"}: $message"

    public companion object {
        // JSON-RPC 2.0

        /** The line is not valid JSON. */
        public const val PARSE_ERROR: Int = -32700

        /** Not a JSON-RPC 2.0 message (`invalid_message`). */
        public const val INVALID_REQUEST: Int = -32600

        /** Unknown method. */
        public const val METHOD_NOT_FOUND: Int = -32601

        /** A missing or mistyped parameter. */
        public const val INVALID_PARAMS: Int = -32602

        /** A bug. */
        public const val INTERNAL_ERROR: Int = -32603

        // Mapped from AgentErrorCode

        /** [AgentErrorCode.MODEL_UNAVAILABLE]. */
        public const val MODEL_UNAVAILABLE: Int = -32001

        /** [AgentErrorCode.GUARDRAIL_VIOLATION]. */
        public const val GUARDRAIL_VIOLATION: Int = -32002

        /** [AgentErrorCode.REFUSAL]. */
        public const val REFUSAL: Int = -32003

        /** [AgentErrorCode.CONTEXT_SIZE_EXCEEDED]. */
        public const val CONTEXT_SIZE_EXCEEDED: Int = -32004

        /** [AgentErrorCode.RATE_LIMITED]. */
        public const val RATE_LIMITED: Int = -32005

        /** [AgentErrorCode.UNSUPPORTED_LANGUAGE]. */
        public const val UNSUPPORTED_LANGUAGE: Int = -32006

        /** [AgentErrorCode.INVALID_SCHEMA]. */
        public const val INVALID_SCHEMA: Int = -32007

        /** [AgentErrorCode.TOOL_FAILED]. */
        public const val TOOL_FAILED: Int = -32008

        /** [AgentErrorCode.CANCELLED]. */
        public const val CANCELLED: Int = -32009

        /** [AgentErrorCode.BUSY]. */
        public const val BUSY: Int = -32010

        /** [AgentErrorCode.INVALID_REQUEST] (the agent rejected the input). */
        public const val AGENT_INVALID_REQUEST: Int = -32011

        /** [AgentErrorCode.GENERATION_FAILED]. */
        public const val GENERATION_FAILED: Int = -32012

        // Bridge

        /** No session has the id (`session_not_found`, `data.session`). */
        public const val SESSION_NOT_FOUND: Int = -32020

        /** `session/create` with an id in use (`session_exists`). */
        public const val SESSION_EXISTS: Int = -32021

        /** Too many sessions (`session_limit`, `data.limit`). */
        public const val SESSION_LIMIT: Int = -32022

        /** After `shutdown` or [BridgeEngine.close] (`shut_down`). */
        public const val SHUT_DOWN: Int = -32023

        /** A blocking call timed out (`timeout`); reserved for hosts, as on Apple's C ABI. */
        public const val TIMEOUT: Int = -32024

        // Game methods

        /** No NPC has the id (`npc_not_found`, `data.npc`). */
        public const val NPC_NOT_FOUND: Int = -32050

        /** `npc/create` or `npc/restore` with an id in use (`npc_exists`). */
        public const val NPC_EXISTS: Int = -32051

        /** No world has the id (`world_not_found`, `data.world`). */
        public const val WORLD_NOT_FOUND: Int = -32052

        /** `world/create` with an id in use (`world_exists`). */
        public const val WORLD_EXISTS: Int = -32053

        /** A world path or value was rejected (`world_error`, `data.world`, `data.path`). */
        public const val WORLD_ERROR: Int = -32054

        /** Too many NPCs, worlds or subscriptions (`limit_reached`, `data.limit`). */
        public const val LIMIT_REACHED: Int = -32055

        /** No world subscription has the id (`subscription_not_found`). */
        public const val SUBSCRIPTION_NOT_FOUND: Int = -32056

        /** Creates an error whose `data` is `{"code": name, …extra}`. */
        public fun named(code: Int, name: String, message: String, extra: Map<String, JsonElement> = emptyMap(), cause: Throwable? = null): BridgeError {
            val data = linkedMapOf<String, JsonElement>("code" to JsonPrimitive(name))
            data.putAll(extra)
            return BridgeError(code, message, JsonObject(data), cause)
        }

        /** The numeric code for an [AgentErrorCode]. */
        public fun codeFor(agentCode: AgentErrorCode): Int = when (agentCode) {
            AgentErrorCode.MODEL_UNAVAILABLE -> MODEL_UNAVAILABLE
            AgentErrorCode.GUARDRAIL_VIOLATION -> GUARDRAIL_VIOLATION
            AgentErrorCode.REFUSAL -> REFUSAL
            AgentErrorCode.CONTEXT_SIZE_EXCEEDED -> CONTEXT_SIZE_EXCEEDED
            AgentErrorCode.RATE_LIMITED -> RATE_LIMITED
            AgentErrorCode.UNSUPPORTED_LANGUAGE -> UNSUPPORTED_LANGUAGE
            AgentErrorCode.INVALID_SCHEMA -> INVALID_SCHEMA
            AgentErrorCode.TOOL_FAILED -> TOOL_FAILED
            AgentErrorCode.CANCELLED -> CANCELLED
            AgentErrorCode.BUSY -> BUSY
            AgentErrorCode.INVALID_REQUEST -> AGENT_INVALID_REQUEST
            AgentErrorCode.GENERATION_FAILED -> GENERATION_FAILED
        }

        /** `-32700 parse_error`. */
        public fun parseError(message: String): BridgeError = named(PARSE_ERROR, "parse_error", message)

        /** `-32600 invalid_message`. */
        public fun invalidRequest(message: String): BridgeError = named(INVALID_REQUEST, "invalid_message", message)

        /** `-32601 method_not_found` with `data.method`. */
        public fun methodNotFound(method: String): BridgeError =
            named(METHOD_NOT_FOUND, "method_not_found", "Unknown method '$method'.", mapOf("method" to JsonPrimitive(method)))

        /** `-32602 invalid_params`; the message should name the parameter. */
        public fun invalidParams(message: String): BridgeError = named(INVALID_PARAMS, "invalid_params", message)

        /** `-32603 internal_error`. */
        public fun internalError(message: String): BridgeError = named(INTERNAL_ERROR, "internal_error", message)

        /** `-32020 session_not_found`. */
        public fun sessionNotFound(id: String): BridgeError =
            named(SESSION_NOT_FOUND, "session_not_found", "No session with id '$id'.", mapOf("session" to JsonPrimitive(id)))

        /** `-32021 session_exists`. */
        public fun sessionExists(id: String): BridgeError =
            named(SESSION_EXISTS, "session_exists", "A session with id '$id' already exists.", mapOf("session" to JsonPrimitive(id)))

        /** `-32022 session_limit`. */
        public fun sessionLimitReached(limit: Int): BridgeError = named(
            SESSION_LIMIT, "session_limit", "The session limit ($limit) is reached; delete a session first.",
            mapOf("limit" to JsonPrimitive(limit)),
        )

        /** `-32023 shut_down`. */
        public fun shutDown(): BridgeError = named(SHUT_DOWN, "shut_down", "The bridge has shut down.")

        /** `-32024 timeout`. */
        public fun timeout(message: String): BridgeError = named(TIMEOUT, "timeout", message)

        /** `-32009 cancelled`. */
        public fun cancelled(message: String = "The request was cancelled."): BridgeError = from(AgentError(AgentErrorCode.CANCELLED, message))

        /** `-32050 npc_not_found`. */
        public fun npcNotFound(id: String): BridgeError =
            named(NPC_NOT_FOUND, "npc_not_found", "No NPC with id '$id'.", mapOf("npc" to JsonPrimitive(id)))

        /** `-32051 npc_exists`. */
        public fun npcExists(id: String): BridgeError =
            named(NPC_EXISTS, "npc_exists", "An NPC with id '$id' already exists.", mapOf("npc" to JsonPrimitive(id)))

        /** `-32052 world_not_found`. */
        public fun worldNotFound(id: String): BridgeError =
            named(WORLD_NOT_FOUND, "world_not_found", "No world with id '$id'.", mapOf("world" to JsonPrimitive(id)))

        /** `-32053 world_exists`. */
        public fun worldExists(id: String): BridgeError =
            named(WORLD_EXISTS, "world_exists", "A world with id '$id' already exists.", mapOf("world" to JsonPrimitive(id)))

        /** `-32054 world_error` for a [WorldStateError] raised by an operation on world [world]. */
        public fun worldError(error: WorldStateError, world: String): BridgeError = named(
            WORLD_ERROR, "world_error", error.message,
            mapOf("world" to JsonPrimitive(world), "path" to JsonPrimitive(error.path)), error,
        )

        /** `-32055 limit_reached`; [kind] is a plural noun such as `"NPCs"`. */
        public fun limitReached(kind: String, limit: Int): BridgeError = named(
            LIMIT_REACHED, "limit_reached", "The limit of $limit $kind is reached; delete some first.",
            mapOf("limit" to JsonPrimitive(limit)),
        )

        /** `-32056 subscription_not_found`. */
        public fun subscriptionNotFound(id: String): BridgeError = named(
            SUBSCRIPTION_NOT_FOUND, "subscription_not_found", "No world subscription with id '$id'.",
            mapOf("subscription" to JsonPrimitive(id)),
        )

        /** `-32007 invalid_schema` for a malformed schema at parameter [path]; `data.schemaPath` points into the schema. */
        public fun invalidSchema(error: InvalidSchemaException, path: String): BridgeError = named(
            INVALID_SCHEMA, AgentErrorCode.INVALID_SCHEMA.wireName, "$path: ${error.reason} (at ${error.path})",
            mapOf("path" to JsonPrimitive(path), "schemaPath" to JsonPrimitive(error.path)), error,
        )

        /**
         * Maps an [AgentError] to its application code. A known
         * [AgentError.retryAfter] becomes `data.retryAfter` (ISO 8601) and
         * `data.retryAfterSeconds`.
         */
        public fun from(error: AgentError): BridgeError {
            val extra = linkedMapOf<String, JsonElement>()
            error.retryAfter?.let { wait -> retryData(wait, extra) }
            return named(codeFor(error.code), error.code.wireName, error.message, extra, error)
        }

        private fun retryData(wait: Duration, into: MutableMap<String, JsonElement>) {
            val seconds = wait.inWholeMilliseconds.coerceAtLeast(0) / 1000.0
            val instant = Instant.now().plusMillis(wait.inWholeMilliseconds.coerceAtLeast(0)).truncatedTo(ChronoUnit.SECONDS)
            into["retryAfter"] = JsonPrimitive(instant.toString())
            into["retryAfterSeconds"] = JsonPrimitive(ceil(seconds).toLong())
        }

        /**
         * Normalizes anything a handler throws: a [BridgeError] as is, an
         * [AgentError] by [from], a malformed schema as `invalid_schema`,
         * cancellation as `cancelled`, malformed JSON input and failed
         * argument checks as `invalid_params`, and anything else as
         * `generation_failed` (as open-apple-models does).
         */
        public fun normalizing(error: Throwable): BridgeError = when (error) {
            is BridgeError -> error
            is AgentError -> from(error)
            is InvalidSchemaException -> named(
                INVALID_SCHEMA, AgentErrorCode.INVALID_SCHEMA.wireName, error.message ?: "Invalid schema.",
                mapOf("path" to JsonPrimitive(error.path)), error,
            )
            is CancellationException -> cancelled()
            is SerializationException -> invalidParams(error.message ?: "Invalid parameters.")
            is IllegalArgumentException -> invalidParams(error.message ?: "Invalid parameters.")
            else -> from(AgentError.from(error))
        }

        /** Parses a JSON-RPC `error` member received from a peer. */
        public fun fromJson(json: JsonElement): BridgeError {
            val obj = json.objectValue
            val code = obj?.get("code")?.intValue ?: INTERNAL_ERROR
            val message = obj?.get("message")?.stringValue ?: "Unknown error"
            return BridgeError(code, message, obj?.get("data")?.objectValue)
        }
    }
}
