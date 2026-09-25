package com.spacecorps.oam.bridge

import com.spacecorps.oam.OamJson
import com.spacecorps.oam.stringValue
import com.spacecorps.oam.toJsonString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * A JSON-RPC request identifier: a string or a number, echoed back exactly as
 * the peer sent it.
 *
 * Numbers travel as doubles on many peers, which hold integers exactly only up
 * to 2^53 in magnitude, so larger (or non-finite) numeric ids are rejected
 * ([of] returns `null` and the bridge answers `-32600`) instead of being
 * echoed back altered. Integral ids are always written as plain digits.
 *
 * @property value The id as it goes on the wire: a JSON string or number.
 */
public class JsonRpcId private constructor(public val value: JsonPrimitive) {
    /** The string id, or the number's digits. */
    override fun toString(): String = value.content

    override fun equals(other: Any?): Boolean = other is JsonRpcId && other.value == value

    override fun hashCode(): Int = value.hashCode()

    public companion object {
        /** The largest magnitude a numeric id may have: 2^53 - 1. */
        public const val MAX_EXACT_INTEGER: Long = 9_007_199_254_740_991L

        /** A string id. */
        public fun of(id: String): JsonRpcId = JsonRpcId(JsonPrimitive(id))

        /** A numeric id; `null` if its magnitude exceeds [MAX_EXACT_INTEGER]. */
        public fun of(id: Long): JsonRpcId? = if (id in -MAX_EXACT_INTEGER..MAX_EXACT_INTEGER) JsonRpcId(JsonPrimitive(id)) else null

        /**
         * Wraps a JSON id: a string, or a finite number of magnitude at most
         * [MAX_EXACT_INTEGER]. Integral numbers are normalized to plain
         * digits (`7.0` and `7e0` become `7`). Returns `null` for anything else.
         */
        public fun of(element: JsonElement): JsonRpcId? {
            val primitive = element as? JsonPrimitive ?: return null
            if (primitive is JsonNull) return null
            if (primitive.isString) return JsonRpcId(primitive)
            val number = JsonText.number(primitive.content) ?: return null
            if (number.abs() > MAX_EXACT_BIG) return null
            return try {
                JsonRpcId(JsonPrimitive(number.longValueExact()))
            } catch (_: ArithmeticException) {
                val double = number.toDouble()
                if (double.isFinite()) JsonRpcId(JsonPrimitive(double)) else null
            }
        }

        private val MAX_EXACT_BIG = BigDecimal.valueOf(MAX_EXACT_INTEGER)

        /** Why [element] is not a valid id, for the `-32600` error message. */
        internal fun rejectionReason(element: JsonElement): String {
            val primitive = element as? JsonPrimitive
            if (primitive == null || primitive is JsonNull || primitive.isString || JsonText.number(primitive.content) == null) {
                return "'id' must be a string or a number."
            }
            return "A numeric 'id' must be an integer of magnitude at most 2^53-1 (9007199254740991) so it can be echoed exactly; use a string id."
        }
    }
}

/** Builders for single-line JSON-RPC 2.0 messages. */
public object JsonRpcMessage {
    /** A success response. */
    public fun result(id: JsonRpcId, result: JsonElement): String =
        JsonObject(linkedMapOf("jsonrpc" to VERSION, "id" to id.value, "result" to result)).toJsonString()

    /** An error response; `id` is `null` when the request's id could not be read. */
    public fun error(id: JsonRpcId?, error: BridgeError): String =
        JsonObject(linkedMapOf("jsonrpc" to VERSION, "id" to (id?.value ?: JsonNull), "error" to error.toJson())).toJsonString()

    /** A notification (no id, no response). */
    public fun notification(method: String, params: JsonElement): String =
        JsonObject(linkedMapOf("jsonrpc" to VERSION, "method" to JsonPrimitive(method), "params" to params)).toJsonString()

    /** A request from the bridge to the peer (such as `tool/call`). */
    public fun request(id: JsonRpcId, method: String, params: JsonElement): String =
        JsonObject(linkedMapOf("jsonrpc" to VERSION, "id" to id.value, "method" to JsonPrimitive(method), "params" to params)).toJsonString()

    private val VERSION = JsonPrimitive("2.0")
}

/** Strict JSON text handling for the wire. */
internal object JsonText {
    private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    /**
     * Parses one JSON document. kotlinx.serialization's tree parser accepts
     * bare words (`{"a": hello}`) as unquoted literals; the wire is strict
     * JSON, so those are rejected here.
     *
     * @throws IllegalArgumentException with a readable message if [text] is not valid JSON.
     */
    fun parse(text: String): JsonElement {
        val element = try {
            OamJson.parseToJsonElement(text)
        } catch (error: SerializationException) {
            throw IllegalArgumentException("Parse error: ${error.message?.lineSequence()?.firstOrNull() ?: "invalid JSON"}", error)
        }
        requireStrict(element)
        return element
    }

    /** The exact value of a JSON number literal, or `null` if [literal] is not one. */
    fun number(literal: String): BigDecimal? {
        if (!NUMBER.matches(literal)) return null
        return try {
            BigDecimal(literal)
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun requireStrict(element: JsonElement) {
        when (element) {
            is JsonObject -> element.values.forEach(::requireStrict)
            is JsonArray -> element.forEach(::requireStrict)
            is JsonNull -> Unit
            is JsonPrimitive -> if (!element.isString) {
                val content = element.content
                if (content != "true" && content != "false" && !NUMBER.matches(content)) {
                    throw IllegalArgumentException("Parse error: '$content' is not a JSON value (strings must be quoted).")
                }
            }
        }
    }

    /** A short rendering of [value] for error messages. */
    fun shown(value: JsonElement): String {
        val text = value.stringValue?.let { JsonPrimitive(it).toString() } ?: value.toJsonString()
        return if (text.length > 80) text.take(77) + "..." else text
    }
}
