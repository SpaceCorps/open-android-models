package com.spacecorps.oam.bridge

import com.spacecorps.oam.game.WorldState
import com.spacecorps.oam.jsonObjectOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** `world/…` methods (open-apple-models' WorldMethodTests). */
@Timeout(60)
class WorldTest {
    @Test
    fun createReadWriteAndDelete() = runBlocking<Unit> {
        val harness = BridgeHarness()
        assertEquals(j("""{"world": "village", "version": 0}"""), harness.result("world/create", """{"world": "village", "state": {"player": {"name": "Aria", "gold": 60}}}"""))
        assertEquals("w1", harness.result("world/create")["world"].str)

        assertEquals(
            j("""{"world": "village", "path": "player.gold", "value": 60, "exists": true, "version": 0}"""),
            harness.result("world/get", """{"world": "village", "path": "player.gold"}"""),
        )
        val missing = harness.result("world/get", """{"world": "village", "path": "player.horse"}""")
        assertEquals(JsonNull, missing["value"])
        assertEquals(JsonPrimitive(false), missing["exists"])

        assertEquals(
            j("""{"world": "village", "path": "quests.ring.stage", "version": 1}"""),
            harness.result("world/set", """{"world": "village", "path": "quests.ring.stage", "value": 1}"""),
        )
        // `null` is a value, not a deletion.
        harness.result("world/set", """{"world": "village", "path": "player.horse", "value": null}""")
        val horse = harness.result("world/get", """{"world": "village", "path": "player.horse"}""")
        assertEquals(JsonPrimitive(true), horse["exists"])
        assertEquals(JsonNull, horse["value"])

        assertEquals(3, harness.result("world/merge", """{"world": "village", "path": "player", "patch": {"gold": 15, "horse": null}}""")["version"].int)
        val removed = harness.result("world/remove", """{"world": "village", "path": "quests.ring"}""")
        assertEquals(JsonPrimitive(true), removed["removed"])
        assertEquals(j("""{"stage": 1}"""), removed["oldValue"])
        assertEquals(JsonPrimitive(false), harness.result("world/remove", """{"world": "village", "path": "quests.ring"}""")["removed"])

        val snapshot = harness.result("world/snapshot", """{"world": "village"}""")
        assertEquals(j("""{"player": {"name": "Aria", "gold": 15}, "quests": {}}"""), snapshot["state"])
        assertEquals(listOf("name", "gold"), snapshot["state"]["player"].keys)

        val list = harness.result("world/list")["worlds"] as JsonArray
        assertEquals(listOf("village", "w1"), list.map { it["world"].str })
        assertEquals(4, list[0]["version"].int)
        assertEquals(listOf("world", "version", "npcs", "subscriptions", "createdAt"), list[0].keys)

        assertEquals(j("""{"world": "village", "deleted": true, "endedSubscriptions": 0}"""), harness.result("world/delete", """{"world": "village"}"""))
        val gone = harness.call("world/get", """{"world": "village"}""")
        assertEquals(BridgeError.WORLD_NOT_FOUND, gone.errorCode)
        assertEquals("world_not_found", gone.errorName)
        assertEquals("village", gone["error"]["data"]["world"].str)
    }

    @Test
    fun errors() = runBlocking<Unit> {
        val harness = BridgeHarness()
        harness.result("world/create", """{"world": "village", "state": {"player": {"gold": 60}}}""")
        val duplicate = harness.call("world/create", """{"world": "village"}""")
        assertEquals("world_exists", duplicate.errorName)
        assertEquals(BridgeError.WORLD_EXISTS, duplicate.errorCode)
        assertEquals(BridgeError.INVALID_PARAMS, harness.call("world/create", """{"state": [1, 2]}""").errorCode)

        val throughNumber = harness.call("world/set", """{"world": "village", "path": "player.gold.copper", "value": 3}""")
        assertEquals(BridgeError.WORLD_ERROR, throughNumber.errorCode)
        assertEquals("world_error", throughNumber.errorName)
        assertEquals("village", throughNumber["error"]["data"]["world"].str)
        assertTrue(throughNumber["error"]["data"]["path"] != null)

        assertEquals("world_error", harness.call("world/set", """{"world": "village", "value": 3}""").errorName)
        assertEquals("world_error", harness.call("world/get", """{"world": "village", "path": "player..gold"}""").errorName)
        assertEquals("world_error", harness.call("world/subscribe", """{"world": "village", "path": "a..b"}""").errorName)
        assertEquals(BridgeError.INVALID_PARAMS, harness.call("world/set", """{"world": "village", "path": "x"}""").errorCode)
        assertEquals("subscription_not_found", harness.call("world/unsubscribe", """{"subscription": "sub99"}""").errorName)

        // Nothing above changed the world.
        assertEquals(0, harness.result("world/snapshot", """{"world": "village"}""")["version"].int)
    }

    @Test
    fun subscriptionsNotifyBeforeTheResponse() = runBlocking<Unit> {
        val harness = BridgeHarness()
        harness.result("world/create", """{"world": "village", "state": {"player": {"gold": 60}, "time": "dawn"}}""")
        val subscription = harness.result("world/subscribe", """{"world": "village", "path": "player"}""")
        assertEquals(j("""{"subscription": "sub1", "world": "village", "path": "player"}"""), subscription)
        val everything = harness.result("world/subscribe", """{"world": "village"}""")

        val set = harness.send("world/set", """{"world": "village", "path": "player.gold", "value": 45}""")
        harness.response(set)
        val changes = harness.box.messages.filter { it["method"].str == "world/changed" }
        assertEquals(2, changes.size)
        assertEquals(listOf(subscription["subscription"], everything["subscription"]), changes.map { it["params"]["subscription"] })
        assertEquals(
            j("""{"world": "village", "subscription": "sub1", "path": "player.gold", "oldValue": 60, "newValue": 45}"""),
            changes[0]["params"],
        )
        assertTrue(harness.box.index { it["method"].str == "world/changed" }!! < harness.responseIndex(set)!!)

        // Changes outside the path only reach the catch-all subscription.
        harness.result("world/set", """{"world": "village", "path": "time", "value": "dusk"}""")
        assertEquals(3, harness.box.messages.count { it["method"].str == "world/changed" })

        // Removal omits newValue.
        harness.result("world/remove", """{"world": "village", "path": "player.gold"}""")
        val removal = harness.box.messages.last { it["method"].str == "world/changed" }
        assertEquals(45, removal["params"]["oldValue"].int)
        assertTrue("newValue" !in (removal["params"] as JsonObject))

        assertEquals(JsonPrimitive(true), harness.result("world/unsubscribe", """{"subscription": "sub1"}""")["unsubscribed"])
        assertEquals(1, (harness.result("world/list")["worlds"][0]["subscriptions"] as JsonArray).size)
        assertEquals(1, harness.result("world/delete", """{"world": "village"}""")["endedSubscriptions"].int)
        assertEquals("subscription_not_found", harness.call("world/unsubscribe", JsonObject(mapOf("subscription" to everything["subscription"]!!))).errorName)
    }

    @Test
    fun hostWorldsAreShared() = runBlocking<Unit> {
        val game = GameExtension(maxWorlds = 2)
        val harness = BridgeHarness(BridgeConfiguration(modelAvailability = { BridgeHarness.TEST_AVAILABILITY }, extensions = listOf(game)))
        val world = WorldState(jsonObjectOf("time" to "noon"))
        game.addWorld(world, id = "main")
        assertEquals("noon", harness.result("world/get", """{"world": "main", "path": "time"}""")["value"].str)
        harness.result("world/set", """{"world": "main", "path": "time", "value": "night"}""")
        assertEquals(JsonPrimitive("night"), world["time"])
        assertSame(world, game.world("main"))

        harness.result("world/create")
        assertEquals("limit_reached", harness.call("world/create").errorName)
        assertFailsWith<BridgeError> { game.addWorld(WorldState(), id = "main") }
        assertFailsWith<BridgeError> { game.addWorld(WorldState(), id = "") }
    }
}
