package com.spacecorps.oam

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A JSON Schema document describing tool arguments or structured output.
 *
 * Supported keywords (the subset used by OpenAI-style tool definitions and
 * structured outputs, plus a few cheap extras):
 *
 * - `type` (a name or an array of names, including `"null"`) and OpenAPI's `nullable: true`
 * - `object`: `properties` (order is kept), `required`, `additionalProperties` (boolean or schema)
 * - `string`: `enum`, `const`, `pattern`, `minLength`, `maxLength`
 * - `integer` / `number`: `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`
 * - `boolean`
 * - `array`: `items`, `minItems`, `maxItems`
 * - `enum` and `const` of any type
 * - `anyOf`, `oneOf`, `allOf`
 * - local `$ref` (`#`, `#/$defs/…`, `#/definitions/…`, any JSON pointer into the document)
 *
 * Other keywords (`format`, `multipleOf`, `uniqueItems`, …) are ignored and
 * reported by [check]. `title`, `description`, `default` and `examples` are
 * shown to the model but never enforced.
 *
 * The on-device model has no constrained decoding for runtime schemas, so
 * output is generated from a prompt, then [coerce]d (safe fixes such as
 * `"3"` → `3` or `"Ale"` → `"ale"`), [validate]d, and repaired once by
 * re-prompting with the violations.
 *
 * Instances are immutable; equality is by JSON content.
 */
@Serializable(with = JsonSchemaSerializer::class)
public class JsonSchema(public val json: JsonObject) {
    /** True for an object schema without properties (a tool that takes no arguments). */
    public val isEmptyObject: Boolean
        get() = (json["type"]?.stringValue == "object" || json["type"] == null) &&
            (json["properties"] as? JsonObject).isNullOrEmpty() &&
            json.keys.none { it in COMPOSITION_KEYWORDS || it == "\$ref" || it == "enum" || it == "const" || it == "items" }

    /** Property names of an object schema, in declaration order. */
    public val propertyNames: List<String>
        get() = (json["properties"] as? JsonObject)?.keys?.toList().orEmpty()

    /** The `description`, if any. */
    public val description: String? get() = json["description"]?.stringValue

    /**
     * Checks that this schema is usable.
     *
     * @return Warnings about keywords that are ignored.
     * @throws InvalidSchemaException if the schema is malformed (unknown
     *   type, unresolvable `$ref`, a non-array `required`, an invalid
     *   `pattern`, …).
     */
    public fun check(): List<String> = SchemaChecker(json).run()

    /**
     * Validates [value] against this schema.
     *
     * @return The violations, empty when the value is valid. Each names the
     *   JSON path of the problem, for example
     *   `$.quantity: must be at most 10; got 12`.
     */
    public fun validate(value: JsonElement): List<SchemaViolation> = SchemaEngine(json).validate(value)

    /** True if [value] satisfies this schema. */
    public fun accepts(value: JsonElement): Boolean = validate(value).isEmpty()

    /**
     * Applies safe fixes that make near-miss model output valid, without
     * guessing: numeric strings where numbers are expected, integral floats
     * where integers are expected, `"true"`/`"false"` strings for booleans,
     * numbers and booleans where strings are expected, JSON text where an
     * object is expected, case- and separator-insensitive `enum`/`const`
     * matches, a single value where an array is expected, `null` for an
     * optional non-nullable property (removed), property names that differ
     * only in case or separators (`itemName` → `item_name`), and unknown
     * properties when `additionalProperties` is `false` (removed).
     *
     * The result may still be invalid; call [validate] afterwards.
     */
    public fun coerce(value: JsonElement): JsonElement = SchemaEngine(json).coerce(value)

    /**
     * Returns a copy of [value] whose object keys follow the property order
     * declared by this schema (recursively). Unknown keys keep their relative
     * order after the declared ones.
     */
    public fun order(value: JsonElement): JsonElement = SchemaEngine(json).order(value)

    /**
     * A compact, TypeScript-like rendering for prompts, for example
     * `{"item": string (menu item), "quantity": integer 1-10, "size"?: "small" | "large"}`.
     * Optional properties are marked with `?`.
     */
    public fun render(): String = SchemaEngine(json).render()

    /** Returns a copy with `description` set. */
    public fun described(description: String): JsonSchema = with("description", JsonPrimitive(description))

