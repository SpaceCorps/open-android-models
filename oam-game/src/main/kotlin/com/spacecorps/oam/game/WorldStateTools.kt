package com.spacecorps.oam.game

import com.spacecorps.oam.AgentTool
import com.spacecorps.oam.JsonSchema
import com.spacecorps.oam.LenientJson
import com.spacecorps.oam.ToolOutput
import com.spacecorps.oam.isBoolean
import com.spacecorps.oam.isNumber
import com.spacecorps.oam.jsonObjectOf
import com.spacecorps.oam.stringValue
import com.spacecorps.oam.toJsonString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The model-facing tools and prompt summaries of [WorldState]. */
internal object WorldStateTools {
    fun tools(world: WorldState, readable: List<String>, writable: List<String>, limit: Int): List<AgentTool> {
        val readPaths = readable.mapNotNull(::parseOrNull)
        val writePaths = writable.mapNotNull(::parseOrNull)
        val tools = ArrayList<AgentTool>()
        if (readPaths.isNotEmpty()) tools += readTool(world, readPaths, limit.coerceAtLeast(MIN_OUTPUT_CHARS))
        if (writePaths.isNotEmpty()) tools += updateTool(world, writePaths)
        return tools
    }

    private fun readTool(world: WorldState, readable: List<WorldPath>, limit: Int): AgentTool {
        var description = "Read the current game state (JSON). Use it to check facts before you answer."
        if (readable.any { it.isRoot }) {
            val keys = world.snapshot().keys
            if (keys.isNotEmpty()) description += " Top-level keys: ${keys.take(12).joinToString(", ")}."
        } else {
            description += " Readable paths: ${readable.joinToString(", ")}."
        }
        val parameters = JsonSchema.obj(
            "path" to JsonSchema.string(description = "Dot path such as 'player.gold'. Empty string for everything you may read."),
        )
        return AgentTool.local(WorldState.READ_TOOL_NAME, description, parameters) { call ->
            read(world, call.arguments["path"]?.stringValue.orEmpty(), readable, limit)
        }
    }

    private fun updateTool(world: WorldState, writable: List<WorldPath>): AgentTool {
        val description = "Change a value in the game state. Writable paths: ${describe(writable)}."
        // "value" accepts any JSON: the model may send 42, true, "done" or {"a": 1}. It is fitted to the stored type.
        val parameters = JsonSchema.obj(
            "path" to JsonSchema.string(description = "Dot path to change, such as 'quests.lost_ring.status'."),
            "value" to JsonSchema(jsonObjectOf("description" to "The new value, such as 42, true, done or {\"a\": 1}.")),
        )
        return AgentTool.local(WorldState.UPDATE_TOOL_NAME, description, parameters) { call ->
            write(world, call.string("path"), call.arguments["value"] ?: JsonNull, writable)
        }
    }

    fun read(world: WorldState, raw: String, readable: List<WorldPath>, limit: Int): ToolOutput {
        val requested = try {
            WorldPath.parse(raw)
        } catch (error: WorldStateError) {
            return ToolOutput.Error(error.message)
        }
        val root = world.snapshot()
        val path = WorldDocument.resolvingCase(requested, root)
        if (readable.any { path.isWithin(it) }) {
            val value = WorldDocument.value(path, root) ?: return ToolOutput.Error(missingMessage(path, root))
            return ToolOutput.Json(shortened(value, limit))
        }
        // An ancestor of readable paths: show only the readable parts.
        val visible = readable.filter { it.isWithin(path) }
        if (visible.isEmpty()) return ToolOutput.Error("You cannot read '$path'. Readable paths: ${readable.joinToString(", ")}.")
        return ToolOutput.Json(shortened(WorldDocument.filtered(root, visible, path), limit))
    }

    fun write(world: WorldState, raw: String, value: JsonElement, writable: List<WorldPath>): ToolOutput {
        val requested = try {
            WorldPath.parse(raw)
        } catch (error: WorldStateError) {
            return ToolOutput.Error(error.message)
        }
        val path = WorldDocument.resolvingCase(requested, world.snapshot())
        if (writable.none { path.isWithin(it) }) return ToolOutput.Error("'$path' is read-only. You may change: ${describe(writable)}.")
        var result: ToolOutput = ToolOutput.Text("")
        try {
            world.modify(path.toString()) { current ->
                when (val coerced = coerce(value, current)) {
                    is Coercion.Success -> {
                        result = ToolOutput.Text("Set $path to ${coerced.value.toJsonString()}" + (current?.let { " (was ${it.toJsonString()})." } ?: "."))
                        coerced.value
                    }
                    is Coercion.Failure -> {
                        result = ToolOutput.Error("Cannot set '$path': ${coerced.message}")
                        current
                    }
                }
            }
        } catch (error: WorldStateError) {
            return ToolOutput.Error(error.message)
        }
        return result
    }

    sealed interface Coercion {
        class Success(val value: JsonElement) : Coercion

        class Failure(val message: String) : Coercion
    }

