package com.spacecorps.oam.bridge

import com.spacecorps.oam.Agent
import com.spacecorps.oam.EmptyJsonObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * An example extension, shaped like the NPC methods a game layer adds: its
 * own object store, its own event method, the shared turn driver.
 */
private class EchoExtension : BridgeExtension {
    private val agents = ConcurrentHashMap<String, Agent>()
    val shutDown = AtomicBoolean(false)

    override fun register(registry: BridgeMethodRegistry, engine: BridgeEngine) {
        registry.register("echo/create") { request ->
            val id = request.params.string("npc")
            val spec = BridgeCoding.modelSpec(request.params["model"])
            val tools = request.params["tools"]?.let { BridgeCoding.tools(it, defaultTimeout = 5.seconds).tools }.orEmpty()
            agents[id] = Agent(request.engine.makeModel(spec), tools = tools)
            BridgeReply.Result(JsonObject(mapOf("npc" to JsonPrimitive(id))))
        }
        registry.register("echo/say") { request ->
            val id = request.params.string("npc")
            val agent = agents[id] ?: throw BridgeError.named(-32050, "npc_not_found", "No NPC '$id'.")
            val run = agent.run(request.params.string("text")) // queued in order, before the next message is handled
            BridgeReply.Deferred {
                val response = request.drive(run, stream = true, context = JsonObject(mapOf("npc" to JsonPrimitive(id))), eventMethod = "echo/event")
                JsonObject(mapOf("npc" to JsonPrimitive(id)) + BridgeCoding.json(response))
            }
        }
        // Extensions may replace built-ins.
        registry.register("ping") { BridgeReply.Result(JsonObject(mapOf("pong" to JsonPrimitive(true)))) }
    }

    override val notificationMethods: List<String> get() = listOf("echo/event")

    override suspend fun shutdown() {
        shutDown.set(true)
    }
}

/** Extensions (open-apple-models' ExtensionTests). */
@Timeout(60)
class ExtensionTest {
    @Test
    fun extensionMethodsUseTheSharedDriver() = runBlocking<Unit> {
        val echo = EchoExtension()
        val harness = BridgeHarness(BridgeConfiguration(modelAvailability = { BridgeHarness.TEST_AVAILABILITY }, extensions = listOf(echo)))
        harness.box.responder = MessageBox.ToolResponder { _, params ->
            assertEquals("gorm", params["npc"].str)
            j("""{"output": "3 swords"}""")
        }
        harness.result(
            "echo/create",
            """{"npc": "gorm", "model": {"type": "scripted", "steps": [{"toolCalls": [{"name": "stock", "arguments": {}}]}, {"template": "I have {toolOutput}."}]},
                "tools": [{"name": "stock", "description": "Check stock."}]}""",
        )
        val request = harness.send("echo/say", """{"npc": "gorm", "text": "Swords?"}""")
        val result = harness.response(request)["result"]
        assertEquals("gorm", result["npc"].str)
        assertEquals("I have 3 swords.", result["text"].str)
        val events = harness.box.messages.filter { it["method"].str == "echo/event" }
        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it["params"]["npc"].str == "gorm" && it["params"]["requestId"].str == request })

        val initialize = harness.result("initialize")
        assertTrue("echo/say" in initialize["capabilities"]["methods"].strings())
        assertEquals(listOf("session/event", "tool/cancel", "echo/event"), initialize["capabilities"]["notifications"].strings())
        assertEquals(j("""{"pong": true}"""), harness.result("ping"))

        val custom = harness.call("echo/say", """{"npc": "nobody", "text": "hi"}""")
        assertEquals(-32050, custom.errorCode)
        assertEquals("npc_not_found", custom.errorName)

        harness.result("shutdown")
        assertTrue(echo.shutDown.get())
    }

    @Test
    fun runtimeRegistration() = runBlocking<Unit> {
        val harness = BridgeHarness()
        harness.engine.register("world/time") { request ->
            BridgeReply.Result(JsonObject(mapOf("hour" to JsonPrimitive((request.params.optionalInt("offset") ?: 0) + 12))))
        }
        assertEquals(j("""{"hour": 12}"""), harness.result("world/time"))
        assertEquals(j("""{"hour": 15}"""), harness.result("world/time", """{"offset": 3}"""))
        assertTrue("world/time" in harness.engine.methods)
    }

    @Test
    fun handlersMayThrowAnyError() = runBlocking<Unit> {
        val harness = BridgeHarness()
        harness.engine.register("test/agent") { throw com.spacecorps.oam.AgentError(com.spacecorps.oam.AgentErrorCode.REFUSAL, "No.") }
        harness.engine.register("test/bug") { throw IllegalStateException("oops") }
        harness.engine.register("test/deferred") { BridgeReply.Deferred { throw com.spacecorps.oam.AgentError(com.spacecorps.oam.AgentErrorCode.BUSY, "Busy.") } }
        assertEquals("refusal", harness.call("test/agent").errorName)
        assertEquals(-32003, harness.call("test/agent").errorCode)
        assertEquals("generation_failed", harness.call("test/bug").errorName)
        assertEquals(-32010, harness.call("test/deferred").errorCode)
        assertEquals(EmptyJsonObject, harness.result("ping"))
    }
}