    /** Returns a copy that also accepts `null`. */
    public fun nullable(): JsonSchema {
        val type = json["type"]
        return when {
            type is JsonPrimitive && type.isString -> with("type", JsonArray(listOf(type, JsonPrimitive("null"))))
            type is JsonArray -> if (type.any { it.stringValue == "null" }) this else with("type", JsonArray(type + JsonPrimitive("null")))
            else -> JsonSchema(JsonObject(mapOf("anyOf" to JsonArray(listOf(json, NULL_SCHEMA)))))
        }
    }

    /** Returns a copy with [definitions] added under `$defs`, for `$ref`s created with [ref]. */
    public fun withDefinitions(definitions: Map<String, JsonSchema>): JsonSchema {
        val existing = (json["\$defs"] as? JsonObject).orEmpty()
        return with("\$defs", JsonObject(existing + definitions.mapValues { it.value.json }))
    }

    private fun with(key: String, value: JsonElement): JsonSchema = JsonSchema(JsonObject(json + (key to value)))

    override fun equals(other: Any?): Boolean = other is JsonSchema && other.json == json

    override fun hashCode(): Int = json.hashCode()

    /** The schema as compact JSON. */
    override fun toString(): String = json.toJsonString()

    /** Builders. Object schemas are closed (`additionalProperties: false`) and list every property as required unless told otherwise. */
    public companion object {
        internal val NULL_SCHEMA = JsonObject(mapOf("type" to JsonPrimitive("null")))
        internal val COMPOSITION_KEYWORDS = setOf("anyOf", "oneOf", "allOf")

        /** An object schema with no properties: a tool that takes no arguments. */
        public val empty: JsonSchema = obj()

        /** A schema that accepts any value. */
        public val any: JsonSchema = JsonSchema(EmptyJsonObject)

        /**
         * Parses a schema from JSON text.
         *
         * @throws InvalidSchemaException if the text is not a JSON object.
         */
        public fun parse(text: String): JsonSchema {
            val element = try {
                OamJson.parseToJsonElement(text)
            } catch (error: SerializationException) {
                throw InvalidSchemaException("#", "the schema is not valid JSON: ${error.message}")
            }
            return from(element)
        }

        /**
         * Wraps a JSON value as a schema. `true` becomes [any].
         *
         * @throws InvalidSchemaException if [element] is not an object or `true`.
         */
        public fun from(element: JsonElement): JsonSchema = when {
            element is JsonObject -> JsonSchema(element)
            element.boolValue == true -> any
            else -> throw InvalidSchemaException("#", "a schema must be a JSON object")
        }

        /**
         * An object schema. Properties are generated in the order given.
         *
         * @param required Required property names; `null` means all of them.
         * @param additionalProperties Whether other keys are allowed.
         */
        public fun obj(
            vararg properties: Pair<String, JsonSchema>,
            required: List<String>? = null,
            description: String? = null,
            title: String? = null,
            additionalProperties: Boolean = false,
        ): JsonSchema = obj(linkedMapOf(*properties), required, description, title, additionalProperties)

        /** An object schema from an ordered map of properties. See the vararg overload. */
        public fun obj(
            properties: Map<String, JsonSchema>,
            required: List<String>? = null,
            description: String? = null,
            title: String? = null,
            additionalProperties: Boolean = false,
        ): JsonSchema = build {
            put("type", JsonPrimitive("object"))
            title?.let { put("title", JsonPrimitive(it)) }
            description?.let { put("description", JsonPrimitive(it)) }
            put("properties", JsonObject(properties.mapValuesTo(LinkedHashMap()) { it.value.json }))
            put("required", JsonArray((required ?: properties.keys.toList()).map(::JsonPrimitive)))
            put("additionalProperties", JsonPrimitive(additionalProperties))
        }

        /** A string, optionally limited to [enum] values, a [const], a regex [pattern] or a length range. */
        public fun string(
            description: String? = null,
            enum: List<String>? = null,
            const: String? = null,
            pattern: String? = null,
            minLength: Int? = null,
            maxLength: Int? = null,
        ): JsonSchema = build {
            put("type", JsonPrimitive("string"))
            description?.let { put("description", JsonPrimitive(it)) }
            enum?.let { values -> put("enum", JsonArray(values.map(::JsonPrimitive))) }
            const?.let { put("const", JsonPrimitive(it)) }
            pattern?.let { put("pattern", JsonPrimitive(it)) }
            minLength?.let { put("minLength", JsonPrimitive(it)) }
            maxLength?.let { put("maxLength", JsonPrimitive(it)) }
        }

        /** An integer in `minimum..maximum` (both optional). */
        public fun integer(description: String? = null, minimum: Long? = null, maximum: Long? = null): JsonSchema = build {
            put("type", JsonPrimitive("integer"))
            description?.let { put("description", JsonPrimitive(it)) }
            minimum?.let { put("minimum", JsonPrimitive(it)) }
            maximum?.let { put("maximum", JsonPrimitive(it)) }
        }

        /** A number in `minimum..maximum` (both optional). */
        public fun number(description: String? = null, minimum: Double? = null, maximum: Double? = null): JsonSchema = build {
            put("type", JsonPrimitive("number"))
            description?.let { put("description", JsonPrimitive(it)) }
            minimum?.let { put("minimum", JsonPrimitive(it)) }
            maximum?.let { put("maximum", JsonPrimitive(it)) }
        }

        /** A boolean. */
        public fun boolean(description: String? = null): JsonSchema = build {
            put("type", JsonPrimitive("boolean"))
            description?.let { put("description", JsonPrimitive(it)) }
        }

        /** An array of [items] with an optional length range. */
        public fun array(items: JsonSchema, description: String? = null, minItems: Int? = null, maxItems: Int? = null): JsonSchema = build {
            put("type", JsonPrimitive("array"))
            description?.let { put("description", JsonPrimitive(it)) }
            put("items", items.json)
            minItems?.let { put("minItems", JsonPrimitive(it)) }
            maxItems?.let { put("maxItems", JsonPrimitive(it)) }
        }

        /** A value matching at least one of [choices]. */
        public fun anyOf(choices: List<JsonSchema>, description: String? = null): JsonSchema = build {
            description?.let { put("description", JsonPrimitive(it)) }
            put("anyOf", JsonArray(choices.map { it.json }))
        }

        /** A value matching exactly one of [choices]. */
        public fun oneOf(choices: List<JsonSchema>, description: String? = null): JsonSchema = build {
            description?.let { put("description", JsonPrimitive(it)) }
            put("oneOf", JsonArray(choices.map { it.json }))
        }

        /** A value equal to one of [values] (any JSON type). */
        public fun enumOf(values: List<JsonElement>, description: String? = null): JsonSchema = build {
            description?.let { put("description", JsonPrimitive(it)) }
            put("enum", JsonArray(values))
        }

        /** A reference to a definition added with [withDefinitions]: `{"$ref": "#/$defs/<name>"}`. */
        public fun ref(name: String): JsonSchema = build { put("\$ref", JsonPrimitive("#/\$defs/$name")) }

        private inline fun build(block: LinkedHashMap<String, JsonElement>.() -> Unit): JsonSchema =
            JsonSchema(JsonObject(LinkedHashMap<String, JsonElement>().apply(block)))
    }
}

