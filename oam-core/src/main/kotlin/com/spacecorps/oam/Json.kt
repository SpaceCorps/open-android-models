package com.spacecorps.oam

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The JSON configuration used throughout open-android-models.
 *
 * JSON values are kotlinx.serialization [JsonElement]s. [JsonObject] keeps
 * the insertion order of its keys, and so does everything in this library,
 * because key order matters to a language model: it generates structured
 * output field by field, so a `reasoning` field placed before a `choice`
 * field acts as a small chain of thought.
 */
public val OamJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** An empty JSON object. */
public val EmptyJsonObject: JsonObject = JsonObject(emptyMap())

/** Serializes this value as compact JSON, keeping object key order. */
public fun JsonElement.toJsonString(): String = OamJson.encodeToString(JsonElement.serializer(), this)

/** Serializes this value as indented JSON, keeping object key order. */
public fun JsonElement.toPrettyJsonString(): String = PrettyJson.encodeToString(JsonElement.serializer(), this)

private val PrettyJson = Json(OamJson) { prettyPrint = true }

/** True for JSON `null`. */
public val JsonElement.isJsonNull: Boolean get() = this is JsonNull

/** The string, if this is a JSON string. */
public val JsonElement.stringValue: String?
    get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The boolean, if this is a JSON boolean. */
public val JsonElement.boolValue: Boolean?
    get() = (this as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.booleanOrNull

/** The number, if this is a JSON number. */
public val JsonElement.doubleValue: Double?
    get() = (this as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.doubleOrNull

/**
 * The number as a [Long], if this is a JSON number with an integral value
 * (so `3` and `3.0` both qualify, `3.5` does not).
 */
public val JsonElement.longValue: Long?
    get() {
        val primitive = (this as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull } ?: return null
        primitive.content.toLongOrNull()?.let { return it }
        val value = primitive.doubleOrNull ?: return null
        if (value.isNaN() || value.isInfinite() || value != Math.rint(value) || kotlin.math.abs(value) > MAX_SAFE_INTEGER) return null
        return value.toLong()
    }

/** The number as an [Int], if it is integral and in range. */
public val JsonElement.intValue: Int?
    get() = longValue?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

/** This value as an object, or `null`. */
public val JsonElement.objectValue: JsonObject? get() = this as? JsonObject

/** This value as an array, or `null`. */
public val JsonElement.arrayValue: JsonArray? get() = this as? JsonArray

/** True if this is a JSON number (not a numeric string). */
public val JsonElement.isNumber: Boolean
    get() = this is JsonPrimitive && !isString && this !is JsonNull && content != "true" && content != "false"

/** True if this is a JSON boolean. */
public val JsonElement.isBoolean: Boolean
    get() = this is JsonPrimitive && !isString && (content == "true" || content == "false")

private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991.0

/**
 * Converts plain Kotlin values into JSON: `null`, [Boolean], [Number],
 * [String], [Char], [Enum] (by name), [Map] (keys via `toString`, order
 * kept), [Iterable], [Array] and [JsonElement] (as is).
 *
 * ```kotlin
 * ToolOutput.Json(jsonOf(mapOf("item" to "ale", "price" to 3)))
 * ```
 *
 * @throws IllegalArgumentException for any other type.
 */
public fun jsonOf(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Boolean -> JsonPrimitive(value)
    is Int, is Long, is Short, is Byte -> JsonPrimitive((value as Number).toLong())
    is Float -> JsonPrimitive(value.toDouble())
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is Char -> JsonPrimitive(value.toString())
    is Enum<*> -> JsonPrimitive(value.name)
    is Map<*, *> -> JsonObject(value.entries.associateTo(LinkedHashMap()) { (key, item) -> key.toString() to jsonOf(item) })
    is Iterable<*> -> JsonArray(value.map(::jsonOf))
    is Array<*> -> JsonArray(value.map(::jsonOf))
    else -> throw IllegalArgumentException("Cannot convert ${value::class.qualifiedName} to JSON.")
}

/** Builds a JSON object from pairs, keeping their order. Values go through [jsonOf]. */
public fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JsonObject =
    JsonObject(pairs.associateTo(LinkedHashMap()) { (key, value) -> key to jsonOf(value) })
