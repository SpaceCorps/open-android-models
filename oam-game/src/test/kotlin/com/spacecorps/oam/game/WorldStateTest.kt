package com.spacecorps.oam.game

import com.spacecorps.oam.ToolOutput
import com.spacecorps.oam.arrayValue
import com.spacecorps.oam.doubleValue
import com.spacecorps.oam.intValue
import com.spacecorps.oam.jsonObjectOf
import com.spacecorps.oam.jsonOf
import com.spacecorps.oam.objectValue
import com.spacecorps.oam.stringValue
import com.spacecorps.oam.toJsonString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldStateTest {
    private fun world() = WorldState.of(
        "player" to mapOf("name" to "Aria", "gold" to 12, "hp" to 30, "alive" to true),
        "party" to listOf(mapOf("name" to "Brom", "class" to "cleric"), mapOf("name" to "Lysa", "class" to "rogue")),
        "npcs" to mapOf("gorm" to mapOf("mood" to "grumpy")),
        "quests" to mapOf("lost_ring" to mapOf("status" to "active")),
    )

    // MARK: Paths

    @Test
    fun getNestedValuesAndIndices() {
        val world = world()
        assertEquals(JsonPrimitive(12), world["player.gold"])
        assertEquals(JsonPrimitive("Lysa"), world["party.1.name"])
        assertEquals(JsonPrimitive("cleric"), world["party[0].class"])
        assertEquals(JsonPrimitive("grumpy"), world["npcs/gorm/mood"])
        assertNull(world["player.missing"])
        assertNull(world["party.9.name"])
        assertNull(world["player..gold"])
        assertEquals(listOf("player", "party", "npcs", "quests"), world[""]?.objectValue?.keys?.toList())
        assertEquals(12, world.get("player.gold", serializer<Int>()))
        assertNull(world.get("player.name", serializer<Int>()))
        assertTrue("player.alive" in world)
        assertFalse("player.mana" in world)
    }

    @Test
    fun pathParsing() {
        assertEquals(listOf("party", "0", "name"), WorldPath.parse("party[0].name").segments)
        assertEquals(listOf("player", "gold"), WorldPath.parse("$.player.gold").segments)
        assertEquals(listOf("player", "gold"), WorldPath.parse("/player/gold").segments)
        assertEquals(listOf("0", "x"), WorldPath.parse("[0].x").segments)
        assertTrue(WorldPath.parse("").isRoot)
        assertTrue(WorldPath.parse(" $ ").isRoot)
        assertFailsWith<WorldStateError> { WorldPath.parse("a..b") }
        assertFailsWith<WorldStateError> { WorldPath.parse("player.") }
        val path = WorldPath.parse("player.gold")
        assertEquals("player.gold", path.toString())
        assertTrue(path.isWithin(WorldPath.parse("player")))
        assertTrue(WorldPath.parse("player").overlaps(path))
        assertFalse(path.overlaps(WorldPath.parse("party")))
        assertEquals(WorldPath.parse("player"), path.parent)
        assertNull(WorldPath.ROOT.parent)
    }

    // MARK: Writing

    @Test
    fun setCreatesIntermediatesAndAppends() {
        val world = world()
        world["npcs.mira.mood"] = "cheerful"
        assertEquals(JsonPrimitive("cheerful"), world["npcs.mira.mood"])
        world["party.2"] = mapOf("name" to "Kael")
        assertEquals(JsonPrimitive("Kael"), world["party.2.name"])
        world["party.0.name"] = "Bromm"
        assertEquals(JsonPrimitive("Bromm"), world["party.0.name"])
        assertEquals(3, world["party"]?.arrayValue?.size)
        world["player.title"] = null
        assertEquals(JsonNull, world["player.title"])
        world.set("player.stats", mapOf("str" to 9), serializer<Map<String, Int>>())
        assertEquals(9, world["player.stats.str"]?.intValue)
    }

    @Test
    fun setRejectsInvalidTargets() {
        val world = world()
        assertFailsWith<WorldStateError> { world["player.gold.amount"] = 3 }
        val index = assertFailsWith<WorldStateError> { world["party.7.name"] = "x" }
        assertTrue(index.message.contains("'party' is a list of 2 items"), index.message)
        assertFailsWith<WorldStateError> { world[""] = 5 }
        assertFailsWith<WorldStateError> { world.replace(jsonOf(listOf(1, 2))) }
        assertFailsWith<WorldStateError> { world["a..b"] = 1 }
        assertEquals(JsonPrimitive(12), world["player.gold"])
        assertEquals(0L, world.version)
    }

    @Test
    fun removeValuesAndListElements() {
        val world = world()
        assertEquals(JsonPrimitive(30), world.remove("player.hp"))
        assertNull(world["player.hp"])
        assertEquals(JsonPrimitive("Brom"), world.remove("party.0")?.objectValue?.get("name"))
        assertEquals(JsonPrimitive("Lysa"), world["party.0.name"])
        assertNull(world.remove("nothing.here"))
        assertEquals(2L, world.version)
        world.remove("")
        assertTrue(world.snapshot().isEmpty())
    }

    @Test
    fun mergePatchAddsReplacesAndDeletes() {
        val world = world()
        val changes = Collections.synchronizedList(ArrayList<WorldStateChange>())
        val observation = world.observe { changes += it }
        world.merge(jsonObjectOf("player" to mapOf("gold" to 20, "hp" to null, "title" to "Knight")))
        assertEquals(JsonPrimitive(20), world["player.gold"])
        assertNull(world["player.hp"])
        assertEquals(JsonPrimitive("Knight"), world["player.title"])
        assertEquals(JsonPrimitive("Aria"), world["player.name"])
        assertEquals(setOf("player.gold", "player.hp", "player.title"), changes.map { it.path }.toSet())
        val gold = changes.first { it.path == "player.gold" }
        assertEquals(JsonPrimitive(12), gold.oldValue)
        assertEquals(JsonPrimitive(20), gold.newValue)
        assertNull(changes.first { it.path == "player.hp" }.newValue)
        assertEquals(1L, world.version)
        // Merging into a scalar replaces it with an object.
        world.merge(jsonObjectOf("a" to 1), "player.name")
        assertEquals(1, world["player.name.a"]?.intValue)
        observation.cancel()
    }

    @Test
    fun modifyIsAtomicUnderConcurrency() = runBlocking {
        val world = WorldState.of("counter" to 0)
        (0 until 200).map {
            async(Dispatchers.Default) {
                world.modify("counter") { value -> JsonPrimitive((value?.doubleValue ?: 0.0) + 1) }
            }
        }.awaitAll()
        assertEquals(200.0, world["counter"]?.doubleValue)
        assertEquals(200L, world.version)
    }

    @Test
    fun modifyCanRemoveAndPropagatesErrors() {
        val world = world()
        world.modify("player.hp") { null }
        assertNull(world["player.hp"])
        assertFailsWith<IllegalStateException> { world.modify("player.gold") { error("nope") } }
        assertEquals(JsonPrimitive(12), world["player.gold"])
    }

    @Test
    fun unchangedWritesDoNotNotify() {
        val world = world()
        val changes = ArrayList<WorldStateChange>()
        world.observe { changes += it }
        world["player.gold"] = 12
        world.merge(jsonObjectOf("player" to mapOf("gold" to 12)))
        assertTrue(changes.isEmpty())
        assertEquals(0L, world.version)
    }

    // MARK: Observers

    @Test
    fun observersFilterByPathAndStopWhenCancelled() {
        val world = world()
        val player = ArrayList<WorldStateChange>()
        val gold = ArrayList<WorldStateChange>()
        val quests = ArrayList<WorldStateChange>()
        world.observe("player") { player += it }
        val goldObservation = world.observe("player.gold") { gold += it }
        world.observe("quests") { quests += it }

        world["player.gold"] = 50
        world["player.name"] = "Aria the Bold"
        // Replacing an ancestor notifies descendants' observers too.
        world["player"] = mapOf("gold" to 1)
        assertEquals(listOf("player.gold", "player.name", "player"), player.map { it.path })
        assertEquals(listOf("player.gold", "player"), gold.map { it.path })
        assertEquals(JsonPrimitive(12), gold[0].oldValue)
        assertEquals(JsonPrimitive(50), gold[0].newValue)
        assertTrue(quests.isEmpty())

        goldObservation.cancel()
        goldObservation.close()
        assertFalse(goldObservation.isActive)
        world["player.gold"] = 2
        assertEquals(2, gold.size)
        assertEquals(4, player.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun changesFlowStartsOnCollection() = runTest {
        val world = world()
        val received = async { world.changes("quests").first() }
        // Let the collector subscribe before writing.
        runCurrent()
        world["player.gold"] = 99
        world["quests.lost_ring.status"] = "done"
        val change = received.await()
        assertEquals("quests.lost_ring.status", change.path)
        assertEquals(JsonPrimitive("done"), change.newValue)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun changesFlowUnsubscribesWhenCollectionStops() = runTest {
        val world = world()
        val seen = ArrayList<String>()
        val job = launch { world.changes().collect { seen += it.path } }
        runCurrent()
        world["a"] = 1
        runCurrent()
        job.cancel()
        job.join()
        world["b"] = 2
        runCurrent()
        assertEquals(listOf("a"), seen)
    }

    // MARK: Persistence

    @Test
    fun serializationRoundTripKeepsKeyOrder() {
        val world = world()
        val text = GameJson.encodeToString(WorldState.serializer(), world)
        val restored = GameJson.decodeFromString(WorldState.serializer(), text)
        assertEquals(world.snapshot(), restored.snapshot())
        assertEquals(listOf("player", "party", "npcs", "quests"), WorldState.parse(world.toJsonString()).snapshot().keys.toList())
        assertFailsWith<WorldStateError> { WorldState.parse("[1]") }
        assertFailsWith<WorldStateError> { WorldState.parse("{nope") }
        assertFailsWith<WorldStateError> { WorldState.parse("""{"a": pleased}""") }

        val change = WorldStateChange("a", null, JsonNull)
        val encoded = GameJson.encodeToJsonElement(WorldStateChange.serializer(), change)
        assertEquals("""{"path":"a","newValue":null}""", encoded.toJsonString())
        assertEquals(change, GameJson.decodeFromJsonElement(WorldStateChange.serializer(), encoded))
    }

    // MARK: Summary

    @Test
    fun summaryFlattensLeaves() {
        val world = world()
        assertEquals(
            """
            player.name: Aria
            player.gold: 12
            player.hp: 30
            player.alive: true
            npcs.gorm.mood: grumpy
            party: [{"name":"Brom","class":"cleric"},{"name":"Lysa","class":"rogue"}]
            """.trimIndent(),
            world.summary(listOf("player", "npcs.gorm.mood", "missing.path", "party", "bad..path")),
        )
        assertTrue(world.summary(listOf("player"), maxLines = 2).endsWith("(2 more not shown)"))
        assertEquals("", world.summary(emptyList()))
    }

    // MARK: Tools

    @Test
    fun toolsRespectReadAndWritePermissions() = runTest {
        val world = world()
        val readOnly = world.tools()
        assertEquals(listOf("read_world_state"), readOnly.map { it.name })
        assertTrue(readOnly[0].description.contains("Top-level keys: player, party, npcs, quests"))

        val tools = world.tools(readable = listOf("player", "quests"), writable = listOf("quests"))
        assertEquals(listOf("read_world_state", "update_world_state"), tools.map { it.name })
        val (read, update) = tools
        assertTrue(read.description.contains("Readable paths: player, quests"))

        assertEquals(ToolOutput.Json(JsonPrimitive(12)), read.invoke(jsonObjectOf("path" to "player.gold")))
        // Case-insensitive keys.
        assertEquals(ToolOutput.Json(JsonPrimitive(12)), read.invoke(jsonObjectOf("path" to "Player.Gold")))
        // Ancestors show only the readable parts.
        val root = read.invoke(jsonObjectOf("path" to "")) as ToolOutput.Json
        assertEquals(listOf("player", "quests"), root.value.objectValue?.keys?.toList())
        // Forbidden and missing paths produce helpful errors.
        val forbidden = read.invoke(jsonObjectOf("path" to "npcs.gorm"))
        assertTrue(forbidden.isError && forbidden.modelText.contains("Readable paths: player, quests"), forbidden.modelText)
        val missing = read.invoke(jsonObjectOf("path" to "player.mana"))
        assertTrue(missing.isError && missing.modelText.contains("'player' has keys: name, gold, hp, alive"), missing.modelText)
        val invalid = read.invoke(jsonObjectOf("path" to "a..b"))
        assertTrue(invalid.isError)

        val updated = update.invoke(jsonObjectOf("path" to "quests.lost_ring.status", "value" to "done"))
        assertFalse(updated.isError)
        assertEquals("""Set quests.lost_ring.status to "done" (was "active").""", updated.modelText)
        assertEquals(JsonPrimitive("done"), world["quests.lost_ring.status"])
        val denied = update.invoke(jsonObjectOf("path" to "player.gold", "value" to "9999"))
        assertTrue(denied.isError && denied.modelText.contains("read-only"), denied.modelText)
        assertEquals(JsonPrimitive(12), world["player.gold"])
        assertTrue(world.tools(readable = emptyList()).isEmpty())
    }

    @Test
    fun updateToolKeepsStoredTypes() = runTest {
        val world = world()
        val update = world.tools(readable = emptyList(), writable = listOf(""))[0]
        assertEquals("update_world_state", update.name)
        assertTrue(update.description.contains("(everything)"))

        update.invoke(jsonObjectOf("path" to "player.gold", "value" to "45"))
        assertEquals(JsonPrimitive(45), world["player.gold"])
        update.invoke(jsonObjectOf("path" to "player.gold", "value" to 50))
        assertEquals(JsonPrimitive(50), world["player.gold"])
        update.invoke(jsonObjectOf("path" to "player.alive", "value" to "false"))
        assertEquals(JsonPrimitive(false), world["player.alive"])
        update.invoke(jsonObjectOf("path" to "player.name", "value" to "\"Aria\""))
        assertEquals(JsonPrimitive("Aria"), world["player.name"])
        update.invoke(jsonObjectOf("path" to "player.name", "value" to 7))
        assertEquals(JsonPrimitive("7"), world["player.name"])
        update.invoke(jsonObjectOf("path" to "player.buffs", "value" to "[\"haste\"]"))
        assertEquals(jsonOf(listOf("haste")), world["player.buffs"])
        update.invoke(jsonObjectOf("path" to "player.note", "value" to "likes swords"))
        assertEquals(JsonPrimitive("likes swords"), world["player.note"])
        // A bare word for a new key stays text (and the document stays valid JSON).
        update.invoke(jsonObjectOf("path" to "player.mood", "value" to "pleased"))
        assertEquals(JsonPrimitive("pleased"), world["player.mood"])
        update.invoke(jsonObjectOf("path" to "player.tags", "value" to "[haste]"))
        assertEquals(JsonPrimitive("[haste]"), world["player.tags"])
        update.invoke(jsonObjectOf("path" to "player.level", "value" to "3"))
        assertEquals(JsonPrimitive(3), world["player.level"])
        assertEquals(world.snapshot(), WorldState.parse(world.toJsonString()).snapshot())
        update.invoke(jsonObjectOf("path" to "Player.HP", "value" to "12.5"))
        assertEquals(JsonPrimitive(12.5), world["player.hp"])

        val wrongType = update.invoke(jsonObjectOf("path" to "player.gold", "value" to "lots"))
        assertTrue(wrongType.isError && wrongType.modelText.contains("not a number"), wrongType.modelText)
        val wrongBool = update.invoke(jsonObjectOf("path" to "player.alive", "value" to "maybe"))
        assertTrue(wrongBool.isError && wrongBool.modelText.contains("true or false"), wrongBool.modelText)
        val wrongList = update.invoke(jsonObjectOf("path" to "party", "value" to "Brom"))
        assertTrue(wrongList.isError && wrongList.modelText.contains("send valid JSON"), wrongList.modelText)
        val throughScalar = update.invoke(jsonObjectOf("path" to "player.gold.amount", "value" to 1))
        assertTrue(throughScalar.isError && throughScalar.modelText.contains("not an object or list"), throughScalar.modelText)
        assertEquals(JsonPrimitive(50), world["player.gold"])
    }

    @Test
    fun largeValuesAreShortened() = runTest {
        val items = (0 until 200).map { "item number $it" }
        val world = WorldState.of("log" to items, "big" to mapOf("a" to items, "b" to 1, "c" to "x".repeat(120)))
        val read = world.tools(maxOutputChars = 300)[0]
        val list = (read.invoke(jsonObjectOf("path" to "log")) as ToolOutput.Json).value
        assertTrue(list.objectValue?.get("_note")?.stringValue?.startsWith("Showing") == true)
        assertTrue(list.toJsonString().length < 400)
        val big = (read.invoke(jsonObjectOf("path" to "big")) as ToolOutput.Json).value.objectValue!!
        assertEquals(JsonPrimitive("[list of 200 items]"), big["a"])
        assertEquals(JsonPrimitive(1), big["b"])
        assertTrue(big["c"]?.stringValue?.endsWith("...") == true)
    }
}
