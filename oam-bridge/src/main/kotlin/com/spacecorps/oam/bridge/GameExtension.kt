package com.spacecorps.oam.bridge

import com.spacecorps.oam.bridge.BridgeCoding.obj
import com.spacecorps.oam.game.NPC
import com.spacecorps.oam.game.WorldObservation
import com.spacecorps.oam.game.WorldPath
import com.spacecorps.oam.game.WorldState
import com.spacecorps.oam.game.WorldStateError
import com.spacecorps.oam.objectValue
import com.spacecorps.oam.stringValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * The game method set: NPC dialogue (`npc/…`), decisions (`decision/…`),
 * shared world state (`world/…`) and content generation (`content/generate`),
 * on top of oam-game. Part of [BridgeConfiguration.standardExtensions], so
 * every transport serves these methods by default. See `docs/PROTOCOL.md`.
 *
 * Kotlin hosts can share objects with bridge clients: add a [WorldState] the
 * game already owns with [addWorld], or reach an NPC a client created with [npc].
 *
 * ```kotlin
 * val game = GameExtension()
 * game.addWorld(worldState, id = "main")
 * val engine = BridgeEngine(BridgeConfiguration(systemModel = nano, extensions = listOf(game))) { line -> send(line) }
 * ```
 *
 * An extension instance belongs to one engine: create a new one per engine.
 *
 * @param maxNpcs Most NPCs alive at once; `npc/create` fails with `limit_reached` beyond it.
 * @param maxWorlds Most worlds alive at once.
 * @param maxSubscriptions Most `world/subscribe` subscriptions alive at once.
 */
