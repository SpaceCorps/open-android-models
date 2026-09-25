package com.spacecorps.oam.game

import com.spacecorps.oam.AgentTool
import com.spacecorps.oam.EmptyJsonObject
import com.spacecorps.oam.jsonObjectOf
import com.spacecorps.oam.jsonOf
import com.spacecorps.oam.toJsonString
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A change to a [WorldState], delivered to observers.
 *
 * JSON form (as on open-apple-models' wire): `{"path", "oldValue"?, "newValue"?}`,
 * where a missing value (created or removed) is omitted and a JSON `null`
 * value is written as `null`.
 *
 * @property path The canonical dot path that changed (`""` for the root).
 * @property oldValue The value before the change (`null` if there was none; [JsonNull] for a JSON null).
 * @property newValue The value after the change (`null` if it was removed).
 */
@Serializable(with = WorldStateChangeSerializer::class)
public data class WorldStateChange(
    public val path: String,
    public val oldValue: JsonElement?,
    public val newValue: JsonElement?,
)

/**
 * A thread-safe JSON blackboard holding the game state that AI characters
 * can see and change.
 *
 * Values are addressed by dot paths (`"player.gold"`, `"npcs.gorm.mood"`,
 * `"party.0.name"`; see [WorldPath]). The game writes state as it changes;
 * NPCs read it through [tools] or get it injected into their prompt with
 * [summary] (see [NPCOptions.worldContextPaths]).
 *
 * ```kotlin
 * val world = WorldState.of("player" to mapOf("name" to "Aria", "gold" to 12))
 * world["npcs.gorm.mood"] = "grumpy"
 * world["player.gold"]                         // 12
 * val observation = world.observe("player") { change -> println(change.path) }
 * ```
 *
 * The root is always a JSON object. All operations are atomic; observers
 * run synchronously on the mutating thread, after the change is applied and
 * outside the internal lock.
 *
 * Serializable as its document (see [snapshot]); key order is kept.
 *
 * @param root The initial document.
 */
@Serializable(with = WorldStateSerializer::class)
public class WorldState(root: JsonObject = EmptyJsonObject) {
    private class Observer(val path: WorldPath?, val handler: (WorldStateChange) -> Unit)

    private val lock = Any()
    private var document: JsonObject = root
    private var changeCount = 0L
    private val observers = LinkedHashMap<Long, Observer>()
    private var nextObserverId = 0L

    // MARK: Reading

    /**
     * The value at [path], or `null` if there is none (or the path is
     * invalid). Keys match exactly; see [tools] for the lenient matching
     * offered to the model.
     */
    public operator fun get(path: String): JsonElement? {
        val parsed = parseOrNull(path) ?: return null
        return synchronized(lock) { WorldDocument.value(parsed, document) }
    }

    /** Decodes the value at [path] with [deserializer], or returns `null` if it is absent or does not fit. */
    public fun <T> get(path: String, deserializer: DeserializationStrategy<T>): T? {
        val value = get(path) ?: return null
        return try {
            GameJson.decodeFromJsonElement(deserializer, value)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Whether a value (possibly JSON `null`) exists at [path]. */
    public operator fun contains(path: String): Boolean = get(path) != null

    /** A copy of the whole document. */
    public fun snapshot(): JsonObject = synchronized(lock) { document }

    /** Increments on every change. Cheap to poll from a game loop. */
    public val version: Long get() = synchronized(lock) { changeCount }

    // MARK: Writing

    /**
     * Writes [value] at [path], creating intermediate objects as needed.
     * Writing index `n` of an `n`-element list appends. [value] is anything
     * [jsonOf] accepts, including a [JsonElement]; `null` stores JSON `null`
     * (use [remove] to delete).
     *
     * @throws WorldStateError if the path is invalid, crosses a value that is
     *   not an object or list, uses an invalid list index, or replaces the root with a non-object.
     * @throws IllegalArgumentException if [value] cannot be converted to JSON.
     */
    public operator fun set(path: String, value: Any?) {
        val element = jsonOf(value)
        apply(WorldPath.parse(path)) { element }
    }

    /** Writes [value] at [path], encoded with [serializer] (see [set]). */
    public fun <T> set(path: String, value: T, serializer: SerializationStrategy<T>) {
        set(path, GameJson.encodeToJsonElement(serializer, value))
    }

    /**
     * Removes the value at [path] and returns it (`null` if there was none).
     * Removing from a list shifts the following elements. Removing the root
     * empties the document.
     *
     * @throws WorldStateError if the path is invalid.
     */
    public fun remove(path: String): JsonElement? {
        val parsed = WorldPath.parse(path)
        if (parsed.isRoot) {
            val old = snapshot()
            replace(EmptyJsonObject)
            return old
        }
        var removed: JsonElement? = null
        val deliveries = synchronized(lock) {
            val (updated, value) = WorldDocument.removing(parsed.segments, document)
            if (value == null) return null
            removed = value
            document = updated as JsonObject
            changeCount++
            deliveries(listOf(WorldStateChange(parsed.toString(), value, null)))
        }
        deliver(deliveries)
        return removed
    }

    /**
     * Atomically reads and rewrites the value at [path]: [transform] receives
     * the current value (`null` if absent) and returns the new one (`null`
     * removes it). Useful for counters touched by both the game loop and tools:
     *
     * ```kotlin
     * world.modify("player.gold") { gold -> JsonPrimitive((gold?.intValue ?: 0) - 45) }
     * ```
     *
     * [transform] runs while the world is locked: it must not access this
     * world. If it throws, nothing changes and the exception propagates.
     *
     * @throws WorldStateError if the path or the result is invalid.
     */
    public fun modify(path: String, transform: (JsonElement?) -> JsonElement?) {
        apply(WorldPath.parse(path), transform)
    }

    /**
     * Replaces the whole document.
     *
     * @throws WorldStateError if [root] is not a JSON object.
     */
    public fun replace(root: JsonElement) {
        apply(WorldPath.ROOT) { root }
    }

    /**
     * Applies an RFC 7386 JSON Merge Patch at [path]: objects merge
     * recursively, `null` members delete keys, anything else replaces.
     * Observers receive one change per modified leaf.
     *
     * @throws WorldStateError if the path is invalid or the result is not a valid document.
     */
    public fun merge(patch: JsonElement, path: String = "") {
        val parsed = WorldPath.parse(path)
        val deliveries = synchronized(lock) {
            val changes = ArrayList<WorldStateChange>()
            val current = WorldDocument.value(parsed, document)
            val merged = WorldDocument.merging(patch, current, parsed, changes)
            if (changes.isEmpty()) return
            val root = WorldDocument.setting(merged, parsed.segments, document, parsed)
            document = root as? JsonObject ?: throw WorldStateError(path, "The world state root must be a JSON object.")
            changeCount++
            deliveries(changes)
        }
        deliver(deliveries)
    }

    private fun apply(path: WorldPath, transform: (JsonElement?) -> JsonElement?) {
        val deliveries = synchronized(lock) {
            val old = WorldDocument.value(path, document)
            val new = transform(old)
            if (new == old) return
            document = when {
                path.isRoot -> new as? JsonObject ?: throw WorldStateError("", "The world state root must be a JSON object.")
                new != null -> WorldDocument.setting(new, path.segments, document, path) as JsonObject
                else -> WorldDocument.removing(path.segments, document).first as JsonObject
            }
            changeCount++
            deliveries(listOf(WorldStateChange(path.toString(), old, new)))
        }
        deliver(deliveries)
    }

    // MARK: Observing

    /**
     * Calls [handler] for every change at, inside or above [path] (`""`
     * observes everything). A change above the path (for example replacing
     * `player` while observing `player.gold`) reports the old and new values
     * of the changed ancestor. Handlers run on the mutating thread, in
     * registration order; they must not throw.
     *
     * @return The observation; [WorldObservation.cancel] (or `close`) stops it.
     */
    public fun observe(path: String = "", handler: (WorldStateChange) -> Unit): WorldObservation {
        val id = addObserver(path, handler)
        return WorldObservation { synchronized(lock) { observers.remove(id) } }
    }

    /**
     * Changes at, inside or above [path] as a cold [Flow]: observation starts
     * when collection starts and ends when the collector stops. Changes are
     * buffered, so a slow collector never blocks the mutating thread.
     */
    public fun changes(path: String = ""): Flow<WorldStateChange> = callbackFlow {
        val observation = observe(path) { change -> trySend(change) }
        awaitClose { observation.cancel() }
    }.buffer(Channel.UNLIMITED)

    private fun addObserver(path: String, handler: (WorldStateChange) -> Unit): Long {
        val parsed = parseOrNull(path)
        return synchronized(lock) {
            val id = ++nextObserverId
            observers[id] = Observer(parsed, handler)
            id
        }
    }

    /** Pairs each change with the observers interested in it (in registration order). Called under the lock. */
    private fun deliveries(changes: List<WorldStateChange>): List<Pair<WorldStateChange, List<(WorldStateChange) -> Unit>>> {
        if (observers.isEmpty()) return emptyList()
        val current = observers.values.toList()
        return changes.mapNotNull { change ->
            val changed = if (change.path.isEmpty()) WorldPath.ROOT else WorldPath(change.path.split('.'))
            val handlers = current.filter { it.path?.overlaps(changed) == true }.map { it.handler }
            if (handlers.isEmpty()) null else change to handlers
        }
    }

    private fun deliver(deliveries: List<Pair<WorldStateChange, List<(WorldStateChange) -> Unit>>>) {
        for ((change, handlers) in deliveries) handlers.forEach { it(change) }
    }

    // MARK: Model access

    /**
     * Tools that let the model read (and optionally change) this world.
     *
     * - `read_world_state(path)` returns the JSON at `path`, limited to the
     *   [readable] prefixes. Reading an ancestor of a readable prefix returns
     *   only the readable parts. Large values are shortened to a key listing.
     * - `update_world_state(path, value)` (only when [writable] is not empty)
     *   writes a value under one of the [writable] prefixes. The value keeps
     *   the type already stored at the path: `"45"` for a number becomes `45`,
     *   and `"lots"` is rejected.
     *
     * Mistakes (unknown paths, forbidden paths, wrong types) come back to the
     * model as error outputs that name the valid alternatives, for example
     * `No value at 'player.mana'. 'player' has keys: name, gold, hp.`, so it
     * can recover. Keys match case-insensitively, and `party[0].name` or
     * `player/gold` work as well as `party.0.name`.
     *
     * @param readable Path prefixes the model may read (`""` = everything). Empty omits the read tool.
     * @param writable Path prefixes the model may write. Empty (the default) omits the write tool.
     * @param maxOutputChars Longest JSON returned before a value is shortened.
     */
    public fun tools(
        readable: List<String> = listOf(""),
        writable: List<String> = emptyList(),
        maxOutputChars: Int = 1500,
    ): List<AgentTool> = WorldStateTools.tools(this, readable, writable, maxOutputChars)

    /**
     * Renders the values at [paths] as compact `path: value` lines, for
     * injecting game state directly into a prompt (cheaper than a tool round
     * for small, always-relevant facts):
     * ```
     * player.name: Aria
     * player.gold: 12
     * time_of_day: night
     * ```
     * Objects are flattened to their leaves, strings are written without
     * quotes, lists as compact JSON. Missing and invalid paths are skipped.
     *
     * @param maxLines Longest summary; the rest is replaced by a `(n more not shown)` line.
     */
    public fun summary(paths: List<String>, maxLines: Int = 30): String = WorldStateTools.summary(snapshot(), paths, maxLines)

    // MARK: Serialization

    /** The document as JSON text (key order kept). */
    public fun toJsonString(): String = snapshot().toJsonString()

    override fun toString(): String = "WorldState(version=$version, ${toJsonString()})"

    public companion object {
        /** Name of the read tool created by [tools]. */
        public const val READ_TOOL_NAME: String = "read_world_state"

        /** Name of the write tool created by [tools]. */
        public const val UPDATE_TOOL_NAME: String = "update_world_state"

        /** A world from key-value pairs; values go through [jsonOf] (maps, lists, numbers, strings, …). */
        public fun of(vararg pairs: Pair<String, Any?>): WorldState = WorldState(jsonObjectOf(*pairs))

        /**
         * A world from a JSON value, which must be an object.
         *
         * @throws WorldStateError otherwise.
         */
        public fun fromJson(json: JsonElement): WorldState {
            val root = json as? JsonObject
                ?: throw WorldStateError("", "The world state root must be a JSON object, not ${WorldDocument.typeName(json)}.")
            return WorldState(root)
        }

        /**
         * A world from JSON text (key order kept).
         *
         * @throws WorldStateError if the text is not a JSON object.
         */
        public fun parse(text: String): WorldState {
            val element = try {
                GameJson.parseToJsonElement(text)
            } catch (error: SerializationException) {
                throw WorldStateError("", "The world state is not valid JSON: ${error.message}")
            }
            if (!WorldDocument.isStrictJson(element)) throw WorldStateError("", "The world state is not valid JSON: it contains unquoted text.")
            return fromJson(element)
        }

        private fun parseOrNull(path: String): WorldPath? = try {
            WorldPath.parse(path)
        } catch (_: WorldStateError) {
            null
        }
    }
}

/** Keeps a [WorldState] observation alive until [cancel] or [close]. */
public class WorldObservation internal constructor(cancel: () -> Unit) : AutoCloseable {
    private val lock = Any()
    private var action: (() -> Unit)? = cancel

    /** True until the observation is cancelled. */
    public val isActive: Boolean get() = synchronized(lock) { action != null }

    /** Stops the observation. Safe to call more than once. */
    public fun cancel() {
        val pending = synchronized(lock) {
            val current = action
            action = null
            current
        }
        pending?.invoke()
    }

    /** Same as [cancel]. */
    override fun close() {
        cancel()
    }
}

internal object WorldStateSerializer : KSerializer<WorldState> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: WorldState) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("WorldState can only be written as JSON.")
        json.encodeJsonElement(value.snapshot())
    }

    override fun deserialize(decoder: Decoder): WorldState {
        val json = decoder as? JsonDecoder ?: throw SerializationException("WorldState can only be read from JSON.")
        val element = json.decodeJsonElement()
        return try {
            WorldState.fromJson(element)
        } catch (error: WorldStateError) {
            throw SerializationException(error.message, error)
        }
    }
}

internal object WorldStateChangeSerializer : KSerializer<WorldStateChange> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: WorldStateChange) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("WorldStateChange can only be written as JSON.")
        val fields = LinkedHashMap<String, JsonElement>()
        fields["path"] = JsonPrimitive(value.path)
        value.oldValue?.let { fields["oldValue"] = it }
        value.newValue?.let { fields["newValue"] = it }
        json.encodeJsonElement(JsonObject(fields))
    }

    override fun deserialize(decoder: Decoder): WorldStateChange {
        val json = decoder as? JsonDecoder ?: throw SerializationException("WorldStateChange can only be read from JSON.")
        val element = json.decodeJsonElement() as? JsonObject ?: throw SerializationException("A world state change must be an object.")
        val path = (element["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw SerializationException("A world state change needs a string 'path'.")
        return WorldStateChange(path, element["oldValue"], element["newValue"])
    }
}
