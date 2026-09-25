package com.spacecorps.oam.bridge

import com.spacecorps.oam.toJsonString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Differential conformance against open-apple-models' own bridge: the same
 * JSON-RPC scenarios (scripted models only, so both sides are deterministic)
 * run through Apple's `oam stdio` and through [BridgeEngine], and the
 * exchanges are compared after normalizing the documented differences.
 *
 * Opt-in: set `OAM_APPLE_BRIDGE` to the `oam` executable (macOS 26+).
 *
 * ```
 * OAM_APPLE_BRIDGE=/path/to/oam ./gradlew :oam-bridge:test --tests '*AppleConformanceTest*'
 * ```
 *
 * Normalized away, per `docs/PROTOCOL.md` (Android differences): `server`,
 * `model` (availability), `usage` values, `steps` (Android adds decide steps),
 * `durationSeconds`, `createdAt`, call ids (`call_…`), transcripts, error and
 * warning *texts* (codes and counts are compared), `generationSchema`, and
 * `modelStep` events plus how text is split into deltas.
 */
@Timeout(120)
class AppleConformanceTest {
    private val binary = System.getenv("OAM_APPLE_BRIDGE")?.takeIf { it.isNotBlank() }

    @Test
    fun scriptedScenariosMatchOpenAppleModels() {
        assumeTrue(binary != null && File(binary).canExecute(), "Set OAM_APPLE_BRIDGE to open-apple-models' oam executable to run the conformance check.")
        val differences = ArrayList<String>()
        val wording = ArrayList<String>()
        var compared = 0
        for ((name, steps) in SCENARIOS) {
            val apple = ApplePeer(binary!!).use { run(it, steps) }
            val android = AndroidPeer().use { run(it, steps) }
            if (apple.size != android.size) differences += "$name: ${apple.size} vs ${android.size} exchanges"
            for (index in 0 until minOf(apple.size, android.size)) {
                compared++
                val (appleRecord, appleMessage) = apple[index]
                val (androidRecord, androidMessage) = android[index]
                if (appleRecord != androidRecord) {
                    differences += "$name step $index (${steps[index].first}):\n  apple:   ${appleRecord.toJsonString()}\n  android: ${androidRecord.toJsonString()}"
                }
                if (appleMessage != androidMessage) wording += "$name step $index: \"$appleMessage\" / \"$androidMessage\""
            }
        }
        println("Conformance: compared $compared exchanges in ${SCENARIOS.size} scenarios, ${differences.size} differences.")
        differences.forEach(::println)
        println("Error messages worded differently (informational): ${wording.size}")
        wording.forEach(::println)
        assertTrue(differences.isEmpty(), "Differences from open-apple-models:\n" + differences.joinToString("\n"))
    }

    // MARK: Peers

    private interface Peer : AutoCloseable {
        fun send(line: String)

        fun poll(timeoutMillis: Long): String?
    }

    private class AndroidPeer : Peer {
        private val queue = LinkedBlockingQueue<String>()
        private val engine = BridgeEngine(BridgeConfiguration(modelAvailability = { BridgeHarness.TEST_AVAILABILITY })) { queue.put(it) }

        override fun send(line: String) = engine.receive(line)

        override fun poll(timeoutMillis: Long): String? = queue.poll(timeoutMillis, TimeUnit.MILLISECONDS)

        override fun close() = engine.close()
    }

    private class ApplePeer(binary: String) : Peer {
        private val process = ProcessBuilder(binary, "stdio").redirectError(ProcessBuilder.Redirect.DISCARD).start()
        private val queue = LinkedBlockingQueue<String>()
        private val writer = process.outputStream.bufferedWriter()

        init {
            Thread {
                process.inputStream.bufferedReader().lineSequence().forEach { queue.put(it) }
            }.apply { isDaemon = true }.start()
        }

        override fun send(line: String) {
            writer.write(line)
            writer.newLine()
            writer.flush()
        }

        override fun poll(timeoutMillis: Long): String? = queue.poll(timeoutMillis, TimeUnit.MILLISECONDS)

