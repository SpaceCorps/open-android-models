package com.spacecorps.oam.game

import com.spacecorps.oam.EmptyJsonObject
import com.spacecorps.oam.isJsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A parsed location inside a [WorldState] document.
 *
 * Paths are dot-separated keys, with list elements addressed by index:
 * `"player.gold"`, `"npcs.gorm.mood"`, `"party.0.name"`. Bracket indices
 * (`"party[0].name"`), slash separators (`"player/gold"`) and a `$.` prefix
 * are accepted too, because small models produce them. The empty path
 * (`""`) is the root. Keys that themselves contain `.`, `/` or `[` cannot be
 * addressed by path; use [WorldState.merge] or [WorldState.replace] for them.
 *
 * @property segments Keys and indices from the root, for example `["party", "0", "name"]`.
 */
public data class WorldPath(public val segments: List<String>) {
    /** True for the root of the document. */
    public val isRoot: Boolean get() = segments.isEmpty()

    /** The parent path, or `null` for the root. */
    public val parent: WorldPath? get() = if (segments.isEmpty()) null else WorldPath(segments.dropLast(1))

    /** Whether this path equals [other] or lies inside it. */
    public fun isWithin(other: WorldPath): Boolean =
        segments.size >= other.segments.size && segments.subList(0, other.segments.size) == other.segments

    /** Whether the two paths are on the same branch (one contains the other). */
    public fun overlaps(other: WorldPath): Boolean = isWithin(other) || other.isWithin(this)

    /** This path with [segment] appended. */
    public fun appending(segment: String): WorldPath = WorldPath(segments + segment)

    /** The canonical dot-separated form (`""` for the root). */
    override fun toString(): String = segments.joinToString(".")

    public companion object {
        /** The root of the document. */
        public val ROOT: WorldPath = WorldPath(emptyList())

        /**
         * Parses a path string.
         *
         * @throws WorldStateError for an empty segment (`"a..b"`, `"player."`).
         */
        public fun parse(text: String): WorldPath {
            var trimmed = text.trim()
            // Tolerate JSONPath-ish and pointer-ish prefixes.
            if (trimmed == "$" || trimmed == "/" || trimmed == ".") trimmed = ""
            trimmed = trimmed.removePrefix("$.").removePrefix("/")
            if (trimmed.isEmpty()) return ROOT
            // "party[0].name" -> "party.0.name"; "player/gold" -> "player.gold"
            val normalized = buildString(trimmed.length) {
                for (character in trimmed) {
                    when (character) {
                        '[', '/' -> append('.')
                        ']' -> Unit
                        else -> append(character)
                    }
                }
            }
            val segments = normalized.split('.').toMutableList()
            // A leading bracket ("[0].x") produces an empty first segment, which we drop.
            if (segments.firstOrNull() == "" && trimmed.startsWith("[")) segments.removeAt(0)
            if (segments.any { it.isEmpty() }) {
                throw WorldStateError(text, "'$text' is not a valid path. Use dot-separated keys such as 'player.gold'.")
            }
            return WorldPath(segments)
        }
    }
}

/**
 * An error reading or writing a [WorldState]: a malformed path, a write
 * through a value that is not an object or list, an invalid list index, or
 * a root that is not an object.
 *
 * @property path The path involved, as given.
 */
public class WorldStateError(public val path: String, message: String) : Exception(message) {
    override val message: String get() = super.message ?: ""
}

/** Pure operations on JSON documents addressed by [WorldPath]. */
internal object WorldDocument {
    fun value(path: WorldPath, root: JsonElement): JsonElement? {
        var current = root
        for (segment in path.segments) {
            current = when (current) {
                is JsonObject -> current[segment] ?: return null
                is JsonArray -> segment.toIntOrNull()?.let { current.getOrNull(it) } ?: return null
                else -> return null
            }
        }
        return current
    }

    /**
     * Returns [path] with each key matched case-insensitively against the
     * document when there is no exact match (small models capitalize keys).
     */
    fun resolvingCase(path: WorldPath, root: JsonElement): WorldPath {
        var current: JsonElement = root
        val resolved = ArrayList<String>()
        for ((offset, segment) in path.segments.withIndex()) {
            when (current) {
                is JsonObject -> {
                    val exact = current[segment]
                    val key = if (exact != null) segment else current.keys.firstOrNull { it.equals(segment, ignoreCase = true) }
                    if (key == null) return WorldPath(resolved + path.segments.drop(offset))
                    current = current.getValue(key)
                    resolved += key
                }
                is JsonArray -> {
                    val element = segment.toIntOrNull()?.let { current.getOrNull(it) } ?: return WorldPath(resolved + path.segments.drop(offset))
                    current = element
                    resolved += segment
                }
                else -> return WorldPath(resolved + path.segments.drop(offset))
            }
        }
        return WorldPath(resolved)
    }