public class GameExtension(
    maxNpcs: Int = 128,
    maxWorlds: Int = 64,
    maxSubscriptions: Int = 256,
) : BridgeExtension {
    /** Most NPCs alive at once. */
    public val maxNpcs: Int = maxNpcs.coerceAtLeast(1)

    /** Most worlds alive at once. */
    public val maxWorlds: Int = maxWorlds.coerceAtLeast(1)

    /** Most world subscriptions alive at once. */
    public val maxSubscriptions: Int = maxSubscriptions.coerceAtLeast(1)

    private val lock = Any()
    private val npcs = LinkedHashMap<String, NPCEntry>()
    private val worlds = LinkedHashMap<String, WorldEntry>()
    private val subscriptions = LinkedHashMap<String, WorldSubscription>()
    private var nextNpc = 1L
    private var nextWorld = 1L
    private var nextSubscription = 1L

    // MARK: BridgeExtension

    override fun register(registry: BridgeMethodRegistry, engine: BridgeEngine) {
        NPCMethods.register(registry, this)
        DecisionMethods.register(registry, this)
        WorldMethods.register(registry, this)
    }

    /** `npc/event` (streamed dialogue) and `world/changed` (subscriptions). */
    override val notificationMethods: List<String> get() = listOf("npc/event", "world/changed")

    /** Cancels every NPC turn and removes all NPCs, worlds and subscriptions. */
    override suspend fun shutdown() {
        val (entries, observations) = synchronized(lock) {
            val taken = npcs.values.toList() to subscriptions.values.map { it.observation }
            npcs.clear()
            worlds.clear()
            subscriptions.clear()
            taken
        }
        entries.forEach { it.close() }
        observations.forEach { it.cancel() }
    }

    // MARK: In-process access

    /** The NPC with id [id], if any. */
    public fun npc(id: String): NPC? = synchronized(lock) { npcs[id]?.npc }

    /** The world with id [id], if any. */
    public fun world(id: String): WorldState? = synchronized(lock) { worlds[id]?.world }

    /** Ids of live NPCs, in creation order. */
    public val npcIds: List<String> get() = synchronized(lock) { npcs.keys.toList() }

    /** Ids of live worlds, in creation order. */
    public val worldIds: List<String> get() = synchronized(lock) { worlds.keys.toList() }

    /**
     * Makes a world the host already owns available to bridge clients under
     * [id] (for `world/…` methods and `npc/create {"world": id}`). Changes
     * either side makes are visible to the other.
     *
     * @throws BridgeError `world_exists`, `limit_reached` or `invalid_params`.
     */
    public fun addWorld(world: WorldState, id: String) {
        validateId(id, "world")
        insertWorld(WorldEntry(id, world))
    }

    // MARK: NPC store

    internal fun npcEntry(id: String): NPCEntry = synchronized(lock) { npcs[id] } ?: throw BridgeError.npcNotFound(id)

    internal val npcEntries: List<NPCEntry> get() = synchronized(lock) { npcs.values.toList() }

    /** Validates a requested NPC id, or generates a free one (`npc1`, `npc2`, …). */
    internal fun npcId(requested: String?): String {
        if (requested != null) {
            validateId(requested, "npc")
            if (synchronized(lock) { requested in npcs }) throw BridgeError.npcExists(requested)
            return requested
        }
        return synchronized(lock) {
            var id: String
            do {
                id = "npc${nextNpc++}"
            } while (id in npcs)
            id
        }
    }

    internal fun insertNpc(entry: NPCEntry) {
        synchronized(lock) {
            if (entry.id in npcs) throw BridgeError.npcExists(entry.id)
            if (npcs.size >= maxNpcs) throw BridgeError.limitReached("NPCs", maxNpcs)
            npcs[entry.id] = entry
        }
    }

    /** Removes an NPC, cancels its queued and running work and closes it. */
    internal fun removeNpc(id: String): NPCEntry? {
        val entry = synchronized(lock) { npcs.remove(id) } ?: return null
        entry.close()
        return entry
    }

    /** Number of NPCs created with world [id]. */
    internal fun npcCount(inWorld: String): Int = synchronized(lock) { npcs.values.count { it.worldId == inWorld } }

    // MARK: World store

    internal fun worldEntry(id: String): WorldEntry = synchronized(lock) { worlds[id] } ?: throw BridgeError.worldNotFound(id)

    internal val worldEntries: List<WorldEntry> get() = synchronized(lock) { worlds.values.toList() }

    internal fun worldId(requested: String?): String {
        if (requested != null) {
            validateId(requested, "world")
            if (synchronized(lock) { requested in worlds }) throw BridgeError.worldExists(requested)
            return requested
        }
        return synchronized(lock) {
            var id: String
            do {
                id = "w${nextWorld++}"
            } while (id in worlds)
            id
        }
    }

    internal fun insertWorld(entry: WorldEntry) {
        synchronized(lock) {
            if (entry.id in worlds) throw BridgeError.worldExists(entry.id)
            if (worlds.size >= maxWorlds) throw BridgeError.limitReached("worlds", maxWorlds)
            worlds[entry.id] = entry
        }
    }

    /**
     * Removes a world and ends its subscriptions. NPCs created with it keep
     * using it; it is only no longer reachable by id.
     *
     * @return The entry and the number of subscriptions ended, or `null`.
     */
    internal fun removeWorld(id: String): Pair<WorldEntry, Int>? {
        val (entry, ended) = synchronized(lock) {
            val entry = worlds.remove(id) ?: return null
            val ended = subscriptions.values.filter { it.worldId == id }
            ended.forEach { subscriptions.remove(it.id) }
            entry to ended
        }
        ended.forEach { it.observation.cancel() }
        return entry to ended.size
    }

    // MARK: Subscriptions

    /** Observes [path] in world [worldId] and forwards each change as a `world/changed` notification. */
    internal fun subscribe(worldId: String, path: String, engine: BridgeEngine): WorldSubscription {
        val entry = worldEntry(worldId)
        try {
            WorldPath.parse(path)
        } catch (error: WorldStateError) {
            throw BridgeError.worldError(error, worldId)
        }
        val id = synchronized(lock) {
            if (subscriptions.size >= maxSubscriptions) throw BridgeError.limitReached("world subscriptions", maxSubscriptions)
            "sub${nextSubscription++}"
        }
        val observation = entry.world.observe(path) { change ->
            val params = linkedMapOf<String, JsonElement>("world" to JsonPrimitive(worldId), "subscription" to JsonPrimitive(id))
            params.putAll(GameCoding.json(change))
            engine.notify("world/changed", JsonObject(params))
        }
        val subscription = WorldSubscription(id, worldId, path, observation)
        // The world may have been deleted meanwhile.
        val inserted = synchronized(lock) {
            if (worlds[worldId] !== entry) return@synchronized false
            subscriptions[id] = subscription
            true
        }
        if (!inserted) {
            observation.cancel()
            throw BridgeError.worldNotFound(worldId)
        }
        return subscription
    }

    internal fun unsubscribe(id: String): WorldSubscription {
        val subscription = synchronized(lock) { subscriptions.remove(id) } ?: throw BridgeError.subscriptionNotFound(id)
        subscription.observation.cancel()
        return subscription
    }

    internal fun subscriptions(ofWorld: String): List<WorldSubscription> =
        synchronized(lock) { subscriptions.values.filter { it.worldId == ofWorld } }

    internal companion object {
        /** Validates a client-chosen id for NPCs and worlds (same rules as session ids). */
        fun validateId(id: String, parameter: String) {
            if (!BridgeCoding.isValidId(id)) throw BridgeError.invalidParams("'$parameter' must be 1-128 printable characters; got '$id'.")
        }
    }
}

