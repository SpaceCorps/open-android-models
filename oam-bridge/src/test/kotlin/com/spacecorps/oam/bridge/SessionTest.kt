package com.spacecorps.oam.bridge

import com.spacecorps.oam.Transcript
import com.spacecorps.oam.testing.ScriptedLanguageModel
import com.spacecorps.oam.toJsonString
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Sessions, turns, streaming and client tools (open-apple-models' SessionTests). */
@Timeout(60)
class SessionTest {
    private val openGate = BridgeHarness.OPEN_GATE

    @Test
    fun lifecycle() = runBlocking {
        val harness = BridgeHarness()
        val created = harness.result(
            "session/create",
            """{"session": "gorm", "instructions": "You are Gorm.", "model": {"type": "scripted", "steps": [{"text": "Hmph."}]}}""",
        )
        assertEquals(j("""{"session": "gorm", "warnings": []}"""), created)
        assertEquals("s1", harness.result("session/create", """{"model": "scripted"}""")["session"].str)

        val duplicate = harness.call("session/create", """{"session": "gorm", "model": "scripted"}""")
        assertEquals(-32021, duplicate.errorCode)
        assertEquals("session_exists", duplicate.errorName)

        val list = harness.result("session/list")["sessions"]
        assertEquals(listOf("gorm", "s1"), (0..1).map { list[it]["session"].str })
        assertEquals("You are Gorm.", list[0]["instructions"].str)
        assertEquals("scripted", list[0]["model"].str)
        assertEquals(listOf("session", "model", "instructions", "tools", "busy", "pendingOperations", "entries", "createdAt"), list[0].keys)

        harness.result("session/respond", """{"session": "gorm", "prompt": "Hello"}""")
        val transcript = harness.result("session/transcript", """{"session": "gorm"}""")["transcript"]
        assertEquals(Transcript.FORMAT, transcript["type"].str)

        assertEquals(j("""{"session": "gorm", "deleted": true}"""), harness.result("session/delete", """{"session": "gorm"}"""))
        val missing = harness.call("session/respond", """{"session": "gorm", "prompt": "Hello?"}""")
        assertEquals(-32020, missing.errorCode)
        assertEquals("session_not_found", missing.errorName)
        assertEquals("gorm", missing["error"]["data"]["session"].str)
        assertEquals(-32020, harness.call("session/delete", """{"session": "gorm"}""").errorCode)
    }

    @Test
    fun manyToolsAreWarnedAbout() = runBlocking {
        val harness = BridgeHarness()
        val tools = (1..6).joinToString(", ", "[", "]") { """{"name": "tool_$it", "description": "Does thing $it."}""" }
        val created = harness.result("session/create", """{"model": "scripted", "tools": $tools}""")
        val warnings = created["warnings"].strings()
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("6 tools"))
        val three = (1..3).joinToString(", ", "[", "]") { """{"name": "tool_$it", "description": "Does thing $it."}""" }
        val replaced = harness.result("session/setTools", """{"session": "${created["session"].str}", "tools": $three}""")
        assertEquals(j("[]"), replaced["warnings"])
    }

    @Test
    fun sessionLimit() = runBlocking {
        val harness = BridgeHarness(BridgeConfiguration(modelAvailability = { BridgeHarness.TEST_AVAILABILITY }, maxSessions = 1))
        harness.createSession()
        val second = harness.call("session/create", """{"model": "scripted"}""")
        assertEquals(-32022, second.errorCode)
        assertEquals("session_limit", second.errorName)
        assertEquals(1, second["error"]["data"]["limit"].int)
    }

    @Test
    fun createWarnsAboutUnknownKeysAndAnUnavailableModel() = runBlocking {
        val harness = BridgeHarness(
            BridgeConfiguration(
                systemModel = ScriptedLanguageModel(),
                modelAvailability = { BridgeModelAvailability(available = false, reason = "device_not_eligible") },
            ),
        )
        val warnings = harness.result("session/create", """{"instructionz": "typo", "options": {"temprature": 0.5}}""")["warnings"].strings()
        assertTrue(warnings.any { "'instructionz'" in it }, "$warnings")
        assertTrue(warnings.any { "'options.temprature'" in it }, "$warnings")
        assertTrue(warnings.any { "device_not_eligible" in it }, "$warnings")
    }

    @Test
    fun respondWithoutStreaming() = runBlocking {
        val harness = BridgeHarness()
        val id = harness.createSession(steps = """[{"text": "Welcome to the forge."}]""")
        val request = harness.send("session/respond", """{"session": "$id", "prompt": "Hi"}""")
        val result = harness.response(request)["result"]
        assertEquals(id, result["session"].str)
        assertEquals("Welcome to the forge.", result["text"].str)
        assertEquals(j("[]"), result["toolCalls"])
        assertNotNull(result["usage"]["outputTokens"].int)
        assertEquals(listOf("inputTokens", "cachedInputTokens", "outputTokens", "totalTokens"), result["usage"].keys)
        assertEquals(1, (result["steps"] as JsonArray).size)
        assertEquals("disallowed", result["steps"][0]["toolCallingMode"].str)
        assertEquals("respond", result["steps"][0]["kind"].str)
        assertEquals(listOf("session", "text", "toolCalls", "usage", "steps"), result.keys)
        assertTrue(harness.notifications("session/event", request).isEmpty())
    }

    @Test
    fun streamingEventsPrecedeTheResult() = runBlocking {
        val harness = BridgeHarness()
        val id = harness.createSession(steps = """[{"text": "Steel is forged in fire and patience.", "chunks": 5}]""")
        val request = harness.send("session/respond", """{"session": "$id", "prompt": "Wisdom?", "stream": true}""")
        val response = harness.response(request)
        val events = harness.notifications("session/event", request)
        val lastEvent = harness.box.lastIndex { it["method"].str == "session/event" }!!
        assertTrue(lastEvent < harness.responseIndex(request)!!)

        val types = events.map { it["params"]["event"]["type"].str }
        assertEquals("modelStep", types.first())
        assertTrue("text" in types)
        assertTrue(events.all { it["params"]["session"].str == id })
        val deltas = events.filter { it["params"]["event"]["type"].str == "text" }.joinToString("") { it["params"]["event"]["delta"].str!! }
        assertEquals("Steel is forged in fire and patience.", deltas)
        assertEquals("Steel is forged in fire and patience.", response["result"]["text"].str)
    }

    @Test
    fun structuredOutput() = runBlocking {
        val harness = BridgeHarness()
        val id = harness.createSession(steps = """[{"json": {"choice": "haggle", "reasoning": "Too cheap."}}]""")
        val schema = """{"type": "object", "properties": {"reasoning": {"type": "string"}, "choice": {"type": "string", "enum": ["sell", "refuse", "haggle"]}}, "required": ["reasoning", "choice"]}"""
        val request = harness.send("session/respond", """{"session": "$id", "prompt": "Decide", "schema": $schema, "stream": true}""")
        val result = harness.response(request)["result"]
        assertEquals("haggle", result["structured"]["choice"].str)
        // Keys follow the schema's order.
        assertEquals(listOf("reasoning", "choice"), result["structured"].keys)
        assertTrue(result["text"].str!!.contains("haggle"))
        assertTrue("partial" in harness.events("session/event", request).map { it["type"].str })

        assertEquals(-32007, harness.call("session/respond", """{"session": "$id", "prompt": "x", "schema": {"type": "nope"}}""").errorCode)
    }

    @Test
    fun clientToolRoundTrip() = runBlocking {
        val harness = BridgeHarness()
        val id = harness.createSession(
            steps = """[{"toolCalls": [{"name": "open_gate", "arguments": {"gate": "north"}}]}, {"template": "Gate result: {toolOutput}"}]""",
            tools = "[$openGate]",
            options = """{"toolChoice": "required"}""",
        )
        val request = harness.send("session/respond", """{"session": "$id", "prompt": "Open the north gate", "stream": true}""")

        val toolCall = harness.box.wait { it["method"].str == "tool/call" }
        val params = toolCall["params"]
        assertEquals(id, params["session"].str)
        assertEquals(request, params["requestId"].str)
        assertEquals("open_gate", params["call"]["name"].str)
        assertEquals(j("""{"gate": "north"}"""), params["call"]["arguments"])
        assertEquals(listOf("session", "requestId", "call"), params.keys)
        val callId = toolCall["id"].str!!
        assertTrue(callId.startsWith("t-"))

        // The turn waits for the engine.
        delay(50)
        assertNull(harness.responseIndex(request))

        harness.engine.receive("""{"jsonrpc": "2.0", "id": "$callId", "result": {"output": {"opened": true}}}""")
        val result = harness.response(request)["result"]
        assertEquals("""Gate result: {"opened":true}""", result["text"].str)
        val record = result["toolCalls"][0]
        assertEquals("open_gate", record["call"]["name"].str)
        assertEquals(j("""{"opened": true}"""), record["output"])
        assertEquals(JsonPrimitive(false), record["isError"])
        assertEquals(listOf("call", "output", "isError", "durationSeconds"), record.keys)
        assertEquals("required", result["steps"][0]["toolCallingMode"].str)

        val events = harness.events("session/event", request)
        val started = events.first { it["type"].str == "toolCallStarted" }
        assertEquals("client", started["execution"].str)
        assertTrue(events.any { it["type"].str == "toolCallCompleted" })
        // The toolCallStarted event, then tool/call, then the response.
        val startedIndex = harness.box.index { it["params"]["event"]["type"].str == "toolCallStarted" }!!
        val toolIndex = harness.box.index { it["method"].str == "tool/call" }!!
        assertTrue(startedIndex < toolIndex)
        assertTrue(toolIndex < harness.responseIndex(request)!!)
    }

    @Test
    fun toolErrorReplies() = runBlocking {
        val harness = BridgeHarness()
        harness.box.responder = MessageBox.ToolResponder { call, _ ->
            if (call["arguments"]["gate"].str == "north") {
                j("""{"error": {"code": -32000, "message": "Gate subsystem offline"}}""")
            } else {
                j("""{"output": "The chain is jammed.", "isError": true}""")
            }
        }
        val id = harness.createSession(
            steps = """[
                {"toolCalls": [{"name": "open_gate", "arguments": {"gate": "north"}}]}, {"template": "1:{toolOutput}"},
                {"toolCalls": [{"name": "open_gate", "arguments": {"gate": "south"}}]}, {"template": "2:{toolOutput}"}
            ]""",
            tools = "[$openGate]",
        )
        val first = harness.result("session/respond", """{"session": "$id", "prompt": "north"}""")
        assertEquals(JsonPrimitive(true), first["toolCalls"][0]["isError"])
        assertEquals("Gate subsystem offline", first["toolCalls"][0]["output"].str)
        assertEquals("1:Error: Gate subsystem offline", first["text"].str)

        val second = harness.result("session/respond", """{"session": "$id", "prompt": "south"}""")
        assertEquals(JsonPrimitive(true), second["toolCalls"][0]["isError"])
        assertEquals("2:Error: The chain is jammed.", second["text"].str)
    }

    @Test
    fun textAndLenientToolOutputs() = runBlocking {
        val harness = BridgeHarness()
        harness.box.responder = MessageBox.ToolResponder { call, _ ->
            if (call["arguments"]["gate"].str == "east") j("""{"output": "Opened."}""") else j("""{"opened": false}""")
        }
        val id = harness.createSession(
            steps = """[{"toolCalls": [{"name": "open_gate", "arguments": {"gate": "east"}}, {"name": "open_gate", "arguments": {"gate": "west"}}]}, {"template": "{toolOutputs}"}]""",
            tools = "[$openGate]",
        )
        val result = harness.result("session/respond", """{"session": "$id", "prompt": "both"}""")
        val outputs = (result["toolCalls"] as JsonArray).map { it["output"] }.toSet()
        assertEquals(setOf(JsonPrimitive("Opened."), j("""{"opened": false}""")), outputs)
        assertTrue(result["text"].str!!.contains("Opened."))
    }

    @Test
    fun toolTimeoutSendsToolCancel() = runBlocking {
        val harness = BridgeHarness()
        val gate = openGate.dropLast(1) + """, "timeoutSeconds": 0.2}"""
        val id = harness.createSession(
            steps = """[{"toolCalls": [{"name": "open_gate", "arguments": {"gate": "north"}}]}, {"template": "{toolOutput}"}]""",
            tools = "[$gate]",
        )
        val request = harness.send("session/respond", """{"session": "$id", "prompt": "Open"}""")
        val toolCall = harness.box.wait { it["method"].str == "tool/call" }
        val result = harness.response(request)["result"]
        assertEquals(JsonPrimitive(true), result["toolCalls"][0]["isError"])
        assertTrue(result["text"].str!!.contains("timed out"), result["text"].str)

        val cancel = harness.box.messages.first { it["method"].str == "tool/cancel" }
        assertEquals(toolCall["id"], cancel["params"]["id"])
        assertEquals(toolCall["params"]["call"]["id"], cancel["params"]["callId"])
        assertEquals(id, cancel["params"]["session"].str)
        assertEquals(request, cancel["params"]["requestId"].str)
        assertTrue(harness.box.index { it["method"].str == "tool/cancel" }!! < harness.responseIndex(request)!!)

        // A late reply is ignored.
        harness.engine.receive("""{"jsonrpc": "2.0", "id": ${toolCall["id"]}, "result": {"output": "late"}}""")
        harness.result("ping")
    }

    @Test
    fun slowTurnsDoNotBlockOtherRequests() = runBlocking {
        val harness = BridgeHarness()
        val a = harness.createSession(steps = """[{"text": "A done", "delayMs": 600}]""")
        val b = harness.createSession(steps = """[{"text": "B done", "delayMs": 600}]""")
        val start = TimeSource.Monotonic.markNow()
        val requestA = harness.send("session/respond", """{"session": "$a", "prompt": "go"}""")
        val requestB = harness.send("session/respond", """{"session": "$b", "prompt": "go"}""")
        // Other requests are served while both turns run.
        val created = harness.call("session/create", """{"model": "scripted"}""")
        assertNull(harness.responseIndex(requestA))
        assertNotNull(created["result"])
        assertEquals("A done", harness.response(requestA)["result"]["text"].str)
        assertEquals("B done", harness.response(requestB)["result"]["text"].str)
        // The two sessions ran concurrently.
        assertTrue(start.elapsedNow() < 1100.milliseconds, "took ${start.elapsedNow()}")
    }

    @Test
    fun concurrentSessionsWithClientToolsKeepPerRequestOrder() = runBlocking {
        val harness = BridgeHarness()
        harness.box.responder = MessageBox.ToolResponder { _, params -> j("""{"output": "ok ${params["session"].str}"}""") }
        val sessions = (0 until 8).map { index ->
            harness.createSession(
                id = "c$index",
                steps = """[{"toolCalls": [{"name": "open_gate", "arguments": {"gate": "g$index"}}], "delayMs": ${index % 3 * 10}}, {"template": "{toolOutput}", "chunks": 4}]""",
                tools = "[$openGate]",
            )
        }
        val sent = sessions.map { it to harness.send("session/respond", """{"session": "$it", "prompt": "go", "stream": true}""") }
        for ((session, request) in sent) assertEquals("ok $session", harness.response(request)["result"]["text"].str)
        harness.engine.flush()
        val messages = harness.box.messages
        for ((session, request) in sent) {
            val responses = messages.indices.filter { messages[it]["id"].str == request && messages[it]["method"] == null }
            assertEquals(1, responses.size)
            val related = messages.indices.filter { messages[it]["params"]["requestId"].str == request }
            assertTrue(related.any { messages[it]["method"].str == "tool/call" })
            assertTrue(related.all { messages[it]["params"]["session"].str == session })
            assertTrue(related.all { it < responses.single() })
        }
    }

    @Test
    fun pipelinedRequestsKeepTheirOrder() = runBlocking {
        val harness = BridgeHarness()
        val model = """{"type": "scripted", "steps": [{"template": "A:{prompt}", "delayMs": 50}, {"template": "B:{prompt}"}]}"""
        // Nothing awaited between these sends.
        val create = harness.send("session/create", """{"session": "pipe", "model": $model}""")
        val first = harness.send("session/respond", """{"session": "pipe", "prompt": "first"}""")
        val second = harness.send("session/respond", """{"session": "pipe", "prompt": "second"}""")
        val rename = harness.send("session/setInstructions", """{"session": "pipe", "instructions": "Later."}""")
        assertEquals("pipe", harness.response(create)["result"]["session"].str)
        assertEquals("A:first", harness.response(first)["result"]["text"].str)
        assertEquals("B:second", harness.response(second)["result"]["text"].str)
        assertEquals(j("""{"session": "pipe"}"""), harness.response(rename)["result"])
        assertEquals("Later.", harness.result("session/list")["sessions"][0]["instructions"].str)
    }

    @Test
    fun cancelRunningAndQueuedTurns() = runBlocking {
        val harness = BridgeHarness()
        val id = harness.createSession(steps = """[{"text": "slow", "delayMs": 5000}, {"text": "slower", "delayMs": 5000}]""")
        val first = harness.send("session/respond", """{"session": "$id", "prompt": "one"}""")
        val second = harness.send("session/respond", """{"session": "$id", "prompt": "two"}""")
        delay(100)
        assertEquals(2, harness.result("session/cancel", """{"session": "$id"}""")["cancelled"].int)
        val firstResponse = harness.response(first)
        assertEquals(-32009, firstResponse.errorCode)
        assertEquals("cancelled", firstResponse.errorName)
        assertEquals(-32009, harness.response(second).errorCode)
        // Cancelled turns leave no history.
        val list = harness.result("session/list")
        assertEquals(0, list["sessions"][0]["entries"].int)
        assertEquals(JsonPrimitive(false), list["sessions"][0]["busy"])
    }

    @Test
    fun cancelWhileWaitingForAClientTool() = runBlocking {
        val harness = BridgeHarness()
        val id = harness.createSession(steps = """[{"toolCalls": [{"name": "open_gate", "arguments": {"gate": "x"}}]}]""", tools = "[$openGate]")
        val request = harness.send("session/respond", """{"session": "$id", "prompt": "Open"}""")
        val toolCall = harness.box.wait { it["method"].str == "tool/call" }
        harness.notify("session/cancel", j("""{"session": "$id"}"""))
        assertEquals(-32009, harness.response(request).errorCode)
        val cancel = harness.box.wait { it["method"].str == "tool/cancel" }
        assertEquals(toolCall["id"], cancel["params"]["id"])
        assertTrue(harness.box.index { it["method"].str == "tool/cancel" }!! < harness.responseIndex(request)!!)
    }

    @Test
    fun modelErrorsMapToApplicationCodes() = runBlocking {
        val harness = BridgeHarness()
        val id = harness.createSession(steps = """[{"error": "guardrail_violation", "message": "Unsafe content."}, {"error": "context_size_exceeded"}, {"text": "Recovered."}]""")
        val guardrail = harness.call("session/respond", """{"session": "$id", "prompt": "x"}""")
        assertEquals(-32002, guardrail.errorCode)
        assertEquals("guardrail_violation", guardrail.errorName)
        assertTrue(guardrail.errorMessage!!.contains("Unsafe content."))
        val context = harness.call("session/respond", """{"session": "$id", "prompt": "y"}""")
        assertEquals(-32004, context.errorCode)
        assertEquals("context_size_exceeded", context.errorName)
        // The session is still usable afterwards, and the failed turns left no trace.
        assertEquals("Recovered.", harness.result("session/respond", """{"session": "$id", "prompt": "z"}""")["text"].str)
        assertEquals(2, harness.result("session/list")["sessions"][0]["entries"].int)
    }

    @Test
    fun rateLimitsCarryTheRetryDelay() = runBlocking {
        val model = ScriptedLanguageModel(
            listOf(
                ScriptedLanguageModel.Step.Fail(
                    com.spacecorps.oam.AgentError(com.spacecorps.oam.AgentErrorCode.RATE_LIMITED, "AICore quota exceeded.", retryAfter = 30.seconds),
                ),
            ),
            style = ScriptedLanguageModel.ScriptStyle.NATIVE,
        )
        val harness = BridgeHarness(BridgeConfiguration(systemModel = model, modelAvailability = { BridgeHarness.TEST_AVAILABILITY }))
        val id = harness.result("session/create", "{}")["session"].str
        val limited = harness.call("session/respond", """{"session": "$id", "prompt": "x"}""")
        assertEquals(-32005, limited.errorCode)
        assertEquals("rate_limited", limited.errorName)
        assertEquals(30, limited["error"]["data"]["retryAfterSeconds"].int)
        assertNotNull(limited["error"]["data"]["retryAfter"].str)
    }

    @Test
    fun transcriptExportAndRestore() = runBlocking {
        val harness = BridgeHarness()
        val original = harness.createSession(steps = """[{"text": "Name's Gorm."}]""", instructions = "You are Gorm.")
        harness.result("session/respond", """{"session": "$original", "prompt": "Who are you?"}""")
        val exported = harness.result("session/transcript", """{"session": "$original"}""")
        // Round-trips through text, as a game would save it to disk.
        val saved = Json.parseToJsonElement(exported["transcript"]!!.toJsonString())
        val restored = harness.result(
            "session/create",
            JsonObject(
                mapOf(
                    "instructions" to JsonPrimitive("You are Gorm."),
                    "history" to saved,
                    "model" to j("""{"type": "scripted", "steps": [{"template": "Again: {prompt}"}]}"""),
                ),
            ),
        )
        val restoredId = restored["session"].str
        val list = harness.result("session/list")["sessions"] as JsonArray
        assertEquals(2, list.first { it["session"].str == restoredId }["entries"].int)
        assertEquals("Again: Still Gorm?", harness.result("session/respond", """{"session": "$restoredId", "prompt": "Still Gorm?"}""")["text"].str)
        val after = harness.result("session/list")["sessions"] as JsonArray
        assertEquals(4, after.first { it["session"].str == restoredId }["entries"].int)

        // The whole session/transcript result is accepted too.
        assertNotNull(harness.call("session/create", JsonObject(mapOf("history" to exported, "model" to JsonPrimitive("scripted"))))["result"])
        assertEquals(-32602, harness.call("session/create", """{"history": {"entries": 5}, "model": "scripted"}""").errorCode)
        assertEquals(-32602, harness.call("session/create", """{"history": {"hello": "world"}, "model": "scripted"}""").errorCode)
    }

    @Test
    fun historyAloneRestoresInstructionsAndTools() = runBlocking {
        val harness = BridgeHarness()
        harness.box.responder = MessageBox.ToolResponder { call, _ -> j("""{"output": "opened ${call["arguments"]["gate"].str}"}""") }
        val original = harness.createSession(steps = """[{"text": "Aye."}]""", tools = "[$openGate]", instructions = "You are a guard.")
        harness.result("session/respond", """{"session": "$original", "prompt": "Hello"}""")
        val saved = harness.result("session/transcript", """{"session": "$original"}""")["transcript"]!!

        val restored = harness.result(
            "session/create",
            JsonObject(
                mapOf(
                    "history" to saved,
                    "model" to j(
                        """{"type": "scripted", "steps": [{"toolCalls": [{"name": "open_gate", "arguments": {"gate": "east"}}]}, {"template": "{toolOutput}"}]}""",
                    ),
                ),
            ),
        )
        val id = restored["session"].str
        assertEquals(j("[]"), restored["warnings"])
        val entry = (harness.result("session/list")["sessions"] as JsonArray).first { it["session"].str == id }
        assertEquals("You are a guard.", entry["instructions"].str)
        assertEquals(listOf("open_gate"), entry["tools"].strings())
        assertEquals(2, entry["entries"].int)
        val reply = harness.result("session/respond", """{"session": "$id", "prompt": "Open east", "toolChoice": {"tool": "open_gate"}}""")
        assertEquals("opened east", reply["text"].str)
        assertEquals(listOf("open_gate"), reply["steps"][0]["enabledTools"].strings())

        // Keys that are present win over the saved values, even when null or empty.
        val overridden = harness.result(
            "session/create",
            JsonObject(mapOf("history" to saved, "instructions" to JsonNull, "tools" to j("[]"), "model" to JsonPrimitive("scripted"))),
        )
        val other = (harness.result("session/list")["sessions"] as JsonArray).first { it["session"] == overridden["session"] }
        assertNull(other["instructions"])
        assertEquals(j("[]"), other["tools"])
    }

    @Test
    fun mutatingSessionSettings() = runBlocking {
        val harness = BridgeHarness()
        harness.box.responder = MessageBox.ToolResponder { call, _ -> j("""{"output": "waved at ${call["arguments"]["target"].str}"}""") }
        val id = harness.createSession(
            steps = """[{"toolCalls": [{"name": "wave", "arguments": {"target": "player"}}]}, {"template": "{toolOutput}"}]""",
            tools = "[$openGate]",
        )
        val setTools = harness.result(
            "session/setTools",
            """{"session": "$id", "tools": [{"name": "wave", "description": "Wave at someone.", "parameters": {"type": "object", "properties": {"target": {"type": "string"}}}}]}""",
        )
        assertEquals(j("[]"), setTools["warnings"])
        harness.result("session/setInstructions", """{"session": "$id", "instructions": "You are friendly."}""")
        harness.result("session/setContextNote", """{"session": "$id", "note": "The player saved the village."}""")
        val list = harness.result("session/list")["sessions"][0]
        assertEquals(listOf("wave"), list["tools"].strings())
        assertEquals("You are friendly.", list["instructions"].str)

        val reply = harness.result("session/respond", """{"session": "$id", "prompt": "Greet the player", "toolChoice": {"tool": "wave"}}""")
        assertEquals("waved at player", reply["text"].str)
        assertEquals(listOf("wave"), reply["steps"][0]["enabledTools"].strings())
        assertEquals("required", reply["steps"][0]["toolCallingMode"].str)
        assertEquals("toolArguments", reply["steps"][0]["kind"].str)
        val transcript = harness.result("session/transcript", """{"session": "$id"}""")
        assertEquals("The player saved the village.", transcript["transcript"]["transcript"]["contextNote"].str)

        harness.result("session/reset", """{"session": "$id"}""")
        val afterReset = harness.result("session/list")["sessions"][0]
        assertEquals(0, afterReset["entries"].int)
        assertEquals("You are friendly.", afterReset["instructions"].str)
    }

    @Test
    fun perTurnPolicyOverrides() = runBlocking {
        val harness = BridgeHarness()
        val id = harness.createSession(steps = """[{"text": "no tools today"}]""", tools = "[$openGate]", options = """{"toolChoice": "required", "maxToolRounds": 2}""")
        val reply = harness.result("session/respond", """{"session": "$id", "prompt": "hi", "toolChoice": "none"}""")
        assertEquals("disallowed", reply["steps"][0]["toolCallingMode"].str)
        assertEquals(j("[]"), reply["toolCalls"])
        assertEquals(-32602, harness.call("session/respond", """{"session": "$id", "prompt": "hi", "toolChoice": {"tool": "ghost"}}""").errorCode)
    }

    @Test
    fun toolChoiceSeesToolsFromAPipelinedSetTools() = runBlocking {
        val harness = BridgeHarness()
        harness.box.responder = MessageBox.ToolResponder { _, _ -> j("""{"output": "waved"}""") }
        val session = harness.createSession(steps = """[{"toolCalls": [{"name": "wave"}]}, {"template": "{toolOutput}"}]""")
        // Nothing awaited in between: the turn must see the new tool.
        val setTools = harness.send("session/setTools", """{"session": "$session", "tools": [{"name": "wave", "description": "Wave."}]}""")
        val respond = harness.send("session/respond", """{"session": "$session", "prompt": "hi", "toolChoice": {"tool": "wave"}}""")
        assertNull(harness.response(setTools)["error"])
        val reply = harness.response(respond)
        assertNull(reply["error"])
        assertEquals("waved", reply["result"]["text"].str)

        val ghost = harness.call("session/respond", """{"session": "$session", "prompt": "hi", "toolChoice": {"tool": "ghost"}}""")
        assertEquals(-32602, ghost.errorCode)
        assertTrue(ghost.errorMessage!!.contains("ghost"))
    }

    @Test
    fun requestIdTagsKeepLargeNumericIdsExact() = runBlocking {
        val harness = BridgeHarness()
        harness.box.responder = MessageBox.ToolResponder { _, _ -> j("""{"output": "opened"}""") }
        val session = harness.createSession(
            steps = """[{"toolCalls": [{"name": "open_gate", "arguments": {"gate": "x"}}]}, {"text": "done"}]""",
            tools = "[$openGate]",
        )
        harness.engine.receive("""{"jsonrpc":"2.0","id":1234567890123456,"method":"session/respond","params":{"session":"$session","prompt":"go","stream":true}}""")
        harness.box.wait { it["id"].toString() == "1234567890123456" && it["method"] == null }
        val lines = harness.box.lines
        assertTrue(lines.any { """"method":"session/event"""" in it && """"requestId":1234567890123456""" in it })
        assertTrue(lines.any { """"method":"tool/call"""" in it && """"requestId":1234567890123456""" in it })
        assertTrue(lines.any { it.startsWith("""{"jsonrpc":"2.0","id":1234567890123456,"result":""") })
        assertTrue(lines.none { "E15" in it || "e+15" in it })
    }

    // MARK: Compaction

    @Test
    fun compactHistory() = runBlocking {
        val harness = BridgeHarness()
        val id = sessionReadyToCompact(harness, delayMs = 0)
        val compacted = harness.result("session/compact", """{"session": "$id", "keepRecentTurns": 1}""")
        assertEquals("The player asked three things.", compacted["summary"].str)
        assertEquals(2, harness.result("session/list")["sessions"][0]["entries"].int)
        val transcript = harness.result("session/transcript", """{"session": "$id"}""")
        assertEquals("The player asked three things.", transcript["transcript"]["transcript"]["contextNote"].str)
        assertEquals(JsonNull, harness.result("session/compact", """{"session": "$id", "keepRecentTurns": 5}""")["summary"])
    }

    @Test
    fun cancelledCompactionLeavesTheHistoryAlone() = runBlocking {
        val harness = BridgeHarness()
        val id = sessionReadyToCompact(harness, delayMs = 400)
        val compact = harness.send("session/compact", """{"session": "$id", "keepRecentTurns": 1}""")
        delay(100)
        assertEquals(1, harness.result("session/cancel", """{"session": "$id"}""")["cancelled"].int)
        assertEquals(-32009, harness.response(compact).errorCode)
        // Well past the summary's delay: the history was not rewritten.
        delay(600)
        val list = harness.result("session/list")["sessions"][0]
        assertEquals(6, list["entries"].int)
        assertEquals(JsonPrimitive(false), list["busy"])
        assertTrue("The player asked three things." !in harness.result("session/transcript", """{"session": "$id"}""").toJsonString())
    }

    @Test
    fun deletingASessionCancelsItsCompaction() = runBlocking {
        val harness = BridgeHarness()
        val id = sessionReadyToCompact(harness, delayMs = 400)
        val compact = harness.send("session/compact", """{"session": "$id", "keepRecentTurns": 1}""")
        delay(100)
        harness.result("session/delete", """{"session": "$id"}""")
        assertEquals(-32009, harness.response(compact).errorCode)
    }

    /** Three turns, then a compaction whose summary takes [delayMs]. */
    private suspend fun sessionReadyToCompact(harness: BridgeHarness, delayMs: Int): String {
        val id = harness.createSession(
            steps = """[{"text": "one"}, {"text": "two"}, {"text": "three"}, {"text": "The player asked three things.", "delayMs": $delayMs}]""",
        )
        for (prompt in listOf("a", "b", "c")) harness.result("session/respond", """{"session": "$id", "prompt": "$prompt"}""")
        return id
    }

    // MARK: Android additions

    @Test
    fun samplingAndLabelsReachTheModel() = runBlocking {
        val model = ScriptedLanguageModel(listOf(ScriptedLanguageModel.Step.Text("Aye.")), style = ScriptedLanguageModel.ScriptStyle.NATIVE)
        val harness = BridgeHarness(BridgeConfiguration(systemModel = model, modelAvailability = { BridgeHarness.TEST_AVAILABILITY }))
        val created = harness.result(
            "session/create",
            """{"options": {"sampling": {"topP": 0.9, "seed": 7}, "temperature": 0.3, "maxResponseTokens": 64, "userLabel": "Player", "assistantLabel": "Gorm"}}""",
        )
        assertTrue(created["warnings"].strings().any { "topP" in it })
        val id = created["session"].str
        harness.result("session/respond", """{"session": "$id", "prompt": "Hi"}""")
        val request = model.requests.single()
        assertEquals(7, request.seed)
        assertEquals(0.3, request.temperature)
        assertEquals(64, request.maxOutputTokens)

        val greedy = harness.result("session/create", """{"options": {"sampling": "greedy"}, "model": "scripted"}""")
        assertEquals(j("[]"), greedy["warnings"])
    }

    @Test
    fun concurrentTurnsOnOneSessionAreSerialized() = runBlocking {
        val harness = BridgeHarness()
        val id = harness.createSession(steps = (1..5).joinToString(", ", "[", "]") { """{"template": "$it:{prompt}", "delayMs": ${(5 - it) * 10}}""" })
        val requests = (1..5).map { harness.send("session/respond", """{"session": "$id", "prompt": "p$it"}""") }
        val texts = requests.map { harness.response(it)["result"]["text"].str }
        assertEquals((1..5).map { "$it:p$it" }, texts)
        val order = Collections.unmodifiableList(requests.map { harness.responseIndex(it)!! })
        assertEquals(order.sorted(), order)
    }
}