        override fun close() {
            runCatching { writer.close() }
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    // MARK: Running a scenario

    /**
     * Sends each request, answers `tool/call`s with [toolAnswer], and returns
     * one normalized record per request (`{response, events, toolCalls, cancels, changes}`) with
     * the error message, if the response is an error.
     */
    private fun run(peer: Peer, steps: List<Pair<String, String>>): List<Pair<JsonElement, String?>> = steps.mapIndexed { index, (method, params) ->
        val id = "q$index"
        if (method == RAW) {
            peer.send(params)
        } else {
            // One line per message: compact the (possibly multi-line) params.
            peer.send("""{"jsonrpc":"2.0","id":"$id","method":"$method","params":${Json.parseToJsonElement(params).toJsonString()}}""")
        }
        val events = ArrayList<JsonElement>()
        val toolCalls = ArrayList<JsonElement>()
        val cancels = ArrayList<JsonElement>()
        val changes = ArrayList<JsonElement>()
        var response: JsonElement? = null
        while (response == null) {
            val line = peer.poll(10_000) ?: fail("No response to $method $params")
            val message = Json.parseToJsonElement(line) as JsonObject
            when (message["method"].str) {
                null -> if (method == RAW || message["id"].str == id) response = message else fail("Unexpected response $line")
                "tool/call" -> {
                    toolCalls += normalize(message["params"]!!)
                    peer.send("""{"jsonrpc":"2.0","id":${message["id"]},${toolAnswer(message["params"]["call"]!!)}}""")
                }
                "tool/cancel" -> cancels += normalize(message["params"]!!)
                "world/changed" -> changes += normalize(message["params"]!!)
                else -> events += normalize(message["params"]!!)
            }
        }
        val record = JsonObject(
            mapOf(
                "response" to normalize(response),
                "events" to JsonArray(collapse(events)),
                "toolCalls" to JsonArray(toolCalls),
                "cancels" to JsonArray(cancels),
                "changes" to JsonArray(changes),
            ),
        )
        record to response["error"]["message"].str
    }

    /** Answers a tool call like a game engine would, from the call alone. */
    private fun toolAnswer(call: JsonElement): String {
        val arguments = call["arguments"]
        return when (call["name"].str) {
            "open_gate" -> if (arguments["gate"].str == "west") {
                """"error":{"code":-32000,"message":"Gate subsystem offline"}"""
            } else {
                """"result":{"output":{"opened":true}}"""
            }
            "check_inventory" -> """"result":{"output":{"item":${arguments["item"]},"stock":3,"price_gold":45}}"""
            else -> """"result":{"output":"ok"}"""
        }
    }

    /**
     * Drops `modelStep` events and merges consecutive text deltas into one
     * event carrying the final text (how text is chunked is not part of the protocol).
     */
    private fun collapse(events: List<JsonElement>): List<JsonElement> {
        val result = ArrayList<JsonElement>()
        for (params in events) {
            val event = params["event"] as? JsonObject ?: continue
            val type = event["type"].str
            if (type == "modelStep" || type == "partial") continue
            val context = JsonObject(params.jsonObjectOrEmpty().filterKeys { it != "event" })
            val merged = when (type) {
                "text" -> JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to event["text"]!!))
                "lineDelta", "lineReset" -> JsonObject(mapOf("type" to JsonPrimitive("line")))
                else -> event
            }
            val entry = JsonObject(context + ("event" to merged))
            if (result.lastOrNull()?.get("event")?.get("type") == merged["type"] && type in setOf("text", "lineDelta", "lineReset")) {
                result[result.lastIndex] = entry
            } else {
                result += entry
            }
        }
        return result
    }

    private fun JsonElement.jsonObjectOrEmpty(): Map<String, JsonElement> = (this as? JsonObject).orEmpty()