    /** Writes [value] at [segments] inside [container], creating intermediate objects. */
    fun setting(value: JsonElement, segments: List<String>, container: JsonElement?, path: WorldPath): JsonElement {
        val key = segments.firstOrNull() ?: return value
        val rest = segments.drop(1)
        return when (container) {
            null, JsonNull -> JsonObject(linkedMapOf(key to setting(value, rest, null, path)))
            is JsonObject -> JsonObject(LinkedHashMap(container).apply { put(key, setting(value, rest, container[key], path)) })
            is JsonArray -> {
                val prefix = WorldPath(path.segments.take(path.segments.size - segments.size))
                val index = key.toIntOrNull()
                if (index == null || index < 0 || index > container.size) {
                    throw WorldStateError(
                        path.toString(),
                        "'$prefix' is a list of ${container.size} items; '$key' is not a valid index (use 0-${container.size}, where ${container.size} appends).",
                    )
                }
                val items = container.toMutableList()
                if (index == items.size) items += setting(value, rest, null, path) else items[index] = setting(value, rest, items[index], path)
                JsonArray(items)
            }
            is JsonPrimitive -> {
                val prefix = WorldPath(path.segments.take(path.segments.size - segments.size))
                throw WorldStateError(path.toString(), "Cannot write '$path': '$prefix' is ${typeName(container)}, not an object or list.")
            }
        }
    }

    /** Removes the value at [segments]. Returns the new container and the removed value (`null` if there was none). */
    fun removing(segments: List<String>, container: JsonElement): Pair<JsonElement, JsonElement?> {
        val key = segments.firstOrNull() ?: return container to null
        val rest = segments.drop(1)
        return when (container) {
            is JsonObject -> {
                val child = container[key] ?: return container to null
                if (rest.isEmpty()) {
                    JsonObject(LinkedHashMap(container).apply { remove(key) }) to child
                } else {
                    val (updated, removed) = removing(rest, child)
                    if (removed == null) container to null else JsonObject(LinkedHashMap(container).apply { put(key, updated) }) to removed
                }
            }
            is JsonArray -> {
                val index = key.toIntOrNull()?.takeIf { it in container.indices } ?: return container to null
                val items = container.toMutableList()
                if (rest.isEmpty()) {
                    val removed = items.removeAt(index)
                    JsonArray(items) to removed
                } else {
                    val (updated, removed) = removing(rest, items[index])
                    if (removed == null) return container to null
                    items[index] = updated
                    JsonArray(items) to removed
                }
            }
            else -> container to null
        }
    }

    /** Applies an RFC 7386 JSON Merge Patch, recording leaf-level changes. */
    fun merging(
        patch: JsonElement,
        target: JsonElement?,
        path: WorldPath,
        changes: MutableList<WorldStateChange>,
        depth: Int = 0,
    ): JsonElement {
        if (patch !is JsonObject || depth >= MAX_MERGE_DEPTH) {
            if (target != patch) changes += WorldStateChange(path.toString(), target, patch)
            return patch
        }
        if (target !is JsonObject) {
            // Replacing a non-object: report one change for the whole subtree.
            val merged = merging(patch, EmptyJsonObject, path, ArrayList(), depth + 1)
            changes += WorldStateChange(path.toString(), target, merged)
            return merged
        }
        val result = LinkedHashMap(target)
        for ((key, value) in patch) {
            val childPath = path.appending(key)
            if (value.isJsonNull) {
                result.remove(key)?.let { old -> changes += WorldStateChange(childPath.toString(), old, null) }
            } else {
                result[key] = merging(value, target[key], childPath, changes, depth + 1)
            }
        }
        return JsonObject(result)
    }

    /** A copy of [root] containing only the subtrees under [paths] that lie within [base], relative to [base]. */
    fun filtered(root: JsonElement, paths: List<WorldPath>, base: WorldPath): JsonElement {
        var result: JsonElement = EmptyJsonObject
        for (path in paths) {
            if (!path.isWithin(base)) continue
            val found = value(path, root) ?: continue
            val relative = WorldPath(path.segments.drop(base.segments.size))
            if (relative.isRoot) return found
            result = try {
                setting(found, relative.segments, result, relative)
            } catch (_: WorldStateError) {
                result
            }
        }
        return result
    }

    private val jsonNumber = Regex("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?")

    /**
     * Whether [element] contains only real JSON values. kotlinx's tree parser
     * accepts bare words as unquoted literals, which would later serialize as
     * invalid JSON.
     */
    fun isStrictJson(element: JsonElement): Boolean = when (element) {
        JsonNull -> true
        is JsonPrimitive -> element.isString || element.content == "true" || element.content == "false" || jsonNumber.matches(element.content)
        is JsonArray -> element.all(::isStrictJson)
        is JsonObject -> element.values.all(::isStrictJson)
    }

    fun typeName(value: JsonElement): String = when (value) {
        JsonNull -> "null"
        is JsonObject -> "an object"
        is JsonArray -> "a list"
        is JsonPrimitive -> when {
            value.isString -> "a string"
            value.content == "true" || value.content == "false" -> "a boolean"
            else -> "a number"
        }
    }

    private const val MAX_MERGE_DEPTH = 64
}