/** Serializes a [JsonSchema] as its JSON document. */
internal object JsonSchemaSerializer : KSerializer<JsonSchema> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: JsonSchema) {
        encoder.encodeSerializableValue(JsonElement.serializer(), value.json)
    }

    override fun deserialize(decoder: Decoder): JsonSchema =
        JsonSchema.from(decoder.decodeSerializableValue(JsonElement.serializer()))
}

/** A malformed schema. [path] is a JSON pointer into the schema (`#/properties/item`). */
public class InvalidSchemaException(public val path: String, public val reason: String) :
    IllegalArgumentException("Schema error at $path: $reason")

/** One way a value fails a schema. [path] is a JSONPath-like location (`$.items[0].name`). */
@Serializable
public data class SchemaViolation(public val path: String, public val message: String) {
    override fun toString(): String = "$path: $message"
}

/** Joins violations for an error message or a repair prompt. */
public fun List<SchemaViolation>.describe(limit: Int = 4): String {
    val shown = take(limit).joinToString("; ")
    return if (size > limit) "$shown; and ${size - limit} more" else shown
}

/** Walks a schema looking for errors (thrown) and ignored keywords (returned). */
private class SchemaChecker(private val root: JsonObject) {
    private val warnings = LinkedHashSet<String>()

    fun run(): List<String> {
        check(root, "#", 0)
        return warnings.toList()
    }

    private fun fail(path: String, reason: String): Nothing = throw InvalidSchemaException(path, reason)