// MARK: - Entries

/**
 * A live NPC and what the bridge needs to list, update and save it.
 *
 * @property toolDefinitions Client tool definitions as sent (echoed into save states).
 * @property toolTimeout Time limit for this NPC's client tools.
 */
internal class NPCEntry(
    val id: String,
    val npc: NPC,
    val worldId: String?,
    val modelKind: String,
    toolDefinitions: List<JsonElement>,
    toolTimeout: Duration?,
    context: CoroutineContext,
    val createdAt: Instant = Instant.now(),
) : AutoCloseable {
    private val lock = Any()
    private val scope = CoroutineScope(context + SupervisorJob())

    /** Serializes this NPC's turn-affecting requests in arrival order. */
    val queue = WorkQueue(scope)

    private var definitions: List<JsonElement> = toolDefinitions
    private var timeout: Duration? = toolTimeout

    val toolDefinitions: List<JsonElement> get() = synchronized(lock) { definitions }

    val toolTimeout: Duration? get() = synchronized(lock) { timeout }

    fun update(toolDefinitions: List<JsonElement>, toolTimeout: Duration?) {
        synchronized(lock) {
            definitions = toolDefinitions
            timeout = toolTimeout
        }
    }

    /** Tool names the model sees (client tools plus built-in world and memory tools). */
    val modelToolNames: List<String> get() = npc.transcript.tools.map { it.name }

    /** The `npc/list` entry. */
    val summary: JsonObject
        get() {
            val persona = npc.persona
            val pending = queue.pendingOperations
            return obj(
                "npc" to JsonPrimitive(id),
                "name" to JsonPrimitive(persona.name),
                "role" to JsonPrimitive(persona.role),
                "world" to (worldId?.let(::JsonPrimitive) ?: JsonNull),
                "model" to JsonPrimitive(modelKind),
                "tools" to JsonArray(toolDefinitions.mapNotNull { toolName(it)?.let(::JsonPrimitive) }),
                "turnCount" to JsonPrimitive(npc.turnCount),
                "relationship" to JsonPrimitive(npc.memory.relationship),
                "busy" to JsonPrimitive(pending > 0),
                "pendingOperations" to JsonPrimitive(pending),
                "createdAt" to JsonPrimitive(BridgeSession.timestamp(createdAt)),
            )
        }

    /**
     * Bridge-level settings stored next to the save state in `npc/state`, so
     * `npc/restore` can rebuild the NPC from the save alone.
     */
    val saveExtras: JsonObject
        get() {
            val options = LinkedHashMap(GameCoding.json(npc.options))
            options["toolTimeoutSeconds"] = BridgeCoding.number(BridgeCoding.seconds(toolTimeout))
            return obj(
                "npc" to JsonPrimitive(id),
                "options" to JsonObject(options),
                "tools" to JsonArray(toolDefinitions),
                "world" to (worldId?.let(::JsonPrimitive) ?: JsonNull),
            )
        }

    /** Cancels queued work and closes the NPC. */
    override fun close() {
        queue.cancelAll()
        npc.close()
        scope.cancel()
    }

    private fun toolName(definition: JsonElement): String? =
        definition.objectValue?.let { it["name"]?.stringValue ?: it["function"]?.objectValue?.get("name")?.stringValue }
}

/** A world addressable by id. */
internal class WorldEntry(val id: String, val world: WorldState, val createdAt: Instant = Instant.now())

/** A `world/subscribe` registration. */
internal class WorldSubscription(val id: String, val worldId: String, val path: String, val observation: WorldObservation) {
    val json: JsonObject get() = obj("subscription" to JsonPrimitive(id), "world" to JsonPrimitive(worldId), "path" to JsonPrimitive(path))
}
