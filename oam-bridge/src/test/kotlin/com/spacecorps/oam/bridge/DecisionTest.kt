package com.spacecorps.oam.bridge

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** `decision/…` and `content/generate` (open-apple-models' DecisionMethodTests). */
@Timeout(60)
class DecisionTest {
    private val goblinOptions = """[{"id": "attack", "description": "Keep fighting"}, {"id": "flee", "description": "Run into the woods"}, "beg"]"""

    private fun scripted(steps: String) = """{"type": "scripted", "steps": $steps}"""

    @Test
    fun decide() = runBlocking<Unit> {
        val harness = BridgeHarness()
        val result = harness.result(
            "decision/decide",
            """{"situation": "The goblin has 3 HP left.", "options": $goblinOptions, "actor": {"name": "Snik", "personality": "Timid and greedy."},
                "context": {"hp": 3, "playerHp": 40},
                "model": ${scripted("""[{"json": {"reasoning": "Snik is timid.", "choice": "flee", "confidence": 78}}]""")}}""",
        )
        assertEquals("flee", result["optionID"].str)
        assertEquals("Snik is timid.", result["reasoning"].str)
        assertEquals(78, result["confidence"].int)
        assertEquals(j("[]"), result["toolCalls"])
        assertEquals(JsonPrimitive(false), result["isFallback"])
        assertEquals(listOf("optionID", "reasoning", "confidence", "toolCalls", "usage", "isFallback"), result.keys)

        // A single option needs no model.
        val only = harness.result("decision/decide", """{"situation": "Cornered.", "options": ["fight"], "model": "scripted"}""")
        assertEquals("fight", only["optionID"].str)
        assertEquals(100, only["confidence"].int)
    }

    @Test
    fun invalidDecisionsFailBeforeTheModel() = runBlocking<Unit> {
        val harness = BridgeHarness()
        assertTrue(
            harness.call("decision/decide", """{"situation": "x", "options": ["a", "a"], "model": "scripted"}""").errorMessage!!
                .contains("Duplicate option id 'a'"),
        )
        assertEquals(BridgeError.INVALID_PARAMS, harness.call("decision/decide", """{"situation": "x", "options": [], "model": "scripted"}""").errorCode)
        assertTrue(
            harness.call("decision/decide", """{"situation": "x", "options": ["a", "b"], "fallbackOptionID": "c", "model": "scripted"}""").errorMessage!!
                .contains("fallbackOptionID"),
        )
        assertTrue(
            harness.call("decision/decide", """{"situation": "x", "options": ["a", "b"], "toolChoice": {"tool": "scout"}, "model": "scripted"}""").errorMessage!!
                .contains("scout"),
        )
        assertEquals(
            "Missing required parameter 'situation'.",
            harness.call("decision/decide", """{"options": ["a", "b"], "model": "scripted"}""").errorMessage,
        )
        assertEquals("npc_not_found", harness.call("decision/decide", """{"situation": "x", "options": ["a", "b"], "actor": "nobody", "model": "scripted"}""").errorName)
    }

    @Test
    fun unusableAnswersUseTheFallbackOption() = runBlocking<Unit> {
        val harness = BridgeHarness()
        val result = harness.result(
            "decision/decide",
            """{"situation": "The ogre swings its club.", "options": $goblinOptions, "fallbackOptionID": "flee", "model": ${scripted("""[{"error": "guardrail_violation"}]""")}}""",
        )
        assertEquals("flee", result["optionID"].str)
        assertEquals(JsonPrimitive(true), result["isFallback"])

        val failed = harness.call(
            "decision/decide",
            """{"situation": "The ogre swings its club.", "options": $goblinOptions, "model": ${scripted("""[{"error": "guardrail_violation"}]""")}}""",
        )
        assertEquals(BridgeError.GUARDRAIL_VIOLATION, failed.errorCode)

        // Android: a choice that stays invalid after the repair is unusable too.
        val invalid = harness.result(
            "decision/decide",
            """{"situation": "x", "options": ["camp", "march"], "fallbackOptionID": "camp",
                "model": ${scripted("""[{"json": {"reasoning": "?", "choice": "dance", "confidence": 5}}, {"json": {"reasoning": "?", "choice": "sing", "confidence": 5}}]""")}}""",
        )
        assertEquals("camp", invalid["optionID"].str)
        assertEquals(JsonPrimitive(true), invalid["isFallback"])
    }

    @Test
    fun clientToolsAndNpcActors() = runBlocking<Unit> {
        val harness = BridgeHarness()
        val seen = Collections.synchronizedList(ArrayList<JsonElement?>())
        harness.box.responder = MessageBox.ToolResponder { call, params ->
            seen += params
            JsonObject(mapOf("output" to JsonObject(mapOf("distance" to JsonPrimitive(12), "target" to call["arguments"]["target"]!!))))
        }
        harness.createNpc("snik", persona = """{"name": "Snik", "role": "a goblin scout"}""")
        val request = harness.send(
            "decision/decide",
            """{"situation": "An adventurer approaches.", "options": $goblinOptions, "actor": "snik",
                "tools": [{"name": "measure", "description": "Distance to a target in meters.", "parameters": {"type": "object", "properties": {"target": {"type": "string"}}}}],
                "toolChoice": "required",
                "model": ${scripted("""[{"toolCalls": [{"name": "measure", "arguments": {"target": "adventurer"}}]}, {"json": {"reasoning": "Far enough to run.", "choice": "flee", "confidence": 60}}]""")}}""",
        )
        val result = harness.response(request)["result"]
        assertEquals("flee", result["optionID"].str)
        assertEquals("measure", result["toolCalls"][0]["call"]["name"].str)
        assertEquals(12, result["toolCalls"][0]["output"]["distance"].int)
        val params = seen.first()
        assertEquals(request, params["requestId"].str)
        assertEquals(j("""{"target": "adventurer"}"""), params["call"]["arguments"])
        assertTrue(harness.box.index { it["method"].str == "tool/call" }!! < harness.responseIndex(request)!!)
    }

    @Test
    fun forwardedToolTimeoutSendsToolCancel() = runBlocking<Unit> {
        val harness = BridgeHarness()
        val result = harness.result(
            "decision/decide",
            """{"situation": "Night falls.", "options": ["camp", "march"], "tools": [{"name": "weather", "description": "Current weather."}], "toolTimeoutSeconds": 0.05,
                "model": ${scripted("""[{"toolCalls": [{"name": "weather"}]}, {"json": {"reasoning": "Unknown weather; rest.", "choice": "camp", "confidence": 40}}]""")}}""",
        )
        assertEquals("camp", result["optionID"].str)
        assertEquals(JsonPrimitive(true), result["toolCalls"][0]["isError"])
        val cancel = harness.box.wait(2.seconds, "tool/cancel") { it["method"].str == "tool/cancel" }
        val call = harness.box.messages.first { it["method"].str == "tool/call" }
        assertEquals(call["id"], cancel["params"]["id"])
        assertEquals(call["params"]["call"]["id"], cancel["params"]["callId"])
        // The forwarded call is cancelled synchronously with the timeout, so its tool/cancel precedes the response.
        assertTrue(harness.box.index { it["method"].str == "tool/cancel" }!! < harness.box.index { it["result"]["optionID"] != null }!!)
    }

    @Test
    fun decideManyKeepsOrderAndIsolatesFailures() = runBlocking<Unit> {
        val harness = BridgeHarness()
        val result = harness.result(
            "decision/decideMany",
            """{"maxConcurrency": 1, "requests": [
                    {"situation": "Guard A hears a noise.", "options": ["investigate", "ignore"]},
                    {"situation": "Guard B sees blood.", "options": ["investigate", "ignore"]},
                    {"situation": "Guard C is alone.", "options": ["wait"]}
                ],
                "model": ${scripted("""[{"json": {"reasoning": "Duty.", "choice": "investigate", "confidence": 90}}, {"error": "guardrail_violation"}]""")}}""",
        )
        val results = result["results"] as JsonArray
        assertEquals(3, results.size)
        assertEquals("investigate", results[0]["optionID"].str)
        assertEquals("guardrail_violation", results[1]["error"]["data"]["code"].str)
        assertEquals(BridgeError.GUARDRAIL_VIOLATION, results[1]["error"]["code"].int)
        assertEquals("wait", results[2]["optionID"].str)
        assertNull(result["warnings"])

        assertEquals(
            "Missing required parameter 'requests[1].options'.",
            harness.call("decision/decideMany", """{"requests": [{"situation": "x", "options": ["a"]}, {"situation": "y"}]}""").errorMessage,
        )
        assertEquals(BridgeError.INVALID_PARAMS, harness.call("decision/decideMany", """{"requests": []}""").errorCode)
    }

    @Test
    fun decideManyForwardsToolsWithTheirIndex() = runBlocking<Unit> {
        val harness = BridgeHarness()
        val seen = Collections.synchronizedList(ArrayList<JsonElement?>())
        harness.box.responder = MessageBox.ToolResponder { _, params ->
            seen += params
            j("""{"output": "clear"}""")
        }
        val tools = """[{"name": "look", "description": "Look around."}]"""
        harness.result(
            "decision/decideMany",
            """{"maxConcurrency": 1, "requests": [
                    {"situation": "A", "options": ["go", "stay"], "tools": $tools, "toolChoice": {"tool": "look"}},
                    {"situation": "B", "options": ["go", "stay"], "tools": $tools, "toolChoice": {"tool": "look"}}
                ],
                "model": ${scripted("""[{"json": {"reasoning": "ok", "choice": "go", "confidence": 50}}, {"json": {"reasoning": "ok", "choice": "stay", "confidence": 50}}]""")}}""",
        )
        assertEquals(listOf(0, 1), seen.map { it["index"].int })
    }

    @Test
    fun generateContent() = runBlocking<Unit> {
        val harness = BridgeHarness()
        val schema =
            """{"type": "object", "properties": {"name": {"type": "string"}, "rarity": {"type": "string", "enum": ["common", "rare", "legendary"]}, "damage": {"type": "integer", "minimum": 1, "maximum": 50}}, "required": ["name", "rarity", "damage"]}"""
        val result = harness.result(
            "content/generate",
            """{"prompt": "A cursed sword from a drowned temple.", "schema": $schema, "context": {"playerLevel": 7},
                "model": ${scripted("""[{"json": {"damage": 45, "name": "Drowned Fang", "rarity": "legendary"}}]""")}}""",
        )
        assertEquals(j("""{"name": "Drowned Fang", "rarity": "legendary", "damage": 45}"""), result["content"])
        // Keys come back in schema order.
        assertEquals(listOf("name", "rarity", "damage"), result["content"].keys)
        assertNull(result["warnings"])

        assertEquals(BridgeError.INVALID_SCHEMA, harness.call("content/generate", """{"prompt": "x", "schema": {"type": "nope"}, "model": "scripted"}""").errorCode)
        assertEquals(BridgeError.INVALID_PARAMS, harness.call("content/generate", """{"schema": $schema, "model": "scripted"}""").errorCode)

        harness.box.responder = MessageBox.ToolResponder { _, _ -> j("""{"output": {"biome": "swamp"}}""") }
        val withTool = harness.result(
            "content/generate",
            """{"prompt": "A monster for the current biome.", "schema": {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]},
                "tools": [{"name": "biome", "description": "The current biome."}],
                "model": ${scripted("""[{"toolCalls": [{"name": "biome"}]}, {"json": {"name": "Bog Lurker"}}]""")}}""",
        )
        assertEquals(j("""{"name": "Bog Lurker"}"""), withTool["content"])
    }
}
