package com.spacecorps.oam

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/** Validation, coercion, key ordering and prompt rendering for one schema document. */
internal class SchemaEngine(private val root: JsonObject) {
    private val patterns = HashMap<String, Regex?>()

    // MARK: Resolution

    private fun resolve(node: JsonElement): JsonElement {
        var current = node
        repeat(MAX_REF_HOPS) {
            val ref = (current as? JsonObject)?.get("\$ref")?.stringValue ?: return current
            current = resolvePointer(root, ref) ?: return current
        }
        return current
    }

    private fun types(schema: JsonObject): List<String>? {
        val declared = when (val type = schema["type"]) {
            is JsonArray -> type.mapNotNull { it.stringValue }
            is JsonPrimitive -> listOfNotNull(type.stringValue)
            else -> return null
        }
        return if (schema["nullable"]?.boolValue == true && "null" !in declared) declared + "null" else declared
    }

    // MARK: Validation

    fun validate(value: JsonElement): List<SchemaViolation> {
        val violations = ArrayList<SchemaViolation>()
        validate(value, root, "$", violations, 0)
        return violations
    }

    private fun validate(value: JsonElement, node: JsonElement, path: String, out: MutableList<SchemaViolation>, depth: Int) {
        if (depth > MAX_DEPTH) return
        if (node is JsonPrimitive) {
            if (node.boolValue == false) out += SchemaViolation(path, "no value is allowed here")
            return
        }
        val schema = resolve(node) as? JsonObject ?: return

        schema["allOf"]?.arrayValue?.forEach { validate(value, it, path, out, depth + 1) }
        schema["anyOf"]?.arrayValue?.let { validateChoice(value, it, path, out, depth, exactlyOne = false) }
        schema["oneOf"]?.arrayValue?.let { validateChoice(value, it, path, out, depth, exactlyOne = true) }

        if (value is JsonNull && schema["nullable"]?.boolValue == true) return
        val types = types(schema)
        if (types != null && types.none { matchesType(value, it) }) {
            out += SchemaViolation(path, "expected ${describeTypes(types)}, got ${describeValue(value)}")
            return
        }
        schema["enum"]?.arrayValue?.let { values ->
            if (values.none { jsonEquals(it, value) }) {
                out += SchemaViolation(path, "must be one of ${values.joinToString(", ") { it.toJsonString() }}; got ${preview(value)}")
            }
        }
        schema["const"]?.let { constant ->
            if (!jsonEquals(constant, value)) out += SchemaViolation(path, "must be exactly ${constant.toJsonString()}; got ${preview(value)}")
        }
        when {
            value is JsonObject -> validateObject(value, schema, path, out, depth)
            value is JsonArray -> validateArray(value, schema, path, out, depth)
            value is JsonPrimitive && value.isString -> validateString(value.content, schema, path, out)
            value.isNumber -> validateNumber(value as JsonPrimitive, schema, path, out)
        }
    }

    private fun validateChoice(
        value: JsonElement,
        choices: List<JsonElement>,
        path: String,
        out: MutableList<SchemaViolation>,
        depth: Int,
        exactlyOne: Boolean,
    ) {
        val results = choices.map { choice -> ArrayList<SchemaViolation>().also { validate(value, choice, path, it, depth + 1) } }
        val matches = results.count { it.isEmpty() }
        when {
            matches == 1 || (matches > 1 && !exactlyOne) -> Unit
            matches > 1 -> out += SchemaViolation(path, "matches $matches of the alternatives but must match exactly one")
            else -> {
                val closest = results.minBy { it.size }
                out += SchemaViolation(path, "does not match any of the ${choices.size} allowed alternatives (closest: ${closest.describe(2)})")
            }
        }
    }

