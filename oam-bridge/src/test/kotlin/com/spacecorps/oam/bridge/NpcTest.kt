package com.spacecorps.oam.bridge

import com.spacecorps.oam.game.NPC
import com.spacecorps.oam.game.WorldState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** `npc/…` methods with scripted models (open-apple-models' NPCMethodTests). */
@Timeout(60)
class NpcTest {
    private val inventory = BridgeHarness.CHECK_INVENTORY

    private fun reply(line: String, emotion: String = "neutral") = BridgeHarness.reply(line, emotion)

    @Test
    fun lifecycle() = runBlocking<Unit> {
        val harness = BridgeHarness()
        assertEquals(j("""{"npc": "gorm", "tools": [], "warnings": []}"""), harness.createNpc())
        assertEquals("npc1", harness.result("npc/create", """{"persona": {"name": "Mira"}, "model": "scripted"}""")["npc"].str)

        val duplicate = harness.call("npc/create", """{"npc": "gorm", "persona": {"name": "Gorm"}, "model": "scripted"}""")
        assertEquals(BridgeError.NPC_EXISTS, duplicate.errorCode)
        assertEquals("npc_exists", duplicate.errorName)

        val npcs = harness.result("npc/list")["npcs"] as JsonArray
        assertEquals(listOf("gorm", "npc1"), npcs.map { it["npc"].str })
        val gorm = npcs.first()
        assertEquals("Gorm", gorm["name"].str)
        assertEquals("the village blacksmith", gorm["role"].str)
        assertEquals("scripted", gorm["model"].str)
        assertEquals(JsonNull, gorm["world"])
        assertEquals(0, gorm["turnCount"].int)
        assertEquals(JsonPrimitive(false), gorm["busy"])
        assertEquals(
            listOf("npc", "name", "role", "world", "model", "tools", "turnCount", "relationship", "busy", "pendingOperations", "createdAt"),
            gorm.keys,
        )

        assertEquals(j("""{"npc": "gorm", "deleted": true}"""), harness.result("npc/delete", """{"npc": "gorm"}"""))
        val missing = harness.call("npc/talk", """{"npc": "gorm", "line": "Hello?"}""")
        assertEquals(-32050, missing.errorCode)
        assertEquals("npc_not_found", missing.errorName)
        assertEquals("gorm", missing["error"]["data"]["npc"].str)
        assertEquals(-32050, harness.call("npc/delete", """{"npc": "gorm"}""").errorCode)
    }

    @Test
    fun talkReturnsADialogueTurn() = runBlocking<Unit> {
        val harness = BridgeHarness()
        harness.createNpc(steps = "[${reply("Three swords, lad. Forty-five gold.", "proud")}]")
        val result = harness.result("npc/talk", """{"npc": "gorm", "line": "Got any swords?", "context": "The player just walked in."}""")
        assertEquals("gorm", result["npc"].str)
        assertEquals("Three swords, lad. Forty-five gold.", result["line"].str)
        assertEquals("proud", result["emotion"].str)
        assertEquals(listOf("Buy one.", "Goodbye."), result["playerOptions"].strings())
        assertEquals(JsonPrimitive(false), result["endsConversation"])
        assertEquals(j("[]"), result["toolCalls"])
        assertEquals(0, result["relationship"].int)
        assertEquals(JsonPrimitive(false), result["isFallback"])
        assertNotNull(result["usage"]["totalTokens"].int)
        assertEquals(
            listOf("npc", "line", "emotion", "playerOptions", "endsConversation", "toolCalls", "relationship", "isFallback", "usage"),
            result.keys,
        )
        assertEquals(1, harness.result("npc/list")["npcs"][0]["turnCount"].int)
    }

    @Test
    fun streamedEventsPrecedeTheResult() = runBlocking<Unit> {
        val harness = BridgeHarness()
        harness.createNpc(steps = "[${reply("Steel is patient, lad.", "amused")}]")
        val request = harness.send("npc/talk", """{"npc": "gorm", "line": "Any wisdom?", "stream": true}""")
        val result = harness.response(request)["result"]
        val events = harness.notifications("npc/event", request)
        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it["params"]["npc"].str == "gorm" })
        assertTrue(harness.box.lastIndex { it["method"].str == "npc/event" }!! < harness.responseIndex(request)!!)

        val payloads = events.mapNotNull { it["params"]["event"] }
        assertEquals("amused", payloads.first { it["type"].str == "emotion" }["emotion"].str)
        // Deltas and resets add up to the final line.
        var shown = ""
        for (event in payloads) {
            when (event["type"].str) {
                "lineDelta" -> shown += event["delta"].str
                "lineReset" -> shown = event["line"].str!!
            }
        }
        assertEquals("Steel is patient, lad.", shown)
        assertEquals("Steel is patient, lad.", result["line"].str)

        // Without `stream`, no events.
        harness.createNpc("quiet", steps = "[${reply("Hm.")}]")
        val quiet = harness.send("npc/talk", """{"npc": "quiet", "line": "Hi"}""")
        harness.response(quiet)
        assertTrue(harness.notifications("npc/event", quiet).isEmpty())
    }

    @Test
    fun clientToolRoundTrip() = runBlocking<Unit> {
        val harness = BridgeHarness()
        val calls = Collections.synchronizedList(ArrayList<JsonElement?>())
        harness.box.responder = MessageBox.ToolResponder { call, params ->
            calls += params
            assertEquals("check_inventory", call["name"].str)
            JsonObject(mapOf("output" to JsonObject(mapOf("item" to call["arguments"]["item"]!!, "stock" to JsonPrimitive(3), "price_gold" to JsonPrimitive(45)))))
        }
        val created = harness.createNpc(
            steps = """[{"toolCalls": [{"name": "check_inventory", "arguments": {"item": "iron sword"}}]}, ${reply("Three iron swords, forty-five gold each.")}]""",
            tools = "[$inventory]",
            options = """{"groundingTool": "check_inventory"}""",
        )
        assertEquals(listOf("check_inventory"), created["tools"].strings())

        val request = harness.send("npc/talk", """{"npc": "gorm", "line": "Iron swords?", "stream": true}""")
        val result = harness.response(request)["result"]
        assertEquals("Three iron swords, forty-five gold each.", result["line"].str)
        val record = result["toolCalls"][0]
        assertEquals("check_inventory", record["call"]["name"].str)
        assertEquals(j("""{"item": "iron sword"}"""), record["call"]["arguments"])
        assertEquals(3, record["output"]["stock"].int)
        assertEquals(JsonPrimitive(false), record["isError"])

        val toolCall = calls.first()
        assertEquals("gorm", toolCall["npc"].str)
        assertEquals(request, toolCall["requestId"].str)
        assertEquals(record["call"]["id"], toolCall["call"]["id"])

        val types = harness.events("npc/event", request).map { payload ->
            if (payload["type"].str == "toolCallStarted") "started:${payload["execution"].str}" else payload["type"].str
        }
        assertTrue(types.indexOf("started:client") in 0 until types.indexOf("toolCallCompleted"), "$types")
        assertTrue(harness.box.index { it["method"].str == "tool/call" }!! < harness.responseIndex(request)!!)
    }

    @Test
    fun clientToolErrorsReachTheModel() = runBlocking<Unit> {
        val harness = BridgeHarness()
        harness.box.responder = MessageBox.ToolResponder { _, _ -> j("""{"error": {"code": -32000, "message": "The shop is closed."}}""") }
        harness.createNpc(
            steps = """[{"toolCalls": [{"name": "check_inventory", "arguments": {"item": "axe"}}]}, ${reply("Shop's closed, lad.")}]""",
            tools = "[$inventory]",
        )
        val result = harness.result("npc/talk", """{"npc": "gorm", "line": "Axes?"}""")
        assertEquals(JsonPrimitive(true), result["toolCalls"][0]["isError"])
        assertEquals("The shop is closed.", result["toolCalls"][0]["output"].str)
        assertEquals("Shop's closed, lad.", result["line"].str)
    }

    @Test
    fun toolTimeoutSendsToolCancel() = runBlocking<Unit> {
        val harness = BridgeHarness()
        val tool = inventory.dropLast(1) + """, "timeoutSeconds": 0.05}"""
        harness.createNpc(
            steps = """[{"toolCalls": [{"name": "check_inventory", "arguments": {"item": "bow"}}]}, ${reply("My ledger is slow today.")}]""",
            tools = "[$tool]",
        )
        val request = harness.send("npc/talk", """{"npc": "gorm", "line": "Bows?"}""")
        val result = harness.response(request)["result"]
        assertEquals("My ledger is slow today.", result["line"].str)
        assertEquals(JsonPrimitive(true), result["toolCalls"][0]["isError"])
        val cancel = harness.box.wait(2.seconds, "tool/cancel") { it["method"].str == "tool/cancel" }
        val call = harness.box.messages.first { it["method"].str == "tool/call" }
        assertEquals(call["id"], cancel["params"]["id"])
        assertEquals("gorm", cancel["params"]["npc"].str)
        assertEquals(call["params"]["call"]["id"], cancel["params"]["callId"])
    }

    @Test
    fun cancelStopsAWaitingTurn() = runBlocking<Unit> {
        val harness = BridgeHarness(BridgeConfiguration(modelAvailability = { BridgeHarness.TEST_AVAILABILITY }, defaultToolTimeout = null))
        harness.createNpc(
            steps = """[{"toolCalls": [{"name": "check_inventory", "arguments": {"item": "shield"}}]}, ${reply("Never mind.")}]""",
            tools = "[$inventory]",
        )
        val talk = harness.send("npc/talk", """{"npc": "gorm", "line": "Shields?"}""")
        val queued = harness.send("npc/talk", """{"npc": "gorm", "line": "And helmets?"}""")
        harness.box.wait(2.seconds, "tool/call") { it["method"].str == "tool/call" }
        assertEquals(2, harness.result("npc/cancel", """{"npc": "gorm"}""")["cancelled"].int)
        val first = harness.response(talk)
        assertEquals("cancelled", first.errorName)
        assertEquals(BridgeError.CANCELLED, first.errorCode)
        assertEquals("cancelled", harness.response(queued).errorName)
        harness.box.wait(2.seconds, "tool/cancel") { it["method"].str == "tool/cancel" }
        // The history is unchanged.
        assertEquals(0, harness.result("npc/list")["npcs"][0]["turnCount"].int)
    }

    @Test
    fun fallbacksRetriesAndTextFormat() = runBlocking<Unit> {
        val harness = BridgeHarness()
        // Structured only: a blocked turn becomes a fallback line.
        harness.createNpc("strict", steps = """[{"error": "guardrail_violation"}]""", options = """{"replyFormat": "structured", "fallbackLines": ["Not now, lad."]}""")
        val blocked = harness.result("npc/talk", """{"npc": "strict", "line": "Tell me about the war."}""")
        assertEquals(JsonPrimitive(true), blocked["isFallback"])
        assertEquals("Not now, lad.", blocked["line"].str)

        // Automatic (the default): retried once as plain text.
        harness.createNpc("auto", steps = """[{"error": "guardrail_violation"}, {"text": "[worried] Let's not speak of it."}]""")
        val retried = harness.result("npc/talk", """{"npc": "auto", "line": "Tell me about the war."}""")
        assertEquals(JsonPrimitive(false), retried["isFallback"])
        assertEquals("Let's not speak of it.", retried["line"].str)
        assertEquals("worried", retried["emotion"].str)

        // Text format: an emotion tag, then the line.
        harness.createNpc("plain", steps = """[{"text": "[happy] Welcome to the forge!"}]""", options = """{"replyFormat": "text"}""")
        val plain = harness.result("npc/talk", """{"npc": "plain", "line": "Hello"}""")
        assertEquals("Welcome to the forge!", plain["line"].str)
        assertEquals("happy", plain["emotion"].str)

        // Without the fallback, the error is returned.
        harness.createNpc(
            "raw",
            steps = """[{"error": "guardrail_violation"}]""",
            options = """{"replyFormat": "structured", "fallbackOnGuardrail": false}""",
        )
        assertEquals(BridgeError.GUARDRAIL_VIOLATION, harness.call("npc/talk", """{"npc": "raw", "line": "Tell me about the war."}""").errorCode)
    }

    @Test
    fun barkUsesAOneOffRequest() = runBlocking<Unit> {
        val harness = BridgeHarness()
        harness.createNpc(steps = """[{"text": "Rain again. Good for quenching."}]""")
        assertEquals(
            j("""{"npc": "gorm", "line": "Rain again. Good for quenching."}"""),
            harness.result("npc/bark", """{"npc": "gorm", "situation": "It starts to rain."}"""),
        )
        assertEquals(0, harness.result("npc/list")["npcs"][0]["turnCount"].int)

        harness.createNpc("blocked", steps = """[{"error": "guardrail_violation"}]""")
        assertEquals("guardrail_violation", harness.call("npc/bark", """{"npc": "blocked", "situation": "A fight breaks out."}""").errorName)
    }

    @Test
    fun stateAndRestoreRoundTrip() = runBlocking<Unit> {
        val harness = BridgeHarness()
        harness.result("world/create", """{"world": "village", "state": {"player": {"name": "Aria"}}}""")
        harness.box.responder = MessageBox.ToolResponder { _, _ -> j("""{"output": "3 in stock"}""") }
        harness.createNpc(
            steps = "[${reply("Welcome, Aria.")}]",
            tools = "[$inventory]",
            options = """{"memoryTools": ["rememberFact"], "playerOptionCount": 2, "toolTimeoutSeconds": 30}""",
            world = "village",
        )
        harness.result("npc/talk", """{"npc": "gorm", "line": "Hello!"}""")
        harness.result("npc/update", """{"npc": "gorm", "memory": {"relationship": 25, "facts": ["Aria likes axes."]}}""")

        val saved = harness.result("npc/state", """{"npc": "gorm"}""")
        val state = saved["state"]!!
        assertEquals(1, state["version"].int)
        assertEquals("gorm", state["npc"].str)
        assertEquals("Gorm", state["persona"]["name"].str)
        assertEquals(25, state["memory"]["relationship"].int)
        assertEquals(listOf("Aria likes axes."), state["memory"]["facts"].strings())
        assertTrue(state["transcript"] is JsonObject)
        assertEquals(j("[$inventory]"), state["tools"])
        assertEquals("village", state["world"].str)
        assertEquals(listOf("rememberFact"), state["options"]["memoryTools"].strings())
        assertEquals(2, state["options"]["playerOptionCount"].int)
        assertEquals(30, state["options"]["toolTimeoutSeconds"].int)
        assertEquals(50, state["options"]["secretsUnlockAtRelationship"].int)
        assertEquals("explicit", state["options"]["toolChoice"].str)

        harness.result("npc/delete", """{"npc": "gorm"}""")
        // The save alone restores the NPC: id, tools, options and world.
        val restored = harness.result(
            "npc/restore",
            JsonObject(
                mapOf(
                    "state" to state,
                    "model" to j(
                        """{"type": "scripted", "steps": [{"toolCalls": [{"name": "check_inventory", "arguments": {"item": "axe"}}]}, ${reply("Axes, you said? Three.")}]}""",
                    ),
                ),
            ),
        )
        assertEquals("gorm", restored["npc"].str)
        assertEquals(j("[]"), restored["warnings"])
        val tools = restored["tools"].strings()
        assertTrue("check_inventory" in tools)
        assertTrue(WorldState.READ_TOOL_NAME in tools)
        assertTrue(NPC.REMEMBER_FACT_TOOL_NAME in tools)

        val listed = harness.result("npc/list")["npcs"][0]
        assertEquals(1, listed["turnCount"].int)
        assertEquals(25, listed["relationship"].int)
        assertEquals("village", listed["world"].str)
        val turn = harness.result("npc/talk", """{"npc": "gorm", "line": "Axes?"}""")
        assertEquals("Axes, you said? Three.", turn["line"].str)
        assertEquals("3 in stock", turn["toolCalls"][0]["output"].str)
        assertEquals(25, turn["relationship"].int)

        // Restoring into a taken id fails; a new id works.
        assertEquals("npc_exists", harness.call("npc/restore", JsonObject(mapOf("state" to state, "model" to JsonPrimitive("scripted")))).errorName)
        val copy = harness.result(
            "npc/restore",
            JsonObject(mapOf("state" to saved, "npc" to JsonPrimitive("gorm2"), "world" to JsonNull, "tools" to j("[]"), "model" to JsonPrimitive("scripted"))),
        )
        assertEquals(listOf(NPC.REMEMBER_FACT_TOOL_NAME), copy["tools"].strings())

        // A save whose world is gone restores without it, with a warning.
        harness.result("world/delete", """{"world": "village"}""")
        val orphan = harness.result("npc/restore", JsonObject(mapOf("state" to state, "npc" to JsonPrimitive("gorm3"), "model" to JsonPrimitive("scripted"))))
        assertTrue(orphan["warnings"][0].str!!.contains("village"))

        val invalid = harness.call("npc/restore", """{"state": {"persona": {"role": "no name"}}}""")
        assertEquals(BridgeError.INVALID_PARAMS, invalid.errorCode)
        assertEquals("Missing required parameter 'state.persona.name'.", invalid.errorMessage)
    }

    @Test
    fun updatesApplyInArrivalOrder() = runBlocking<Unit> {
        val harness = BridgeHarness()
        harness.createNpc(steps = """[${reply("First.")}, {"text": "[angry] Second."}]""")
        // Pipelined: the update waits for the first turn and applies before the second.
        val first = harness.send("npc/talk", """{"npc": "gorm", "line": "One"}""")
        val update = harness.send(
            "npc/update",
            """{"npc": "gorm", "persona": {"personality": "Furious today.", "goals": ["Close early"]}, "options": {"replyFormat": "text", "fallbackLines": ["Go away."]}, "memory": {"relationship": -30}}""",
        )
        val second = harness.send("npc/talk", """{"npc": "gorm", "line": "Two"}""")
        val state = harness.send("npc/state", """{"npc": "gorm"}""")
        assertEquals("First.", harness.response(first)["result"]["line"].str)
        assertEquals(j("""{"npc": "gorm", "warnings": []}"""), harness.response(update)["result"])
        val secondResult = harness.response(second)["result"]
        assertEquals("Second.", secondResult["line"].str)
        assertEquals("angry", secondResult["emotion"].str)
        assertEquals(-30, secondResult["relationship"].int)
        val saved = harness.response(state)["result"]["state"]
        assertEquals("Furious today.", saved["persona"]["personality"].str)
        assertEquals("Gorm", saved["persona"]["name"].str)
        assertEquals("the village blacksmith", saved["persona"]["role"].str)
        assertEquals("text", saved["options"]["replyFormat"].str)
        assertEquals(-30, saved["memory"]["relationship"].int)

        // Tools and a grounding tool can change together.
        val both = harness.result("npc/update", """{"npc": "gorm", "tools": [$inventory], "options": {"groundingTool": "check_inventory"}}""")
        assertEquals(j("[]"), both["warnings"])
        assertEquals(listOf("check_inventory"), harness.result("npc/list")["npcs"][0]["tools"].strings())

        // Invalid updates fail and change nothing.
        val unknownTool = harness.call("npc/update", """{"npc": "gorm", "tools": [], "options": {"groundingTool": "nope"}}""")
        assertEquals(BridgeError.INVALID_PARAMS, unknownTool.errorCode)
        assertEquals(BridgeError.INVALID_PARAMS, harness.call("npc/update", """{"npc": "gorm", "persona": {"name": ""}}""").errorCode)
        val after = harness.result("npc/state", """{"npc": "gorm", "settle": false}""")["state"]
        assertEquals("check_inventory", after["options"]["groundingTool"].str)
        assertEquals(j("[$inventory]"), after["tools"])
        assertEquals("Gorm", after["persona"]["name"].str)

        // A null in a patch resets the field to its default.
        harness.result("npc/update", """{"npc": "gorm", "options": {"groundingTool": null, "replyFormat": null}}""")
        val reset = harness.result("npc/state", """{"npc": "gorm", "settle": false}""")["state"]
        assertFalse("groundingTool" in (reset["options"] as JsonObject))
        assertEquals("automatic", reset["options"]["replyFormat"].str)

        // Reset clears the conversation, optionally the memory too.
        harness.result("npc/reset", """{"npc": "gorm", "clearMemory": true}""")
        val cleared = harness.result("npc/list")["npcs"][0]
        assertEquals(0, cleared["turnCount"].int)
        assertEquals(0, cleared["relationship"].int)
    }

    @Test
    fun worldToolsAndNotifications() = runBlocking<Unit> {
        val harness = BridgeHarness()
        harness.result("world/create", """{"world": "village", "state": {"player": {"name": "Aria", "gold": 60}}}""")
        val subscription = harness.result("world/subscribe", """{"world": "village", "path": "quests"}""")
        harness.createNpc(
            steps = """[
                {"toolCalls": [{"name": "read_world_state", "arguments": {"path": "player.gold"}}]},
                {"toolCalls": [{"name": "update_world_state", "arguments": {"path": "quests.ring", "value": "started"}}]},
                ${reply("Sixty gold, Aria. Find my ring.")}
            ]""",
            options = """{"groundingTool": "read_world_state", "worldWritable": ["quests"], "maxToolRounds": 3}""",
            world = "village",
        )
        val request = harness.send("npc/talk", """{"npc": "gorm", "line": "What can I afford?", "stream": true}""")
        val result = harness.response(request)["result"]
        assertEquals(60, result["toolCalls"][0]["output"].int)
        assertEquals("update_world_state", result["toolCalls"][1]["call"]["name"].str)
        assertEquals("started", harness.result("world/get", """{"world": "village", "path": "quests.ring"}""")["value"].str)

        val change = harness.box.messages.first { it["method"].str == "world/changed" }
        assertEquals("village", change["params"]["world"].str)
        assertEquals(subscription["subscription"], change["params"]["subscription"])
        assertEquals("quests.ring", change["params"]["path"].str)
        assertEquals("started", change["params"]["newValue"].str)
        assertTrue("oldValue" !in (change["params"] as JsonObject))
        // The change happened during the turn, so it precedes the response.
        assertTrue(harness.box.index { it["method"].str == "world/changed" }!! < harness.responseIndex(request)!!)
        val local = harness.events("npc/event", request).filter { it["type"].str == "toolCallStarted" }
        assertEquals(2, local.size)
        assertTrue(local.all { it["execution"].str == "local" })
    }

    @Test
    fun invalidParametersAreRejected() = runBlocking<Unit> {
        val harness = BridgeHarness()
        val noPersona = harness.call("npc/create", """{"model": "scripted"}""")
        assertEquals(BridgeError.INVALID_PARAMS, noPersona.errorCode)
        assertTrue(noPersona.errorMessage!!.contains("'persona'"))

        assertEquals("Missing required parameter 'persona.name'.", harness.call("npc/create", """{"persona": {"role": "a ghost"}, "model": "scripted"}""").errorMessage)
        assertEquals(
            "Parameter 'persona.goals' must be an array.",
            harness.call("npc/create", """{"persona": {"name": "Ann", "goals": "gold"}, "model": "scripted"}""").errorMessage,
        )
        assertTrue(
            harness.call("npc/create", """{"persona": {"name": "Ann", "defaultEmotion": "gleeful"}, "model": "scripted"}""").errorMessage!!
                .contains("persona.defaultEmotion"),
        )
        assertTrue(
            harness.call("npc/create", """{"persona": {"name": "Ann"}, "options": {"replyFormat": "json"}, "model": "scripted"}""").errorMessage!!
                .contains("options.replyFormat"),
        )
        assertTrue(
            harness.call("npc/create", """{"persona": {"name": "Ann"}, "options": {"memoryTools": ["dance"]}, "model": "scripted"}""").errorMessage!!
                .contains("dance"),
        )
        assertTrue(
            harness.call("npc/create", """{"persona": {"name": "Ann"}, "options": {"toolChoice": "sometimes"}, "model": "scripted"}""").errorMessage!!
                .contains("options.toolChoice"),
        )
        assertTrue(
            harness.call("npc/create", """{"persona": {"name": "Ann"}, "options": {"playerOptionCount": 9}, "model": "scripted"}""").errorMessage!!
                .contains("playerOptionCount"),
        )
        val unknownGrounding = harness.call("npc/create", """{"persona": {"name": "Ann"}, "options": {"groundingTool": "check_inventory"}, "model": "scripted"}""")
        assertEquals(BridgeError.INVALID_PARAMS, unknownGrounding.errorCode)
        assertTrue(unknownGrounding.errorMessage!!.contains("groundingTool"))
        assertEquals("world_not_found", harness.call("npc/create", """{"persona": {"name": "Ann"}, "world": "atlantis", "model": "scripted"}""").errorName)
        assertEquals(BridgeError.INVALID_PARAMS, harness.call("npc/create", """{"npc": "", "persona": {"name": "Ann"}, "model": "scripted"}""").errorCode)

        // Nothing above created an NPC.
        assertEquals(j("""{"npcs": []}"""), harness.result("npc/list"))

        val warned = harness.result(
            "npc/create",
            """{"persona": {"name": "Ann", "personalty": "typo"}, "options": {"temprature": 0.5, "memoryTools": "all"}, "voice": "deep", "model": "scripted"}""",
        )
        val warnings = warned["warnings"].strings()
        assertTrue(warnings.any { "'persona.personalty'" in it }, "$warnings")
        assertTrue(warnings.any { "'options.temprature'" in it }, "$warnings")
        assertTrue(warnings.any { "'voice'" in it }, "$warnings")
        assertEquals(listOf(NPC.REMEMBER_FACT_TOOL_NAME, NPC.CHANGE_RELATIONSHIP_TOOL_NAME), warned["tools"].strings())

        val npc = warned["npc"].str
        assertEquals("Missing required parameter 'line'.", harness.call("npc/talk", """{"npc": "$npc"}""").errorMessage)
        assertEquals("invalid_request", harness.call("npc/talk", """{"npc": "$npc", "line": "hi", "toolChoice": {"tool": "nope"}}""").errorName)
    }

    @Test
    fun manyToolsAreWarnedAbout() = runBlocking<Unit> {
        val harness = BridgeHarness()
        val tools = (1..4).joinToString(", ", "[", "]") { """{"name": "tool_$it", "description": "Does thing $it."}""" }
        harness.result("world/create", """{"world": "w"}""")
        val created = harness.result(
            "npc/create",
            """{"persona": {"name": "Ann"}, "tools": $tools, "world": "w", "options": {"worldWritable": [""]}, "model": "scripted"}""",
        )
        assertTrue(created["warnings"].strings().any { "6 tools" in it })
    }

    @Test
    fun limitsAndShutdown() = runBlocking<Unit> {
        val game = GameExtension(maxNpcs = 1)
        val harness = BridgeHarness(BridgeConfiguration(modelAvailability = { BridgeHarness.TEST_AVAILABILITY }, extensions = listOf(game)))
        harness.createNpc()
        val second = harness.call("npc/create", """{"persona": {"name": "Two"}, "model": "scripted"}""")
        assertEquals("limit_reached", second.errorName)
        assertEquals(BridgeError.LIMIT_REACHED, second.errorCode)
        assertEquals(1, second["error"]["data"]["limit"].int)
        assertEquals("Gorm", game.npc("gorm")?.persona?.name)

        val initialize = harness.result("initialize")
        val notifications = initialize["capabilities"]["notifications"].strings()
        assertTrue("npc/event" in notifications && "world/changed" in notifications)
        val methods = initialize["capabilities"]["methods"].strings()
        for (method in listOf(
            "npc/create", "npc/talk", "npc/bark", "npc/state", "npc/restore", "npc/update", "npc/reset", "npc/cancel", "npc/delete",
            "npc/list", "decision/decide", "decision/decideMany", "world/create", "world/get", "world/set", "world/merge",
            "world/remove", "world/snapshot", "world/delete", "world/list", "world/subscribe", "world/unsubscribe", "content/generate",
        )) {
            assertTrue(method in methods, "missing $method")
        }

        harness.result("world/create", """{"world": "w"}""")
        harness.result("shutdown")
        assertTrue(game.npcIds.isEmpty())
        assertTrue(game.worldIds.isEmpty())
    }

    @Test
    fun standardExtensionsServeTheGameMethods() {
        assertTrue(BridgeConfiguration.standardExtensions().any { it is GameExtension })
        assertTrue("npc/talk" in BridgeHarness().engine.methods)
        assertTrue("npc/talk" !in BridgeHarness(BridgeConfiguration(extensions = emptyList())).engine.methods)
    }
}
