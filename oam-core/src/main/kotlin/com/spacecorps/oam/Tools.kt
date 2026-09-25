package com.spacecorps.oam

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import java.security.SecureRandom
import kotlin.time.Duration

/**
 * A tool invocation chosen by the model.
 *
 * @property id OpenAI-style identifier (`call_` + 24 alphanumerics), unique per call.
 * @property name The tool's name.
 * @property arguments The arguments, already coerced and validated against the tool's schema.
 */
@Serializable
public data class ToolCall(
    public val id: String = newId(),
    public val name: String,
    public val arguments: JsonObject = EmptyJsonObject,
) {
    /**
     * The string argument [key].
     *
     * @throws ToolArgumentException if it is missing or not a string.
     */
    public fun string(key: String): String = arguments[key]?.stringValue ?: throw missing(key, "a string")

    /** The string argument [key], or `null` if it is absent or `null`. */
    public fun stringOrNull(key: String): String? = arguments[key]?.stringValue

    /**
     * The integer argument [key].
     *
     * @throws ToolArgumentException if it is missing or not an integer.
     */
    public fun int(key: String): Int = arguments[key]?.intValue ?: throw missing(key, "an integer")

    /**
     * The integer argument [key] as a [Long].
     *
     * @throws ToolArgumentException if it is missing or not an integer.
     */
    public fun long(key: String): Long = arguments[key]?.longValue ?: throw missing(key, "an integer")

    /**
     * The number argument [key].
     *
     * @throws ToolArgumentException if it is missing or not a number.
     */
    public fun double(key: String): Double = arguments[key]?.doubleValue ?: throw missing(key, "a number")

    /**
     * The boolean argument [key].
     *
     * @throws ToolArgumentException if it is missing or not a boolean.
     */
    public fun bool(key: String): Boolean = arguments[key]?.boolValue ?: throw missing(key, "a boolean")

    private fun missing(key: String, kind: String) =
        ToolArgumentException("Argument '$key' of tool '$name' must be $kind; got ${arguments[key]?.toJsonString() ?: "nothing"}.")

    public companion object {
        private val random = SecureRandom()
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        /** A new OpenAI-style call identifier. */
        public fun newId(): String = "call_" + String(CharArray(24) { ALPHABET[random.nextInt(ALPHABET.length)] })
    }
}

/**
 * Decodes the arguments into [T] with kotlinx.serialization.
 *
 * @throws ToolArgumentException if they do not fit [T].
 */
public inline fun <reified T> ToolCall.decode(): T = decodeWith(serializer())

/** Decodes the arguments with [deserializer]. @throws ToolArgumentException if they do not fit. */
public fun <T> ToolCall.decodeWith(deserializer: kotlinx.serialization.DeserializationStrategy<T>): T =
    try {
        OamJson.decodeFromJsonElement(deserializer, arguments)
    } catch (error: SerializationException) {
        throw ToolArgumentException("Arguments of tool '$name' do not fit: ${error.message}")
    } catch (error: IllegalArgumentException) {
        throw ToolArgumentException("Arguments of tool '$name' do not fit: ${error.message}")
    }

/** Thrown by [ToolCall] accessors. Tool errors are shown to the model, which may retry. */
public class ToolArgumentException(message: String) : IllegalArgumentException(message)

/** What a tool returns to the model. */
@Serializable
public sealed interface ToolOutput {
    /** The text the model sees. */
    public val modelText: String

    /** True for [Error]. */
    public val isError: Boolean get() = this is Error

    /** Plain text. */
    @Serializable
    @SerialName("text")
    public data class Text(public val text: String) : ToolOutput {
        override val modelText: String get() = text
    }

    /** Structured data, shown to the model as compact JSON. */
    @Serializable
    @SerialName("json")
    public data class Json(public val value: JsonElement) : ToolOutput {
        override val modelText: String get() = value.toJsonString()
    }

    /**
     * A failure the model sees and may recover from (for example by
     * apologizing, or by retrying with other arguments). It does not abort the turn.
     */
    @Serializable
    @SerialName("error")
    public data class Error(public val message: String) : ToolOutput {
        override val modelText: String get() = "Error: $message"
    }

    public companion object {
        /** JSON output from any value accepted by [jsonOf], or text for a [String]. */
        public fun of(value: Any?): ToolOutput = if (value is String) Text(value) else Json(jsonOf(value))

        /** Encodes [value] with kotlinx.serialization as JSON output. */
        public inline fun <reified T> encoding(value: T): ToolOutput = Json(OamJson.encodeToJsonElement(serializer<T>(), value))
    }
}

/**
 * A completed tool invocation.
 *
 * @property durationSeconds Wall-clock time spent in the tool, including waiting for an external host.
 */
@Serializable
public data class ToolRecord(
    public val call: ToolCall,
    public val output: ToolOutput,
    public val durationSeconds: Double,
)

/** A tool's public definition: what hosts and transcripts see. */
@Serializable
public data class ToolDefinition(
    public val name: String,
    public val description: String,
    public val parameters: JsonSchema = JsonSchema.empty,
) {
    /** The definition in OpenAI function format. */
    public val openAIDefinition: JsonObject
        get() = jsonObjectOf(
            "type" to "function",
            "function" to jsonObjectOf("name" to name, "description" to description, "parameters" to parameters.json),
        )
}