    private fun validateObject(value: JsonObject, schema: JsonObject, path: String, out: MutableList<SchemaViolation>, depth: Int) {
        val properties = schema["properties"] as? JsonObject ?: EmptyJsonObject
        schema["required"]?.arrayValue?.forEach { name ->
            val key = name.stringValue ?: return@forEach
            if (key !in value) out += SchemaViolation(path, "missing required property \"$key\"")
        }
        for ((key, child) in value) {
            val propertySchema = properties[key]
            when {
                propertySchema != null -> validate(child, propertySchema, childPath(path, key), out, depth + 1)
                else -> when (val additional = schema["additionalProperties"]) {
                    null -> Unit
                    is JsonObject -> validate(child, additional, childPath(path, key), out, depth + 1)
                    else -> if (additional.boolValue == false) {
                        val allowed = if (properties.isEmpty()) "no properties are allowed" else "allowed: ${properties.keys.joinToString(", ") { "\"$it\"" }}"
                        out += SchemaViolation(path, "unexpected property \"$key\" ($allowed)")
                    }
                }
            }
        }
    }

    private fun validateArray(value: JsonArray, schema: JsonObject, path: String, out: MutableList<SchemaViolation>, depth: Int) {
        schema["minItems"]?.longValue?.let { min ->
            if (value.size < min) out += SchemaViolation(path, "must have at least $min item${if (min == 1L) "" else "s"}; got ${value.size}")
        }
        schema["maxItems"]?.longValue?.let { max ->
            if (value.size > max) out += SchemaViolation(path, "must have at most $max item${if (max == 1L) "" else "s"}; got ${value.size}")
        }
        schema["items"]?.let { items -> value.forEachIndexed { index, item -> validate(item, items, "$path[$index]", out, depth + 1) } }
    }

    private fun validateString(value: String, schema: JsonObject, path: String, out: MutableList<SchemaViolation>) {
        val length = value.codePointCount(0, value.length)
        schema["minLength"]?.longValue?.let { min ->
            if (length < min) out += SchemaViolation(path, "must have at least $min character${if (min == 1L) "" else "s"}; got $length")
        }
        schema["maxLength"]?.longValue?.let { max ->
            if (length > max) out += SchemaViolation(path, "must have at most $max character${if (max == 1L) "" else "s"}; got $length")
        }
        schema["pattern"]?.stringValue?.let { pattern ->
            val regex = patterns.getOrPut(pattern) { runCatching { Regex(pattern) }.getOrNull() }
            if (regex != null && !regex.containsMatchIn(value)) {
                out += SchemaViolation(path, "must match the pattern $pattern; got ${preview(JsonPrimitive(value))}")
            }
        }
    }

    private fun validateNumber(value: JsonPrimitive, schema: JsonObject, path: String, out: MutableList<SchemaViolation>) {
        val number = value.decimal() ?: return
        fun bound(key: String) = schema[key]?.let { (it as? JsonPrimitive)?.decimal() }
        bound("minimum")?.let { if (number < it) out += SchemaViolation(path, "must be at least ${plain(it)}; got ${plain(number)}") }
        bound("maximum")?.let { if (number > it) out += SchemaViolation(path, "must be at most ${plain(it)}; got ${plain(number)}") }
        bound("exclusiveMinimum")?.let { if (number <= it) out += SchemaViolation(path, "must be greater than ${plain(it)}; got ${plain(number)}") }
        bound("exclusiveMaximum")?.let { if (number >= it) out += SchemaViolation(path, "must be less than ${plain(it)}; got ${plain(number)}") }
    }

    // MARK: Coercion

    fun coerce(value: JsonElement): JsonElement = coerce(value, root, 0)

    private fun coerce(value: JsonElement, node: JsonElement, depth: Int): JsonElement {
        if (depth > MAX_DEPTH || node is JsonPrimitive) return value
        val schema = resolve(node) as? JsonObject ?: return value
        var current = value
        schema["allOf"]?.arrayValue?.forEach { current = coerce(current, it, depth + 1) }
        (schema["anyOf"] ?: schema["oneOf"])?.arrayValue?.let { choices ->
            if (choices.any { accepts(current, it, depth) }) return current
            for (choice in choices) {
                val candidate = coerce(current, choice, depth + 1)
                if (accepts(candidate, choice, depth)) return candidate
            }
            return current
        }
        if (current is JsonNull) return current
        val types = types(schema)
        if (types != null && types.none { matchesType(current, it) }) current = convert(current, types)
        schema["enum"]?.arrayValue?.let { current = matchChoice(current, it) }
        schema["const"]?.let { current = matchChoice(current, listOf(it)) }
        return when (val converted = current) {
            is JsonObject -> coerceObject(converted, schema, depth)
            is JsonArray -> schema["items"]?.let { items -> JsonArray(converted.map { coerce(it, items, depth + 1) }) } ?: converted
            else -> converted
        }
    }

    private fun accepts(value: JsonElement, node: JsonElement, depth: Int): Boolean {
        val violations = ArrayList<SchemaViolation>()
        validate(value, node, "$", violations, depth + 1)
        return violations.isEmpty()
    }

    private fun convert(value: JsonElement, types: List<String>): JsonElement {
        for (type in types) {
            val converted: JsonElement? = when (type) {
                "integer" -> value.stringValue?.trim()?.let { text ->
                    text.toLongOrNull()?.let(::JsonPrimitive)
                        ?: text.toBigDecimalOrNull()?.takeIf { it.stripTrailingZeros().scale() <= 0 }?.let { JsonPrimitive(it.toLong()) }
                }
                "number" -> value.stringValue?.trim()?.let { text ->
                    text.toLongOrNull()?.let(::JsonPrimitive) ?: text.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::JsonPrimitive)
                }
                "boolean" -> when (value.stringValue?.trim()?.lowercase()) {
                    "true", "yes" -> JsonPrimitive(true)
                    "false", "no" -> JsonPrimitive(false)
                    else -> null
                }
                "string" -> if (value.isNumber || value.isBoolean) JsonPrimitive((value as JsonPrimitive).content) else null
                "object" -> value.stringValue?.let { LenientJson.parseOrNull(it.trim()) as? JsonObject }
                "array" -> value.stringValue?.let { LenientJson.parseOrNull(it.trim()) as? JsonArray } ?: JsonArray(listOf(value))
                "null" -> if (value.stringValue?.trim()?.lowercase() in setOf("null", "none")) JsonNull else null
                else -> null
            }
            if (converted != null) return converted
        }
        return value
    }

    private fun matchChoice(value: JsonElement, choices: List<JsonElement>): JsonElement {
        if (choices.any { jsonEquals(it, value) }) return value
        val text = value.stringValue ?: return value
        val wanted = normalizeWords(text)
        val matches = choices.filter { choice -> choice.stringValue?.let(::normalizeWords) == wanted }
        return matches.singleOrNull() ?: value
    }

    private fun coerceObject(value: JsonObject, schema: JsonObject, depth: Int): JsonObject {
        val properties = schema["properties"] as? JsonObject ?: EmptyJsonObject
        val required = schema["required"]?.arrayValue?.mapNotNull { it.stringValue }?.toSet().orEmpty()
        val additional = schema["additionalProperties"]

        // Rename keys that differ from a declared property only in case or separators.
        val renamed = LinkedHashMap<String, JsonElement>()
        for ((key, child) in value) {
            val target = if (key in properties) {
                key
            } else {
                properties.keys.firstOrNull { it !in value && it !in renamed && normalizeKey(it) == normalizeKey(key) } ?: key
            }
            renamed[target] = child
        }
        val complete = required.all { it in renamed }

        val result = LinkedHashMap<String, JsonElement>()
        for ((key, child) in renamed) {
            val propertySchema = properties[key]
            if (propertySchema != null) {
                if (child is JsonNull && key !in required && !accepts(JsonNull, propertySchema, depth)) continue
                result[key] = coerce(child, propertySchema, depth + 1)
            } else {
                when {
                    additional is JsonObject -> result[key] = coerce(child, additional, depth + 1)
                    // Drop unknown keys only when nothing is missing, so a
                    // misnamed key still shows up in the violations.
                    additional?.boolValue == false && complete -> Unit
                    else -> result[key] = child
                }
            }
        }
        return JsonObject(result)
    }

    // MARK: Ordering

    fun order(value: JsonElement): JsonElement = order(value, root, 0)

    private fun order(value: JsonElement, node: JsonElement, depth: Int): JsonElement {
        if (depth > MAX_DEPTH) return value
        val schema = resolve(node) as? JsonObject ?: return value
        return when (value) {
            is JsonObject -> {
                (schema["anyOf"] ?: schema["oneOf"])?.arrayValue?.let { choices ->
                    val keys = value.keys
                    val best = choices.map(::resolve).maxByOrNull { overlap(it, keys) }
                    return if (best != null) order(value, best, depth + 1) else value
                }
                val properties = LinkedHashMap<String, JsonElement>()
                (schema["properties"] as? JsonObject)?.let { properties.putAll(it) }
                schema["allOf"]?.arrayValue?.forEach { branch ->
                    ((resolve(branch) as? JsonObject)?.get("properties") as? JsonObject)?.forEach { (key, child) -> properties.putIfAbsent(key, child) }
                }
                val declared = schema["x-order"]?.arrayValue?.mapNotNull { it.stringValue } ?: properties.keys.toList()
                val result = LinkedHashMap<String, JsonElement>()
                for (key in declared) value[key]?.let { result[key] = order(it, properties[key] ?: EmptyJsonObject, depth + 1) }
                for ((key, child) in value) {
                    if (key !in result) result[key] = order(child, properties[key] ?: schema["additionalProperties"] ?: EmptyJsonObject, depth + 1)
                }
                JsonObject(result)
            }
            is JsonArray -> {
                val items = schema["items"] ?: return value
                JsonArray(value.map { order(it, items, depth + 1) })
            }
            else -> value
        }
    }

    private fun overlap(schema: JsonElement, keys: Set<String>): Int {
        val names = ((schema as? JsonObject)?.get("properties") as? JsonObject)?.keys.orEmpty()
        return names.intersect(keys).size * 2 - (names - keys).size
    }

    // MARK: Rendering

    fun render(): String = render(root, 0, emptySet())

    private fun render(node: JsonElement, depth: Int, refs: Set<String>): String {
        if (node is JsonPrimitive) return if (node.boolValue == false) "never" else "any"
        val raw = node as? JsonObject ?: return "any"
        raw["\$ref"]?.stringValue?.let { ref ->
            if (ref in refs || depth > RENDER_DEPTH) return ref.substringAfterLast('/').ifEmpty { "object" }
            val target = resolvePointer(root, ref) ?: return "any"
            return render(target, depth + 1, refs + ref)
        }
        if (depth > RENDER_DEPTH) return "…"
        val schema = raw
        schema["enum"]?.arrayValue?.let { values -> return values.joinToString(" | ") { it.toJsonString() } }
        schema["const"]?.let { return it.toJsonString() }
        (schema["anyOf"] ?: schema["oneOf"])?.arrayValue?.let { choices ->
            return choices.joinToString(" | ") { render(it, depth + 1, refs) }
        }
        schema["allOf"]?.arrayValue?.let { parts -> return parts.joinToString(" & ") { render(it, depth + 1, refs) } }
        val types = types(schema) ?: when {
            schema["properties"] != null -> listOf("object")
            schema["items"] != null -> listOf("array")
            else -> return "any"
        }
        return types.joinToString(" | ") { type -> renderType(type, schema, depth, refs) }
    }

    private fun renderType(type: String, schema: JsonObject, depth: Int, refs: Set<String>): String = when (type) {
        "object" -> renderObject(schema, depth, refs)
        "array" -> {
            val items = schema["items"]?.let { render(it, depth + 1, refs) } ?: "any"
            val min = schema["minItems"]?.longValue
            val max = schema["maxItems"]?.longValue
            "[$items, …]" + range(min, max, " items")
        }
        "string" -> buildString {
            append("string")
            schema["pattern"]?.stringValue?.let { append(" matching /$it/") }
            append(range(schema["minLength"]?.longValue, schema["maxLength"]?.longValue, " chars"))
        }
        "integer", "number" -> buildString {
            append(type)
            val min = schema["minimum"]?.let { (it as? JsonPrimitive)?.decimal() }
            val max = schema["maximum"]?.let { (it as? JsonPrimitive)?.decimal() }
            when {
                min != null && max != null -> append(" ${plain(min)}-${plain(max)}")
                min != null -> append(" >= ${plain(min)}")
                max != null -> append(" <= ${plain(max)}")
            }
            schema["exclusiveMinimum"]?.let { (it as? JsonPrimitive)?.decimal() }?.let { append(" > ${plain(it)}") }
            schema["exclusiveMaximum"]?.let { (it as? JsonPrimitive)?.decimal() }?.let { append(" < ${plain(it)}") }
        }
        else -> type
    }

    private fun renderObject(schema: JsonObject, depth: Int, refs: Set<String>): String {
        val properties = schema["properties"] as? JsonObject
        if (properties.isNullOrEmpty()) {
            val additional = schema["additionalProperties"] as? JsonObject
            return if (additional != null) "{[key: string]: ${render(additional, depth + 1, refs)}}" else "{}"
        }
        val required = schema["required"]?.arrayValue?.mapNotNull { it.stringValue }?.toSet().orEmpty()
        return properties.entries.joinToString(", ", "{", "}") { (name, child) ->
            val optional = if (name in required) "" else "?"
            val description = (resolve(child) as? JsonObject)?.get("description")?.stringValue
                ?: (child as? JsonObject)?.get("description")?.stringValue
            val rendered = render(child, depth + 1, refs)
            "\"$name\"$optional: $rendered" + (description?.let { " ($it)" } ?: "")
        }
    }

    private fun range(min: Long?, max: Long?, unit: String): String = when {
        min != null && max != null && min == max -> " (exactly $min$unit)"
        min != null && max != null -> " ($min-$max$unit)"
        min != null -> " (at least $min$unit)"
        max != null -> " (at most $max$unit)"
        else -> ""
    }

    companion object {
        private const val MAX_DEPTH = 64
        private const val RENDER_DEPTH = 8
        private const val MAX_REF_HOPS = 16

        /** Resolves a local JSON pointer (`#`, `#/$defs/Item`) in [root]. */
        fun resolvePointer(root: JsonObject, ref: String): JsonElement? {
            if (ref == "#") return root
            if (!ref.startsWith("#/")) return null
            var node: JsonElement = root
            for (raw in ref.substring(2).split('/')) {
                val token = raw.replace("~1", "/").replace("~0", "~")
                node = when (node) {
                    is JsonObject -> node[token]
                    is JsonArray -> token.toIntOrNull()?.let { node.getOrNull(it) }
                    else -> null
                } ?: return null
            }
            return node
        }

        fun matchesType(value: JsonElement, type: String): Boolean = when (type) {
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            "string" -> value is JsonPrimitive && value.isString
            "boolean" -> value.isBoolean
            "null" -> value is JsonNull
            "number" -> value.isNumber
            "integer" -> value.isNumber && (value as JsonPrimitive).decimal()?.let { it.stripTrailingZeros().scale() <= 0 } == true
            else -> true
        }

        /** Structural equality with numbers compared by value (`3 == 3.0`). */
        fun jsonEquals(a: JsonElement, b: JsonElement): Boolean = when {
            a.isNumber && b.isNumber -> (a as JsonPrimitive).decimal()?.compareTo((b as JsonPrimitive).decimal()) == 0
            a is JsonObject && b is JsonObject -> a.keys == b.keys && a.all { (key, child) -> jsonEquals(child, b.getValue(key)) }
            a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { jsonEquals(a[it], b[it]) }
            else -> a == b
        }

        fun describeTypes(types: List<String>): String = types.joinToString(" or ") { type ->
            when (type) {
                "object" -> "an object"
                "array" -> "an array"
                "integer" -> "an integer"
                "null" -> "null"
                else -> "a $type"
            }
        }

        fun describeValue(value: JsonElement): String = when {
            value is JsonNull -> "null"
            value is JsonObject -> "an object"
            value is JsonArray -> "an array"
            value is JsonPrimitive && value.isString -> "a string (${preview(value)})"
            value.isBoolean -> "a boolean (${(value as JsonPrimitive).content})"
            else -> "a number (${(value as JsonPrimitive).content})"
        }

        fun preview(value: JsonElement): String {
            val text = value.toJsonString()
            return if (text.length > 60) text.take(57) + "…" else text
        }

        fun childPath(path: String, key: String): String =
            if (key.isNotEmpty() && key.all { it.isLetterOrDigit() || it == '_' } && !key[0].isDigit()) "$path.$key" else "$path[${JsonPrimitive(key).toJsonString()}]"

        /** Lowercase, collapse separators: `"Dark Ale"`, `"dark_ale"` and `"dark-ale "` compare equal. */
        fun normalizeWords(text: String): String = text.trim().lowercase().replace(Regex("[\\s_-]+"), " ")

        /** Lowercase alphanumerics only: `itemName` equals `item_name`. */
        fun normalizeKey(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

        private fun JsonPrimitive.decimal(): BigDecimal? = if (isString) null else content.toBigDecimalOrNull()

        private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
    }
}