    private fun normalize(value: JsonElement, key: String? = null): JsonElement = when {
        value is JsonObject -> JsonObject(
            value.filterKeys { it !in DROPPED }.mapValues { (member, element) ->
                when (member) {
                    in MASKED -> JsonPrimitive("<$member>")
                    "warnings" -> JsonPrimitive((element as? JsonArray)?.size ?: -1)
                    "message" -> if (key == "error") JsonPrimitive("<message>") else normalize(element, member)
                    else -> normalize(element, member)
                }
            },
        )
        value is JsonArray -> JsonArray(value.map { normalize(it, key) })
        value is JsonPrimitive && value.isString && value.content.startsWith("call_") -> JsonPrimitive("<call>")
        value is JsonPrimitive && !value.isString && value != JsonNull -> JsonPrimitive(value.content.toDoubleOrNull()?.let { if (it == Math.rint(it)) it.toLong().toString() else it.toString() } ?: value.content)
        else -> value
    }

    private companion object {
        const val RAW = "<raw>"

        /** Members whose values differ by design between the platforms. */
        val DROPPED = setOf("server", "model", "steps", "transcript", "generationSchema", "rendered", "usage", "supportedLanguages")

        /** Members whose presence is compared but not their value. */
        val MASKED = setOf("durationSeconds", "createdAt", "retryAfter", "schemaPath")

        const val OPEN_GATE =
            """{"name":"open_gate","description":"Ask the game to open a gate.","parameters":{"type":"object","properties":{"gate":{"type":"string"}},"required":["gate"]}}"""
        const val INVENTORY =
            """{"name":"check_inventory","description":"Look up stock and price of an item.","parameters":{"type":"object","properties":{"item":{"type":"string"}},"required":["item"]}}"""
        const val GORM = """{"name":"Gorm","role":"the village blacksmith","personality":"Gruff but fair."}"""

        fun reply(line: String, emotion: String = "neutral") =
            """{"json":{"emotion":"$emotion","line":"$line","player_options":["Buy one.","Goodbye."],"ends_conversation":false}}"""

        fun scripted(vararg steps: String) = """{"type":"scripted","steps":[${steps.joinToString(",")}]}"""

        val SCENARIOS: List<Pair<String, List<Pair<String, String>>>> = listOf(
            "core" to listOf(
                "initialize" to """{"client":{"name":"conformance"},"protocolVersion":"1.0"}""",
                "ping" to "{}",
                "npc/dance" to "{}",
                RAW to """{"jsonrpc":"2.0","id":1,"method":""",
                RAW to """[{"jsonrpc":"2.0","id":2,"method":"ping"}]""",
                RAW to """{"jsonrpc":"2.0","id":9007199254740993,"method":"ping"}""",
                RAW to """{"id":3,"method":"ping"}""",
                "session/list" to "[1]",
                "initialize" to """{"protocolVersion":"2.0"}""",
                "schema/validate" to """{"schema":{"type":"object","properties":{"mood":{"type":"string","enum":["calm","angry"]}},"required":["mood"]}}""",
                "schema/validate" to """{"schema":{"type":"quaternion"}}""",
                "tools/validate" to """{"tools":[$OPEN_GATE,{"type":"function","function":{"name":"wave","parameters":{"type":"object","properties":{}}}}]}""",
                "tools/validate" to """{"tools":[{"name":"a"},{"name":"a"}]}""",
                "session/create" to """{"options":{"toolTimeoutSeconds":1e19},"model":"scripted"}""",
                "shutdown" to "{}",
            ),
            "sessions" to listOf(
                "session/create" to """{"session":"guard","instructions":"You are a guard.","tools":[$OPEN_GATE],"options":{"toolChoice":"required"},
                    "model":${scripted(
                    """{"toolCalls":[{"name":"open_gate","arguments":{"gate":"north"}}]}""",
                    """{"template":"Gate: {toolOutput}"}""",
                    """{"toolCalls":[{"name":"open_gate","arguments":{"gate":"west"}}]}""",
                    """{"template":"West: {toolOutput}"}""",
                    """{"error":"guardrail_violation","message":"Unsafe."}""",
                    """{"text":"Recovered."}""",
                )}}""",
                "session/create" to """{"model":"scripted"}""",
                "session/create" to """{"session":"guard","model":"scripted"}""",
                "session/respond" to """{"session":"guard","prompt":"Open the north gate.","stream":true}""",
                "session/respond" to """{"session":"guard","prompt":"And the west one."}""",
                "session/respond" to """{"session":"guard","prompt":"Bad words.","toolChoice":"none"}""",
                "session/respond" to """{"session":"guard","prompt":"Hello?","toolChoice":"none"}""",
                "session/respond" to """{"session":"guard","prompt":"x","toolChoice":{"tool":"ghost"}}""",
                "session/respond" to """{"session":"guard"}""",
                "session/list" to "{}",
                "session/setInstructions" to """{"session":"guard","instructions":"You are a kind guard."}""",
                "session/setContextNote" to """{"session":"guard","note":"The player is a knight."}""",
                "session/setTools" to """{"session":"guard","tools":[$OPEN_GATE,{"name":"wave","description":"Wave."}]}""",
                "session/list" to "{}",
                "session/cancel" to """{"session":"guard"}""",
                "session/reset" to """{"session":"guard"}""",
                "session/list" to "{}",
                "session/delete" to """{"session":"s1"}""",
                "session/delete" to """{"session":"s1"}""",
                "session/respond" to """{"session":"s1","prompt":"gone"}""",
            ),
            "structured and compaction" to listOf(
                "session/create" to """{"session":"s","model":${scripted(
                    """{"json":{"choice":"haggle","reasoning":"Too cheap."}}""",
                    """{"text":"two"}""",
                    """{"text":"three"}""",
                    """{"text":"Three things happened."}""",
                )}}""",
                "session/respond" to """{"session":"s","prompt":"Decide","stream":true,"schema":{"type":"object","properties":{"reasoning":{"type":"string"},"choice":{"type":"string","enum":["sell","refuse","haggle"]}},"required":["reasoning","choice"]}}""",
                "session/respond" to """{"session":"s","prompt":"b"}""",
                "session/respond" to """{"session":"s","prompt":"c"}""",
                "session/respond" to """{"session":"s","prompt":"x","schema":{"type":"nope"}}""",
                "session/compact" to """{"session":"s","keepRecentTurns":1}""",
                "session/compact" to """{"session":"s","keepRecentTurns":5}""",
                "session/list" to "{}",
            ),
            "npcs" to listOf(
                "world/create" to """{"world":"village","state":{"player":{"name":"Aria","gold":60}}}""",
                "world/subscribe" to """{"world":"village","path":"quests"}""",
                "npc/create" to """{"npc":"gorm","persona":$GORM,"tools":[$INVENTORY],"world":"village",
                    "options":{"groundingTool":"check_inventory","worldWritable":["quests"],"maxToolRounds":2,"memoryTools":["rememberFact"]},
                    "model":${scripted(
                    """{"toolCalls":[{"name":"check_inventory","arguments":{"item":"iron sword"}}]}""",
                    """{"toolCalls":[{"name":"update_world_state","arguments":{"path":"quests.ring","value":"started"}}]}""",
                    reply("Three swords, lad. Find my ring.", "proud"),
                    """{"text":"Rain again."}""",
                    """{"toolCalls":[{"name":"check_inventory","arguments":{"item":"axe"}}]}""",
                    """{"error":"guardrail_violation"}""",
                    """{"text":"[worried] Let us not speak of it."}""",
                )}}""",
                "npc/create" to """{"npc":"gorm","persona":{"name":"Gorm"},"model":"scripted"}""",
                "npc/create" to """{"persona":{"name":"Ann","goals":"gold"},"model":"scripted"}""",
                "npc/create" to """{"persona":{"name":"Ann"},"options":{"replyFormat":"json"},"model":"scripted"}""",
                "npc/talk" to """{"npc":"gorm","line":"Iron swords?","stream":true}""",
                "npc/bark" to """{"npc":"gorm","situation":"It rains."}""",
                "npc/talk" to """{"npc":"gorm","line":"Tell me of the war."}""",
                "npc/list" to "{}",
                "npc/update" to """{"npc":"gorm","memory":{"relationship":25,"facts":["Aria likes axes."]},"persona":{"personality":"Grateful."}}""",
                "npc/state" to """{"npc":"gorm"}""",
                "npc/talk" to """{"npc":"gorm","line":"hi","toolChoice":{"tool":"nope"}}""",
                "npc/reset" to """{"npc":"gorm","clearMemory":true}""",
                "npc/list" to "{}",
                "npc/cancel" to """{"npc":"gorm"}""",
                "npc/delete" to """{"npc":"gorm"}""",
                "npc/talk" to """{"npc":"gorm","line":"Hello?"}""",
            ),
            "decisions and content" to listOf(
                "decision/decide" to """{"situation":"The goblin has 3 HP.","options":[{"id":"attack","description":"Fight"},{"id":"flee"},"beg"],
                    "actor":{"name":"Snik"},"context":{"hp":3},"model":${scripted("""{"json":{"reasoning":"Timid.","choice":"flee","confidence":78}}""")}}""",
                "decision/decide" to """{"situation":"Cornered.","options":["fight"],"model":"scripted"}""",
                "decision/decide" to """{"situation":"x","options":["a","a"],"model":"scripted"}""",
                "decision/decide" to """{"situation":"x","options":["a","b"],"fallbackOptionID":"b","model":${scripted("""{"error":"guardrail_violation"}""")}}""",
                "decision/decide" to """{"situation":"Night.","options":["camp","march"],"tools":[{"name":"weather","description":"Weather."}],"toolChoice":"required",
                    "model":${scripted("""{"toolCalls":[{"name":"weather"}]}""", """{"json":{"reasoning":"Rest.","choice":"camp","confidence":40}}""")}}""",
                "decision/decideMany" to """{"maxConcurrency":1,"requests":[{"situation":"A","options":["go","stay"]},{"situation":"B","options":["go","stay"]},{"situation":"C","options":["wait"]}],
                    "model":${scripted("""{"json":{"reasoning":"Duty.","choice":"go","confidence":90}}""", """{"error":"refusal"}""")}}""",
                "decision/decideMany" to """{"requests":[{"situation":"x","options":["a"]},{"situation":"y"}]}""",
                "content/generate" to """{"prompt":"A cursed sword.","schema":{"type":"object","properties":{"name":{"type":"string"},"rarity":{"type":"string","enum":["common","rare"]},"damage":{"type":"integer","minimum":1,"maximum":50}},"required":["name","rarity","damage"]},
                    "model":${scripted("""{"json":{"damage":45,"name":"Drowned Fang","rarity":"rare"}}""")}}""",
                "content/generate" to """{"prompt":"x","schema":{"type":"nope"},"model":"scripted"}""",
            ),
            "world" to listOf(
                "world/create" to """{"world":"village","state":{"player":{"name":"Aria","gold":60},"time":"dawn"}}""",
                "world/create" to "{}",
                "world/create" to """{"world":"village"}""",
                "world/subscribe" to """{"world":"village","path":"player"}""",
                "world/subscribe" to """{"world":"village"}""",
                "world/get" to """{"world":"village","path":"player.gold"}""",
                "world/get" to """{"world":"village","path":"player.horse"}""",
                "world/set" to """{"world":"village","path":"player.gold","value":45}""",
                "world/set" to """{"world":"village","path":"player.horse","value":null}""",
                "world/merge" to """{"world":"village","path":"player","patch":{"gold":15,"horse":null}}""",
                "world/set" to """{"world":"village","path":"player.gold.copper","value":3}""",
                "world/get" to """{"world":"village","path":"player..gold"}""",
                "world/remove" to """{"world":"village","path":"time"}""",
                "world/remove" to """{"world":"village","path":"time"}""",
                "world/snapshot" to """{"world":"village"}""",
                "world/list" to "{}",
                "world/unsubscribe" to """{"subscription":"sub1"}""",
                "world/unsubscribe" to """{"subscription":"sub1"}""",
                "world/delete" to """{"world":"village"}""",
                "world/get" to """{"world":"village"}""",
            ),
        )
    }
}