/** How a tool runs. */
public sealed interface ToolExecution {
    /** A suspend function in this process. Its exceptions become [ToolOutput.Error]. */
    public class Local(public val handler: suspend (ToolCall) -> ToolOutput) : ToolExecution

    /**
     * The host runs it (a game engine, a bridge client): the call is announced
     * as [AgentEvent.ToolCallRequested] and the turn waits for
     * [AgentRun.submit].
     */
    public data object External : ToolExecution
}

/**
 * A tool the model can call, defined at runtime.
 *
 * ```kotlin
 * val menu = AgentTool.local("check_menu", "Look up today's menu and prices.") {
 *     ToolOutput.of(mapOf("ale" to 3, "stew" to 5))
 * }
 * val order = AgentTool.external(
 *     "take_order", "Place an order for the player.",
 *     JsonSchema.obj("item" to JsonSchema.string(), "quantity" to JsonSchema.integer(minimum = 1, maximum = 10)),
 * )
 * ```
 *
 * @property name The tool name. Use short snake_case names; `respond` and
 *   `respond_directly` are reserved.
 * @property description What the tool does and when to use it (the model reads this).
 * @property parameters JSON Schema for the arguments, normally an object schema.
 * @property timeout Per-call time limit. For local tools the agent's
 *   [AgentConfiguration.toolTimeout] applies when this is `null`; external
 *   tools without a timeout wait until the host answers or the turn is cancelled.
 * @property execution Local handler or external.
 * @throws AgentError [AgentErrorCode.INVALID_REQUEST] for an empty or reserved name,
 *   [AgentErrorCode.INVALID_SCHEMA] for a malformed schema.
 */
public class AgentTool(
    public val name: String,
    public val description: String,
    public val parameters: JsonSchema = JsonSchema.empty,
    public val timeout: Duration? = null,
    public val execution: ToolExecution,
) {
    /** Warnings about schema keywords that are ignored. */
    public val schemaWarnings: List<String>

    init {
        if (name.isBlank() || name != name.trim()) throw AgentError(AgentErrorCode.INVALID_REQUEST, "Tool names must be non-empty and must not start or end with whitespace: '$name'.")
        if (name in RESERVED_NAMES) throw AgentError(AgentErrorCode.INVALID_REQUEST, "'$name' is reserved for the agent's respond action.")
        if (timeout != null && timeout.isNegative()) throw AgentError(AgentErrorCode.INVALID_REQUEST, "Tool '$name' has a negative timeout.")
        schemaWarnings = try {
            parameters.check().map { "$name: $it" }
        } catch (error: InvalidSchemaException) {
            throw AgentError(AgentErrorCode.INVALID_SCHEMA, "Tool '$name': ${error.message}", cause = error)
        }
    }

    /** True for [ToolExecution.External]. */
    public val isExternal: Boolean get() = execution is ToolExecution.External

    /** The public definition. */
    public val definition: ToolDefinition get() = ToolDefinition(name, description, parameters)

    /** The definition in OpenAI function format. */
    public val openAIDefinition: JsonObject get() = definition.openAIDefinition

    /** A copy with another [execution] (for example to turn a restored definition into a local tool). */
    public fun withExecution(execution: ToolExecution): AgentTool = AgentTool(name, description, parameters, timeout, execution)

    override fun toString(): String = "AgentTool($name${if (isExternal) ", external" else ""})"

    public companion object {
        /** The envelope action that means "reply without a tool". */
        public const val RESPOND_ACTION: String = "respond"

        /** Names user tools may not use. */
        public val RESERVED_NAMES: Set<String> = setOf(RESPOND_ACTION, "respond_directly")

        /** A tool that runs [handler] in-process. */
        public fun local(
            name: String,
            description: String,
            parameters: JsonSchema = JsonSchema.empty,
            timeout: Duration? = null,
            handler: suspend (ToolCall) -> ToolOutput,
        ): AgentTool = AgentTool(name, description, parameters, timeout, ToolExecution.Local(handler))

        /** A tool the host runs (see [ToolExecution.External]). */
        public fun external(
            name: String,
            description: String,
            parameters: JsonSchema = JsonSchema.empty,
            timeout: Duration? = null,
        ): AgentTool = AgentTool(name, description, parameters, timeout, ToolExecution.External)

        /** A tool built from a [definition], run by the host. */
        public fun external(definition: ToolDefinition, timeout: Duration? = null): AgentTool =
            AgentTool(definition.name, definition.description, definition.parameters, timeout, ToolExecution.External)

        /**
         * A local tool whose handler receives arguments decoded into [A] with
         * kotlinx.serialization.
         */
        public inline fun <reified A> typed(
            name: String,
            description: String,
            parameters: JsonSchema,
            timeout: Duration? = null,
            noinline handler: suspend (A) -> ToolOutput,
        ): AgentTool = typedWith(name, description, parameters, serializer(), timeout, handler)

        /** Like [typed] with an explicit [deserializer]. */
        public fun <A> typedWith(
            name: String,
            description: String,
            parameters: JsonSchema,
            deserializer: KSerializer<A>,
            timeout: Duration? = null,
            handler: suspend (A) -> ToolOutput,
        ): AgentTool = local(name, description, parameters, timeout) { call -> handler(call.decodeWith(deserializer)) }
    }
}

/** Shorthand for `ToolOutput.Text`. */
public fun String.toToolOutput(): ToolOutput = ToolOutput.Text(this)
