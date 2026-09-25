package com.spacecorps.oam.bridge

import com.spacecorps.oam.bridge.BridgeCoding.obj
import com.spacecorps.oam.game.WorldPath
import com.spacecorps.oam.game.WorldState
import com.spacecorps.oam.game.WorldStateError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `world/…` methods. World operations are synchronous and atomic, so they
 * answer immediately (in arrival order). Change notifications caused by a
 * request are sent before its response: observers run on the mutating thread.
 */
internal object WorldMethods {
    fun register(registry: BridgeMethodRegistry, game: GameExtension) {
        registry.register("world/create") { request ->
            val params = request.params
            val id = game.worldId(params.optionalString("world"))
            val state = params["state"]
            val world = when (state) {
                null -> WorldState()
                is JsonObject -> WorldState(state)
                else -> throw BridgeError.invalidParams("'state' must be a JSON object (the world root).")
            }
            game.insertWorld(WorldEntry(id, world))
            BridgeReply.Result(obj("world" to JsonPrimitive(id), "version" to JsonPrimitive(world.version)))
        }
        registry.register("world/get") { request ->
            val (id, world) = lookup(request, game)
            val path = request.params.optionalString("path") ?: ""
            validate(path, id)
            val value = world[path]
            BridgeReply.Result(
                obj(
                    "world" to JsonPrimitive(id),
                    "path" to JsonPrimitive(path),
                    "value" to (value ?: JsonNull),
                    "exists" to JsonPrimitive(value != null),
                    "version" to JsonPrimitive(world.version),
                ),
            )
        }
        registry.register("world/set") { request ->
            val (id, world) = lookup(request, game)
            val path = request.params.optionalString("path") ?: ""
            // `null` is a legitimate value here, so read the raw member.
            val value = request.params.obj["value"]
                ?: throw BridgeError.invalidParams("Missing required parameter 'value' (use world/remove to delete).")
            worldOperation(id) { world[path] = value }
            BridgeReply.Result(obj("world" to JsonPrimitive(id), "path" to JsonPrimitive(path), "version" to JsonPrimitive(world.version)))
        }
        registry.register("world/merge") { request ->
            val (id, world) = lookup(request, game)
            val path = request.params.optionalString("path") ?: ""
            val patch = request.params.obj["patch"] ?: throw BridgeError.invalidParams("Missing required parameter 'patch'.")
            worldOperation(id) { world.merge(patch, path) }
            BridgeReply.Result(obj("world" to JsonPrimitive(id), "path" to JsonPrimitive(path), "version" to JsonPrimitive(world.version)))
        }
        registry.register("world/remove") { request ->
            val (id, world) = lookup(request, game)
            val path = request.params.string("path")
            val removed = worldOperation(id) { world.remove(path) }
            BridgeReply.Result(
                obj(
                    "world" to JsonPrimitive(id),
                    "path" to JsonPrimitive(path),
                    "removed" to JsonPrimitive(removed != null),
                    "oldValue" to (removed ?: JsonNull),
                    "version" to JsonPrimitive(world.version),
                ),
            )
        }
        registry.register("world/snapshot") { request ->
            val (id, world) = lookup(request, game)
            BridgeReply.Result(obj("world" to JsonPrimitive(id), "state" to world.snapshot(), "version" to JsonPrimitive(world.version)))
        }
        registry.register("world/delete") { request ->
            val id = request.params.string("world")
            val (_, ended) = game.removeWorld(id) ?: throw BridgeError.worldNotFound(id)
            BridgeReply.Result(obj("world" to JsonPrimitive(id), "deleted" to JsonPrimitive(true), "endedSubscriptions" to JsonPrimitive(ended)))
        }
        registry.register("world/list") {
            val worlds = game.worldEntries.map { entry ->
                obj(
                    "world" to JsonPrimitive(entry.id),
                    "version" to JsonPrimitive(entry.world.version),
                    "npcs" to JsonPrimitive(game.npcCount(entry.id)),
                    "subscriptions" to JsonArray(game.subscriptions(entry.id).map { it.json }),
                    "createdAt" to JsonPrimitive(BridgeSession.timestamp(entry.createdAt)),
                )
            }
            BridgeReply.Result(obj("worlds" to JsonArray(worlds)))
        }
        registry.register("world/subscribe") { request ->
            val id = request.params.string("world")
            val path = request.params.optionalString("path") ?: ""
            BridgeReply.Result(game.subscribe(id, path, request.engine).json)
        }
        registry.register("world/unsubscribe") { request ->
            val subscription = game.unsubscribe(request.params.string("subscription"))
            BridgeReply.Result(JsonObject(subscription.json + ("unsubscribed" to JsonPrimitive(true))))
        }
    }

    private fun lookup(request: BridgeRequest, game: GameExtension): Pair<String, WorldState> {
        val id = request.params.string("world")
        return id to game.worldEntry(id).world
    }

    private fun validate(path: String, world: String) {
        worldOperation(world) { WorldPath.parse(path) }
    }

    /** Runs [operation], mapping [WorldStateError] to `world_error` for world [id]. */
    private inline fun <T> worldOperation(id: String, operation: () -> T): T = try {
        operation()
    } catch (error: WorldStateError) {
        throw BridgeError.worldError(error, id)
    }
}
