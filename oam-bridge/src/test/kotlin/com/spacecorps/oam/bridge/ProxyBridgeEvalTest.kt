package com.spacecorps.oam.bridge

import com.spacecorps.oam.game.Emotion
import com.spacecorps.oam.openai.OpenAICompatibleModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The whole protocol against a live small model: open-apple-models' `oam
 * serve` (Apple's ~3B on-device model behind an OpenAI-compatible endpoint)
 * stands in for Gemini Nano as the bridge's system model, and a fake game
 * engine answers `tool/call`s.
 *
 * Opt-in:
 * ```
 * oam serve --port 19996 &
 * OAM_PROXY_EVAL=1 OAM_PROXY_URL=http://127.0.0.1:19996/v1 [OAM_PROXY_EVAL_RUNS=3] \
 *   ./gradlew :oam-bridge:test --tests '*ProxyBridgeEvalTest*'
 * ```
 *
 * Protocol invariants are asserted (a response for every request, tool calls
 * before responses, deltas adding up, valid enums); what the model chose is
 * reported, not asserted.
 */
@EnabledIfEnvironmentVariable(named = "OAM_PROXY_EVAL", matches = "1")
@Timeout(900)
class ProxyBridgeEvalTest {
    private val baseUrl = System.getenv("OAM_PROXY_URL") ?: "http://127.0.0.1:19996/v1"
    private val modelName = System.getenv("OAM_PROXY_MODEL") ?: "system"
    private val runs = System.getenv("OAM_PROXY_EVAL_RUNS")?.toIntOrNull()?.coerceIn(1, 10) ?: 1
    private val report = ArrayList<String>()
    private val timeout = 90.seconds

    private fun harness() = BridgeHarness(BridgeConfiguration(systemModel = OpenAICompatibleModel(baseUrl, modelName)))

    private suspend fun timed(harness: BridgeHarness, method: String, params: String): Pair<JsonElement, Long> {
        val started = TimeSource.Monotonic.markNow()
        val id = harness.send(method, params)
        val response = harness.response(id, timeout)
        return response to started.elapsedNow().inWholeMilliseconds
    }

    @Test
    fun liveProtocolScenarios() = runBlocking<Unit> {
        repeat(runs) { run ->
            report += "Run ${run + 1}"
            guardSession()
            npcTurns()
            decisionsAndContent()
        }
        println("Bridge eval against $baseUrl ($modelName):")
        report.forEach(::println)
    }

    /** open-apple-models' PROTOCOL.md example: a guard with a client tool, plus small talk. */
    private suspend fun guardSession() {
        val harness = harness()
        harness.box.responder = MessageBox.ToolResponder { _, _ -> j("""{"output": {"opened": false, "reason": "the portcullis chain is jammed"}}""") }
        harness.result(
            "session/create",
            """{"session": "guard", "instructions": "You are a castle guard in a game. Use tools to act. Reply in one sentence.",
                "tools": [{"name": "open_gate", "description": "Ask the game engine to open a named gate. Returns whether it opened.",
                           "parameters": {"type": "object", "properties": {"gate": {"type": "string"}}, "required": ["gate"]}}],
                "options": {"toolChoice": "explicit", "maxToolRounds": 1}}""",
        )
        for ((line, expectsTool) in listOf("Please open the north gate." to true, "Nice evening for a watch, isn't it?" to false)) {
            val request = harness.send("session/respond", """{"session": "guard", "prompt": "$line", "stream": true}""")
            val started = TimeSource.Monotonic.markNow()
            val response = harness.response(request, timeout)
            val millis = started.elapsedNow().inWholeMilliseconds
            val result = assertNotNull(response["result"], "session/respond failed: $response")
            val calls = harness.box.messages.filter { it["method"].str == "tool/call" && it["params"]["requestId"].str == request }
            calls.forEach { assertTrue(harness.box.messages.indexOf(it) < harness.responseIndex(request)!!) }
            val deltas = harness.events("session/event", request).filter { it["type"].str == "text" }
            if (deltas.isNotEmpty()) assertEquals(result["text"].str, deltas.last()["text"].str)
            val called = calls.map { "${it["params"]["call"]["name"].str}${it["params"]["call"]["arguments"]}" }
            val steps = (result["steps"] as JsonArray).joinToString("+") { it["kind"].str!! + if (it["isRepair"].toString() == "true") "(repair)" else "" }
            val verdict = if (called.isNotEmpty() == expectsTool) "ok" else "MISS"
            report += "  guard  [$verdict] \"$line\" -> tools=$called steps=$steps ${millis}ms: ${result["text"].str}"
        }
        harness.engine.close()
    }

    /** Gorm with a grounding client tool, a free turn, and a bark. */
    private suspend fun npcTurns() {
        val harness = harness()
        harness.box.responder = MessageBox.ToolResponder { call, _ ->
            val item = call["arguments"]["item"].str ?: "?"
            if ("sword" in item.lowercase()) {
                j("""{"output": {"item": "iron sword", "stock": 3, "price_gold": 45}}""")
            } else {
                j("""{"output": {"item": ${kotlinx.serialization.json.JsonPrimitive(item)}, "stock": 0}}""")
            }
        }
        harness.result(
            "npc/create",
            """{"npc": "gorm", "persona": {"name": "Gorm", "role": "the village blacksmith", "personality": "Gruff but fair.",
                "speakingStyle": "Short, blunt sentences. Calls people 'lad'."},
                "tools": [{"name": "check_inventory", "description": "Look up stock and price of an item in Gorm's shop.",
                           "parameters": {"type": "object", "properties": {"item": {"type": "string"}}, "required": ["item"]}}],
                "options": {"groundingTool": "check_inventory", "maxToolRounds": 1}}""",
        )
        for (line in listOf("Got any iron swords?", "Do you sell shields?")) {
            val request = harness.send("npc/talk", """{"npc": "gorm", "line": "$line", "stream": true}""")
            val started = TimeSource.Monotonic.markNow()
            val response = harness.response(request, timeout)
            val millis = started.elapsedNow().inWholeMilliseconds
            val result = assertNotNull(response["result"], "npc/talk failed: $response")
            assertNotNull(Emotion.fromWireName(result["emotion"].str!!), "emotion ${result["emotion"]}")
            var shown = ""
            for (event in harness.events("npc/event", request)) {
                when (event["type"].str) {
                    "lineDelta" -> shown += event["delta"].str
                    "lineReset" -> shown = event["line"].str!!
                }
            }
            assertEquals(result["line"].str, shown)
            val calls = (result["toolCalls"] as JsonArray).map { "${it["call"]["name"].str}${it["call"]["arguments"]}" }
            report += "  npc    \"$line\" -> tools=$calls [${result["emotion"].str}] fallback=${result["isFallback"]} ${millis}ms: ${result["line"].str} " +
                "options=${result["playerOptions"]}"
        }
        val (bark, millis) = timed(harness, "npc/bark", """{"npc": "gorm", "situation": "Rain starts to fall on the forge."}""")
        report += "  bark   ${millis}ms: ${bark["result"]["line"].str ?: bark["error"]}"
        harness.engine.close()
    }

    private suspend fun decisionsAndContent() {
        val harness = harness()
        val (decision, decisionMillis) = timed(
            harness,
            "decision/decide",
            """{"situation": "You are a goblin with 3 HP left. The player is at full health and blocks the cave exit, but the forest path behind you is open.",
                "options": [{"id": "attack", "description": "Keep fighting"}, {"id": "flee", "description": "Run into the woods"}, {"id": "beg", "description": "Beg for mercy"}],
                "actor": {"name": "Snik", "role": "a goblin scout", "personality": "Timid and greedy."}, "fallbackOptionID": "flee"}""",
        )
        val choice = decision["result"]["optionID"].str
        assertTrue(choice in setOf("attack", "flee", "beg"), "decision: $decision")
        report += "  decide ${decisionMillis}ms: $choice (${decision["result"]["confidence"]}) fallback=${decision["result"]["isFallback"]}: ${decision["result"]["reasoning"].str}"

        val (content, contentMillis) = timed(
            harness,
            "content/generate",
            """{"prompt": "A cursed sword found in a drowned temple.", "schema": {"type": "object", "properties": {
                "name": {"type": "string"}, "rarity": {"type": "string", "enum": ["common", "rare", "legendary"]},
                "damage": {"type": "integer", "minimum": 1, "maximum": 50}}, "required": ["name", "rarity", "damage"]}}""",
        )
        val item = content["result"]["content"]
        if (item != null) {
            assertTrue(item["rarity"].str in setOf("common", "rare", "legendary"))
            assertTrue(item["damage"].int in 1..50)
        }
        report += "  content ${contentMillis}ms: ${item ?: content["error"]}"
        harness.engine.close()
    }
}