    /**
     * Fits a model-supplied value to the type already stored at the path. The
     * model often sends values as strings: `"42"` for a number becomes `42`,
     * and `"true"` for a boolean becomes `true`.
     */
    fun coerce(value: JsonElement, current: JsonElement?): Coercion {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when {
            current == null || current is JsonNull -> {
                // A new value: interpret JSON literals (42, true, [1], "x"), otherwise keep the text.
                if (text == null) Coercion.Success(value) else Coercion.Success(strictJson(text.trim()) ?: value)
            }
            current is JsonPrimitive && current.isString -> when {
                text != null -> {
                    // Unwrap a JSON string literal the model quoted itself.
                    val trimmed = text.trim()
                    val inner = if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) strictJson(trimmed)?.stringValue else null
                    Coercion.Success(JsonPrimitive(inner ?: text))
                }
                value is JsonPrimitive -> Coercion.Success(JsonPrimitive(value.content))
                else -> Coercion.Failure("it holds a string; send text, not ${WorldDocument.typeName(value)}.")
            }
            current.isNumber -> {
                if (value.isNumber) return Coercion.Success(value)
                val number = text?.trim()?.replace(",", "")?.toDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: return Coercion.Failure("it holds a number; '${text ?: value.toJsonString()}' is not a number.")
                Coercion.Success(if (number == Math.rint(number) && kotlin.math.abs(number) < 1e15) JsonPrimitive(number.toLong()) else JsonPrimitive(number))
            }
            current.isBoolean -> {
                if (value.isBoolean) return Coercion.Success(value)
                when (text?.trim()?.lowercase()) {
                    "true", "yes", "1" -> Coercion.Success(JsonPrimitive(true))
                    "false", "no", "0" -> Coercion.Success(JsonPrimitive(false))
                    else -> Coercion.Failure("it holds true or false; got '${text ?: value.toJsonString()}'.")
                }
            }
            else -> {
                // A list or object.
                if (value is JsonArray || value is JsonObject) return Coercion.Success(value)
                val parsed = text?.let { LenientJson.parseOrNull(it.trim()) }?.takeIf { it is JsonArray || it is JsonObject }
                    ?: return Coercion.Failure("it holds ${WorldDocument.typeName(current)}; send valid JSON.")
                Coercion.Success(parsed)
            }
        }
    }

    /**
     * Strict JSON parsing, so `done` stays text while `42`, `true` and `["a"]`
     * become values. kotlinx's tree parser accepts bare words as unquoted
     * literals (which would later serialize as invalid JSON), so those are rejected here.
     */
    private fun strictJson(text: String): JsonElement? {
        val parsed = try {
            GameJson.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return null
        }
        return parsed.takeIf(WorldDocument::isStrictJson)
    }

    fun missingMessage(path: WorldPath, root: JsonElement): String {
        var ancestor = path
        while (true) {
            ancestor = ancestor.parent ?: break
            val value = WorldDocument.value(ancestor, root) ?: continue
            val name = if (ancestor.isRoot) "The state" else "'$ancestor'"
            return when (value) {
                is JsonObject -> "No value at '$path'. $name has keys: ${value.keys.take(20).joinToString(", ")}."
                is JsonArray -> "No value at '$path'. $name is a list of ${value.size} items (indices 0-${(value.size - 1).coerceAtLeast(0)})."
                else -> "No value at '$path'. $name is ${WorldDocument.typeName(value)}: ${value.toJsonString()}."
            }
        }
        return "No value at '$path'."
    }

    /** Returns [value], or a key listing when its JSON exceeds [limit] characters. */
    fun shortened(value: JsonElement, limit: Int): JsonElement {
        if (value.toJsonString().length <= limit) return value
        return when (value) {
            is JsonObject -> {
                val preview = LinkedHashMap<String, JsonElement>()
                for ((key, child) in value) {
                    preview[key] = when {
                        child is JsonObject -> JsonPrimitive("{object with ${child.size} keys}")
                        child is JsonArray -> JsonPrimitive("[list of ${child.size} items]")
                        child is JsonPrimitive && child.isString && child.content.length > 80 -> JsonPrimitive(child.content.take(77) + "...")
                        else -> child
                    }
                }
                preview["_note"] = JsonPrimitive("Shortened. Read a narrower path for details.")
                JsonObject(preview)
            }
            is JsonArray -> {
                val kept = ArrayList<JsonElement>()
                var used = 2
                for (item in value) {
                    val size = item.toJsonString().length + 1
                    if (used + size > limit) break
                    kept += item
                    used += size
                }
                jsonObjectOf("items" to JsonArray(kept), "_note" to "Showing ${kept.size} of ${value.size} items.")
            }
            is JsonPrimitive -> if (value.isString) JsonPrimitive(value.content.take(limit) + "...") else value
        }
    }

    fun summary(root: JsonObject, paths: List<String>, maxLines: Int): String {
        var lines = ArrayList<String>()
        for (raw in paths) {
            val path = parseOrNull(raw) ?: continue
            val value = WorldDocument.value(path, root) ?: continue
            flatten(value, path.toString(), lines, 0)
        }
        val limit = maxLines.coerceAtLeast(1)
        if (lines.size > limit) {
            val omitted = lines.size - limit
            lines = ArrayList(lines.take(limit) + "($omitted more not shown)")
        }
        return lines.joinToString("\n")
    }

    private fun flatten(value: JsonElement, path: String, lines: MutableList<String>, depth: Int) {
        val label = path.ifEmpty { "state" }
        when {
            value is JsonObject && depth < MAX_FLATTEN_DEPTH && value.isNotEmpty() ->
                for ((key, child) in value) flatten(child, if (path.isEmpty()) key else "$path.$key", lines, depth + 1)
            value is JsonPrimitive && value.isString -> lines += "$label: ${clip(value.content)}"
            else -> lines += "$label: ${clip(value.toJsonString())}"
        }
    }

    private fun clip(text: String): String = if (text.length > 200) text.take(197) + "..." else text

    private fun describe(paths: List<WorldPath>): String = paths.joinToString(", ") { if (it.isRoot) "(everything)" else it.toString() }

    private fun parseOrNull(text: String): WorldPath? = try {
        WorldPath.parse(text)
    } catch (_: WorldStateError) {
        null
    }

    private const val MIN_OUTPUT_CHARS = 100
    private const val MAX_FLATTEN_DEPTH = 4
}
