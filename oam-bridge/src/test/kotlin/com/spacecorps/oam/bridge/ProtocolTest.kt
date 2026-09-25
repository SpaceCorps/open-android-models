package com.spacecorps.oam.bridge

import com.spacecorps.oam.EmptyJsonObject
import com.spacecorps.oam.ModelCapabilities
import com.spacecorps.oam.testing.ScriptedLanguageModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Framing, error handling, core methods and lifecycle (open-apple-models' ProtocolTests and RobustnessTests). */
@Timeout(60)
class ProtocolTest {
    @Test
    fun initializeReportsProtocolAndCapabilities() = runBlocking<Unit> {
        val harness = BridgeHarness()
        val result = harness.result("initialize", """{"client": {"name": "unit-test", "version": "1"}, "protocolVersion": "1.0"}""")
        assertEquals("1.0", result["protocolVersion"].str)
        assertEquals("open-android-models", result["server"]["name"].str)
        assertEquals(BridgeVersion.LIBRARY, result["server"]["version"].str)
        val methods = result["capabilities"]["methods"].strings()
        for (method in listOf(
            "initialize", "ping", "model/availability", "session/create", "session/respond", "session/cancel", "session/reset",
            "session/delete", "session/list", "session/transcript", "session/setInstructions", "session/setContextNote",
            "session/setTools", "session/compact", "schema/validate", "tools/validate", "shutdown",
        )) {
            assertTrue(method in methods, "missing $method")
        }
        assertEquals(listOf("tool/call"), result["capabilities"]["clientRequests"].strings())
        assertEquals(listOf("session/event", "tool/cancel", "npc/event", "world/changed"), result["capabilities"]["notifications"].strings())
        assertEquals(listOf("system", "scripted"), result["capabilities"]["models"].strings())
        assertEquals(JsonPrimitive(false), result["capabilities"]["batch"])
        assertEquals(64, result["capabilities"]["maxSessions"].int)
        assertEquals(JsonPrimitive(true), result["model"]["available"])
        assertEquals(4096, result["model"]["contextSize"].int)
        assertEquals("unit-test", harness.engine.clientInfo["name"].str)
    }

    @Test
    fun initializeRejectsAnIncompatibleProtocolVersion() = runBlocking<Unit> {
        val harness = BridgeHarness()
        assertEquals(-32602, harness.call("initialize", """{"protocolVersion": "2.0"}""").errorCode)
        assertNotNull(harness.result("initialize", """{"protocolVersion": "1.3"}""")["protocolVersion"])
    }

    @Test
    fun pingAndAvailability() = runBlocking<Unit> {
        val harness = BridgeHarness(
            BridgeConfiguration(
                modelAvailability = {
                    BridgeModelAvailability(available = false, reason = "model_not_ready", contextSize = 4000, status = "downloadable")
                },
            ),
        )
        assertEquals(EmptyJsonObject, harness.result("ping"))
        assertEquals(
            j("""{"available": false, "reason": "model_not_ready", "contextSize": 4000, "supportedLanguages": [], "status": "downloadable"}"""),
            harness.result("model/availability"),
        )
    }

    @Test
    fun theSystemModelsAvailabilityIsReported() = runBlocking<Unit> {
        val nano = ScriptedLanguageModel(
            capabilities = ModelCapabilities(maxInputTokens = 3800, maxOutputTokens = 256, modelName = "nano-v4"),
            availability = com.spacecorps.oam.ModelAvailability.Downloading(bytesDownloaded = 10, totalBytes = 100),
        )
        val harness = BridgeHarness(BridgeConfiguration(systemModel = nano))
        val availability = harness.result("model/availability")
        assertEquals(JsonPrimitive(false), availability["available"])
        assertEquals("model_not_ready", availability["reason"].str)
        assertEquals("downloading", availability["status"].str)
        assertEquals(3800, availability["contextSize"].int)
        assertEquals("nano-v4", availability["variant"].str)
        assertEquals(10, availability["bytesDownloaded"].int)
        assertEquals(100, availability["totalBytes"].int)

        // Without a system model, "system" turns fail with model_unavailable; scripted ones work.
        val bare = BridgeHarness(BridgeConfiguration())
        assertEquals("unknown", bare.result("model/availability")["reason"].str)
        val created = bare.call("session/create", "{}")
        assertEquals(-32001, created.errorCode)
        assertEquals("model_unavailable", created.errorName)
        assertNotNull(bare.result("session/create", """{"model": "scripted"}""")["session"])
    }

    @Test
    fun parseErrorsHaveANullId() = runBlocking<Unit> {
        val harness = BridgeHarness()
        harness.engine.receive("""{"jsonrpc": "2.0", "id": 1, "method": """)
        harness.engine.receive("""{"jsonrpc": "2.0", "id": 2, "method": "ping", "params": {"x": bare}}""")
        harness.engine.flush()
        val errors = harness.box.messages
        assertEquals(2, errors.size)
        for (error in errors) {
            assertEquals(JsonNull, error["id"])
            assertEquals(-32700, error.errorCode)
            assertEquals("parse_error", error.errorName)
        }
    }

    @Test
    fun invalidMessagesAreRejected() = runBlocking<Unit> {
        val harness = BridgeHarness()
        val cases = listOf(
            """{"id": 1, "method": "ping"}""" to j("1"), // missing jsonrpc
            """[{"jsonrpc": "2.0", "id": 2, "method": "ping"}]""" to JsonNull, // batch
            """{"jsonrpc": "2.0", "id": {"x": 1}, "method": "ping"}""" to JsonNull, // bad id
            """{"jsonrpc": "2.0", "id": 4, "method": 7}""" to j("4"), // bad method
            """{"jsonrpc": "2.0", "id": 5}""" to j("5"), // neither request nor response
            "42" to JsonNull,
        )
        for ((line, _) in cases) harness.engine.receive(line)
        harness.engine.flush()
        val errors = harness.box.messages.filter { it.errorCode == -32600 }
        assertEquals(cases.size, errors.size)
        assertEquals(cases.map { it.second }.toSet(), errors.map { it["id"] }.toSet())
        assertTrue(errors.all { it.errorName == "invalid_message" })
    }

    @Test
    fun blankLinesAreIgnored() = runBlocking<Unit> {
        val harness = BridgeHarness()
        harness.engine.receive("")
        harness.engine.receive("   \t")
        harness.engine.flush()
        assertTrue(harness.box.messages.isEmpty())
    }

    @Test
    fun unknownMethod() = runBlocking<Unit> {
        val harness = BridgeHarness()
        val response = harness.call("npc/dance", """{"style": "jig"}""")
        assertEquals(-32601, response.errorCode)
        assertEquals("method_not_found", response.errorName)
        assertEquals("npc/dance", response["error"]["data"]["method"].str)
    }

    @Test
    fun numericIdsAreEchoedExactly() = runBlocking<Unit> {
        val harness = BridgeHarness()
        val ids = listOf("7", "1000000000000000", "9007199254740991", "-9007199254740991", "4503599627370497")
        for (id in ids) harness.engine.receive("""{"jsonrpc":"2.0","id":$id,"method":"ping"}""")
        harness.engine.receive("""{"jsonrpc":"2.0","id":8.0,"method":"ping"}""")
        harness.result("ping")
        harness.engine.flush()
        val lines = harness.box.lines
        for (id in ids + "8") assertTrue("""{"jsonrpc":"2.0","id":$id,"result":{}}""" in lines, "id $id: $lines")
    }

    @Test
    fun numericIdsThatCannotRoundTripAreRejected() = runBlocking<Unit> {
        val harness = BridgeHarness()
        // 2^53 + 1 would come back as 2^53; 1e999 is infinite.
        for (id in listOf("9007199254740993", "9007199254740992", "1e999", "-1e300")) {
            harness.engine.receive("""{"jsonrpc":"2.0","id":$id,"method":"ping"}""")
        }
        harness.engine.flush()
        val errors = harness.box.messages
        assertEquals(4, errors.size)
        assertTrue(errors.all { it.errorCode == -32600 && it["id"] == JsonNull })
        assertTrue(errors.first().errorMessage!!.contains("string id"))
    }

    @Test
    fun notificationsGetNoResponse() = runBlocking<Unit> {
        val harness = BridgeHarness()
        harness.notify("ping")
        harness.notify("does/not/exist")
        harness.notify("session/respond", j("""{"session": "missing", "prompt": "hi"}"""))
        // A request after the notifications is answered; nothing else is.
        harness.result("ping")
        harness.engine.flush()
        assertEquals(1, harness.box.messages.size)
    }

    @Test
    fun invalidParams() = runBlocking<Unit> {
        val harness = BridgeHarness()
        val id = harness.createSession()
        val missingPrompt = harness.call("session/respond", """{"session": "$id"}""")
        assertEquals(-32602, missingPrompt.errorCode)
        assertEquals("invalid_params", missingPrompt.errorName)
        assertTrue(missingPrompt.errorMessage!!.contains("'prompt'"))

        assertEquals(-32602, harness.call("session/respond", """{"session": "$id", "prompt": 5}""").errorCode)
        assertEquals(-32602, harness.call("session/list", "[1, 2]").errorCode)
        assertEquals(-32602, harness.call("session/create", """{"options": {"toolChoice": "sometimes"}}""").errorCode)
        assertEquals(-32602, harness.call("session/create", """{"options": {"toolChoice": {"tool": "ghost"}}}""").errorCode)

        val badStep = harness.call("session/create", """{"model": {"type": "scripted", "steps": [{"dance": true}]}}""")
        assertEquals(-32602, badStep.errorCode)
        assertTrue(badStep.errorMessage!!.contains("model.steps[0]"))
        val badCode = harness.call("session/create", """{"model": {"type": "scripted", "steps": [{"error": "oops"}]}}""")
        assertTrue(badCode.errorMessage!!.contains("guardrail_violation"))

        assertEquals(-32602, harness.call("session/create", """{"model": {"type": "quantum"}}""").errorCode)
        assertEquals(-32602, harness.call("session/create", """{"tools": [{"name": "open gate", "description": "x"}]}""").errorCode)
        assertEquals(-32602, harness.call("session/create", """{"tools": [{"name": "a", "description": "x", "execution": "server"}]}""").errorCode)
        assertEquals(-32602, harness.call("session/create", """{"tools": [{"name": "respond", "description": "x"}]}""").errorCode)
        assertEquals(-32602, harness.call("session/create", """{"session": "", "model": "scripted"}""").errorCode)
        assertEquals(-32602, harness.call("session/create", """{"options": {"userLabel": " "}, "model": "scripted"}""").errorCode)
    }

    @Test
    fun outOfRangeTimeoutsAreRejectedNotTrapped() = runBlocking<Unit> {
        val harness = BridgeHarness()
        val cases = listOf(
            "session/create" to """{"options": {"toolTimeoutSeconds": 1e19}}""",
            "session/create" to """{"options": {"toolTimeoutSeconds": -1}}""",
            "session/create" to """{"tools": [{"name": "a", "description": "x", "timeoutSeconds": 1e19}]}""",
            "tools/validate" to """{"tools": [{"name": "a", "description": "x", "timeoutSeconds": 1e300}]}""",
            "npc/create" to """{"persona": {"name": "Gorm"}, "options": {"toolTimeoutSeconds": 1e19}}""",
            "decision/decide" to """{"situation": "x", "options": ["a", "b"], "toolTimeoutSeconds": 1e19, "model": "scripted"}""",
            "content/generate" to
                """{"prompt": "x", "schema": {"type": "object", "properties": {"name": {"type": "string"}}}, "tools": [{"name": "a", "description": "x"}], "toolTimeoutSeconds": 1e19, "model": "scripted"}""",
        )
        for ((method, params) in cases) assertEquals(-32602, harness.call(method, params).errorCode, "$method $params")
        val session = harness.createSession()
        val setTools = harness.call("session/setTools", """{"session": "$session", "tools": [{"name": "a", "description": "x", "timeoutSeconds": 1e19}]}""")
        assertEquals(-32602, setTools.errorCode)

        // NaN can only come from in-process callers; it must not slip past the range checks.
        for (option in listOf("toolTimeoutSeconds", "temperature")) {
            val params = JsonObject(mapOf("options" to JsonObject(mapOf(option to JsonPrimitive(Double.NaN)))))
            assertFailsWith<BridgeError> { harness.engine.call("session/create", params) }
        }

        // The limits themselves are accepted, and the engine is still alive.
        harness.result("session/create", """{"options": {"toolTimeoutSeconds": 86400}, "model": "scripted"}""")
        harness.result("session/create", """{"options": {"toolTimeoutSeconds": 0}, "model": "scripted"}""")
        assertEquals(EmptyJsonObject, harness.result("ping"))
    }

    @Test
    fun schemaValidation() = runBlocking<Unit> {
        val harness = BridgeHarness()
        val result = harness.result(
            "schema/validate",
            """{"schema": {"type": "object", "properties": {"code": {"type": "string", "format": "uuid"}, "mood": {"type": "string", "enum": ["calm", "angry"]}}, "required": ["code", "mood"]}}""",
        )
        assertTrue(result["warnings"].strings().any { "format" in it }, "${result["warnings"]}")
        assertNotNull(result["generationSchema"]["properties"])
        assertTrue(result["rendered"].str!!.contains("calm"))

        val invalid = harness.call("schema/validate", """{"schema": {"type": "quaternion"}}""")
        assertEquals(-32007, invalid.errorCode)
        assertEquals("invalid_schema", invalid.errorName)
        assertEquals("schema", invalid["error"]["data"]["path"].str)
        assertNotNull(invalid["error"]["data"]["schemaPath"].str)
    }

    @Test
    fun toolValidation() = runBlocking<Unit> {
        val harness = BridgeHarness()
        val result = harness.result(
            "tools/validate",
            """{"tools": [
                {"name": "open_gate", "description": "Open a gate.", "parameters": {"type": "object", "properties": {"gate": {"type": "string", "format": "uuid"}}}},
                {"type": "function", "function": {"name": "wave", "parameters": {"type": "object", "properties": {}}}}
            ]}""",
        )
        assertEquals("open_gate", result["tools"][0]["name"].str)
        assertTrue(result["tools"][0]["warnings"].strings().any { "format" in it && !it.startsWith("open_gate:") })
        assertEquals("wave", result["tools"][1]["name"].str)
        assertTrue(result["warnings"].strings().any { it.startsWith("wave:") && "description" in it })

        assertEquals(-32602, harness.call("tools/validate", """{"tools": [{"name": "a"}, {"name": "a"}]}""").errorCode)
        val badSchema = harness.call("tools/validate", """{"tools": [{"name": "a", "parameters": {"type": "wat"}}]}""")
        assertEquals(-32007, badSchema.errorCode)
        assertEquals("tools[0].parameters", badSchema["error"]["data"]["path"].str)
    }

    @Test
    fun outgoingMessagesAreSingleLineJsonRpc() = runBlocking<Unit> {
        val harness = BridgeHarness()
        val id = harness.createSession(steps = """[{"text": "Line one\nLine two", "chunks": 2}]""")
        harness.result("session/respond", """{"session": "$id", "prompt": "Say two lines", "stream": true}""")
        harness.engine.flush()
        for (line in harness.box.lines) {
            assertTrue('\n' !in line)
            assertEquals("2.0", Json.parseToJsonElement(line)["jsonrpc"].str)
        }
    }

    @Test
    fun responsesToUnknownRequestsAreIgnored() = runBlocking<Unit> {
        val logs = LogCollector()
        val harness = BridgeHarness(BridgeConfiguration(modelAvailability = { BridgeHarness.TEST_AVAILABILITY }, logger = logs.logger))
        harness.engine.receive("""{"jsonrpc":"2.0","id":"t-99","result":{"output":"stray"}}""")
        harness.result("ping")
        assertEquals(1, harness.box.messages.size)
        assertTrue(logs.messages.any { "t-99" in it })
    }

    @Test
    fun inProcessCalls() = runBlocking<Unit> {
        val harness = BridgeHarness()
        assertEquals(EmptyJsonObject, harness.engine.call("ping"))
        val error = assertFailsWith<BridgeError> { harness.engine.call("nope/nope") }
        assertEquals(-32601, error.code)
        val created = harness.engine.call("session/create", j("""{"model": {"type": "scripted", "steps": [{"text": "hi there", "chunks": 2}]}}"""))
        val session = created["session"]!!
        val reply = harness.engine.call(
            "session/respond",
            JsonObject(mapOf("session" to session, "prompt" to JsonPrimitive("x"), "stream" to JsonPrimitive(true))),
            id = JsonRpcId.of("mine-1"),
        )
        assertEquals("hi there", reply["text"].str)
        harness.engine.flush()
        // Responses go to the caller; the events still go through `send`, tagged with the caller's id.
        assertTrue(harness.box.messages.isNotEmpty())
        assertTrue(harness.box.messages.all { it["params"]["requestId"].str == "mine-1" })
    }

    @Test
    fun scriptedModelsCanBeDisabled() = runBlocking<Unit> {
        val harness = BridgeHarness(BridgeConfiguration(modelAvailability = { BridgeHarness.TEST_AVAILABILITY }, allowsScriptedModels = false))
        assertEquals(-32602, harness.call("session/create", """{"model": {"type": "scripted", "steps": []}}""").errorCode)
        assertEquals(listOf("system"), harness.result("initialize")["capabilities"]["models"].strings())
    }

    @Test
    fun customModelTypesGoThroughTheFactory() = runBlocking<Unit> {
        val specs = java.util.Collections.synchronizedList(ArrayList<BridgeModelSpec>())
        val harness = BridgeHarness(
            BridgeConfiguration(
                modelAvailability = { BridgeHarness.TEST_AVAILABILITY },
                modelFactory = { spec ->
                    specs += spec
                    if (spec is BridgeModelSpec.Custom && spec.type == "echo") {
                        ScriptedLanguageModel(listOf(ScriptedLanguageModel.Step.Template("echo: {prompt}")), style = ScriptedLanguageModel.ScriptStyle.NATIVE)
                    } else {
                        BridgeConfiguration.defaultModelFactory(null)(spec)
                    }
                },
            ),
        )
        val session = harness.result("session/create", """{"model": {"type": "echo", "voice": "deep"}}""")["session"].str
        assertEquals("echo: hello", harness.result("session/respond", """{"session": "$session", "prompt": "hello"}""")["text"].str)
        assertEquals("echo", harness.result("session/list")["sessions"][0]["model"].str)
        assertEquals("deep", (specs.first() as BridgeModelSpec.Custom).options["voice"].str)
        assertEquals(-32602, harness.call("session/create", """{"model": "quantum"}""").errorCode)
    }

    @Test
    fun shutdownCancelsWorkAndRejectsLaterRequests() = runBlocking<Unit> {
        val fired = AtomicBoolean(false)
        val harness = BridgeHarness(BridgeConfiguration(modelAvailability = { BridgeHarness.TEST_AVAILABILITY }, onShutdown = { fired.set(true) }))
        val id = harness.createSession(steps = """[{"text": "late", "delayMs": 5000}]""")
        val respond = harness.send("session/respond", """{"session": "$id", "prompt": "wait"}""")
        delay(50)
        assertEquals(EmptyJsonObject, harness.result("shutdown"))
        val cancelled = harness.response(respond)
        assertEquals(-32009, cancelled.errorCode)
        // The cancelled request's error response came first.
        assertTrue(harness.responseIndex(respond)!! < harness.box.index { it["result"] == EmptyJsonObject }!!)
        val later = harness.call("ping")
        assertEquals(-32023, later.errorCode)
        assertEquals("shut_down", later.errorName)
        harness.engine.flush()
        assertTrue(fired.get())
        assertTrue(harness.engine.isShutDown)
        assertTrue(harness.engine.sessions.isEmpty())
    }

    @Test
    fun shutdownWaitIsBoundedEvenForUncancellableWork() = runBlocking<Unit> {
        val stuck = object : BridgeExtension {
            override fun register(registry: BridgeMethodRegistry, engine: BridgeEngine) {}

            override suspend fun shutdown() {
                withContext(NonCancellable) { delay(8_000) }
            }
        }
        val harness = BridgeHarness(BridgeConfiguration(modelAvailability = { BridgeHarness.TEST_AVAILABILITY }, extensions = listOf(stuck)))
        harness.engine.register("test/stuck") {
            BridgeReply.Deferred {
                withContext(NonCancellable) { delay(8_000) }
                EmptyJsonObject
            }
        }
        harness.send("test/stuck")
        harness.engine.flush()
        val started = TimeSource.Monotonic.markNow()
        harness.engine.shutdown()
        assertTrue(started.elapsedNow() < 5.seconds, "shutdown took ${started.elapsedNow()}")
        assertTrue(harness.engine.isShutDown)
    }

    @Test
    fun closeStopsDelivery() = runBlocking<Unit> {
        val harness = BridgeHarness()
        harness.result("ping")
        harness.engine.close()
        val count = harness.box.messages.size
        harness.send("ping")
        harness.engine.receive("not json")
        delay(50)
        assertEquals(count, harness.box.messages.size)
        assertFailsWith<BridgeError> { harness.engine.call("ping") }
        assertTrue(harness.engine.isShutDown)
    }

    @Test
    fun closeFromInsideTheSendCallbackDoesNotDeadlock() = runBlocking<Unit> {
        lateinit var engine: BridgeEngine
        val delivered = java.util.Collections.synchronizedList(ArrayList<String>())
        engine = BridgeEngine(BridgeConfiguration(modelAvailability = { BridgeHarness.TEST_AVAILABILITY })) { line ->
            delivered += line
            engine.close()
        }
        engine.receive("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")
        engine.receive("""{"jsonrpc":"2.0","id":2,"method":"ping"}""")
        delay(100)
        assertEquals(1, delivered.size)
    }

    @Test
    fun bridgeErrorsCarryTheirMessage() {
        val error: Throwable = BridgeError.invalidParams("Parameter 'x' is wrong.")
        assertEquals("Parameter 'x' is wrong.", error.message)
        assertNull((error as BridgeError).data?.get("missing"))
        assertEquals(j("""{"code": -32602, "message": "Parameter 'x' is wrong.", "data": {"code": "invalid_params"}}"""), error.toJson())
    }
}