    private fun check(node: JsonElement, path: String, depth: Int) {
        if (depth > MAX_DEPTH) fail(path, "the schema nests more than $MAX_DEPTH levels deep")
        if (node is JsonPrimitive && node.boolValue != null) return // true / false schemas
        val schema = node as? JsonObject ?: fail(path, "expected a schema object")
        for (key in schema.keys) {
            if (key !in KNOWN_KEYWORDS && !key.startsWith("x-")) warnings += "$path: '$key' is not supported and is ignored"
        }
        schema["\$ref"]?.let { ref ->
            val target = ref.stringValue ?: fail("$path/\$ref", "must be a string")
            if (SchemaEngine.resolvePointer(root, target) == null) fail("$path/\$ref", "cannot resolve '$target' (only local references are supported)")
        }
        when (val type = schema["type"]) {
            null -> Unit
            is JsonPrimitive -> if (!type.isString || type.content !in TYPES) fail("$path/type", "unsupported type ${type.toJsonString()}")
            is JsonArray -> {
                if (type.isEmpty()) fail("$path/type", "must not be empty")
                type.forEach { if (it.stringValue !in TYPES) fail("$path/type", "unsupported type ${it.toJsonString()}") }
            }
            else -> fail("$path/type", "must be a string or an array of strings")
        }
        schema["properties"]?.let { properties ->
            val members = properties as? JsonObject ?: fail("$path/properties", "must be an object")
            members.forEach { (name, child) -> check(child, "$path/properties/${escape(name)}", depth + 1) }
        }
        schema["required"]?.let { required ->
            val names = required as? JsonArray ?: fail("$path/required", "must be an array of property names")
            names.forEachIndexed { index, name -> if (name.stringValue == null) fail("$path/required/$index", "must be a string") }
        }
        schema["additionalProperties"]?.let { if (it !is JsonPrimitive || it.boolValue == null) check(it, "$path/additionalProperties", depth + 1) }
        schema["items"]?.let { items ->
            if (items is JsonArray) fail("$path/items", "tuple validation (an array of schemas) is not supported")
            check(items, "$path/items", depth + 1)
        }
        for (keyword in JsonSchema.COMPOSITION_KEYWORDS) {
            schema[keyword]?.let { choices ->
                val list = choices as? JsonArray ?: fail("$path/$keyword", "must be an array of schemas")
                if (list.isEmpty()) fail("$path/$keyword", "must not be empty")
                list.forEachIndexed { index, child -> check(child, "$path/$keyword/$index", depth + 1) }
            }
        }
        schema["enum"]?.let { values ->
            val list = values as? JsonArray ?: fail("$path/enum", "must be an array")
            if (list.isEmpty()) fail("$path/enum", "must not be empty")
        }
        for (keyword in listOf("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum")) {
            schema[keyword]?.let { if (it.doubleValue == null) fail("$path/$keyword", "must be a number") }
        }
        for (keyword in listOf("minLength", "maxLength", "minItems", "maxItems")) {
            schema[keyword]?.let { if ((it.longValue ?: -1) < 0) fail("$path/$keyword", "must be a non-negative integer") }
        }
        schema["pattern"]?.let { pattern ->
            val text = pattern.stringValue ?: fail("$path/pattern", "must be a string")
            try {
                Regex(text)
            } catch (error: IllegalArgumentException) {
                fail("$path/pattern", "invalid regular expression: ${error.message?.lineSequence()?.firstOrNull()}")
            }
        }
        for (keyword in listOf("\$defs", "definitions")) {
            schema[keyword]?.let { defs ->
                val members = defs as? JsonObject ?: fail("$path/$keyword", "must be an object")
                members.forEach { (name, child) -> check(child, "$path/$keyword/${escape(name)}", depth + 1) }
            }
        }
    }

    private fun escape(name: String) = name.replace("~", "~0").replace("/", "~1")

    companion object {
        const val MAX_DEPTH = 32
        val TYPES = setOf("object", "array", "string", "integer", "number", "boolean", "null")
        val KNOWN_KEYWORDS = setOf(
            "type", "nullable", "properties", "required", "additionalProperties", "items", "minItems", "maxItems",
            "enum", "const", "pattern", "minLength", "maxLength", "minimum", "maximum", "exclusiveMinimum",
            "exclusiveMaximum", "anyOf", "oneOf", "allOf", "\$ref", "\$defs", "definitions", "title", "description",
            "default", "examples", "\$schema", "\$id", "\$comment", "x-order",
        )
    }
}

internal fun JsonElement?.isNullOrJsonNull(): Boolean = this == null || this is JsonNull
