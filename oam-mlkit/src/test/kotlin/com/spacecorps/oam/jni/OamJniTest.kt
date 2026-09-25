package com.spacecorps.oam.jni

import android.content.Context
import android.content.ContextWrapper
import com.spacecorps.oam.ModelCapabilities
import com.spacecorps.oam.jsonObjectOf
import com.spacecorps.oam.testing.ScriptedLanguageModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [OamJni] on the JVM: the native library is absent, so deliveries go to an
 * injected sink instead of `nativeDeliver`.
 */
class OamJniTest {
    private val delivered = CopyOnWriteArrayList<Pair<Long, String>>()
    private lateinit var originalFactory: MessageEngineFactory
    private lateinit var originalSink: (Long, String) -> Unit
    private val context: Context = ContextWrapper(null)

    @BeforeEach
    fun setUp() {
        originalFactory = OamJni.engineFactory
        originalSink = OamJni.nativeSink
        OamJni.nativeSink = { handle, line -> delivered += handle to line }
    }

    @AfterEach
    fun tearDown() {
        OamJni.host.close()
        OamJni.engineFactory = originalFactory
        OamJni.nativeSink = originalSink
    }

    private fun awaitDelivered(count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (delivered.size < count && System.currentTimeMillis() < deadline) Thread.sleep(2)
        assertTrue(delivered.size >= count, "expected $count deliveries, got $delivered")
    }

    @Test
    fun createSendDestroyWithAConfiguredFactory() {
        var factoryContext: Context? = null
        var engine: EchoEngine? = null
        OamJni.engineFactory = MessageEngineFactory { ctx, output ->
            factoryContext = ctx
            EchoEngine(output).also { engine = it }
        }
        val id = OamJni.create(context, 0x7f00L)
        assertTrue(id > 0)
        // The stub context has no application context, so the context itself is used.
        assertNotNull(factoryContext)

        OamJni.send(id, """{"jsonrpc":"2.0","id":1,"method":"ping"}""")
        awaitDelivered(1)
        assertEquals(0x7f00L to """echo:{"jsonrpc":"2.0","id":1,"method":"ping"}""", delivered.single())

        OamJni.destroy(id)
        assertTrue(engine!!.closed.get())
        OamJni.send(id, "dropped")
        OamJni.destroy(id) // unknown ids are ignored
        Thread.sleep(50)
        assertEquals(1, delivered.size)
    }

    @Test
    fun aFailingFactoryYieldsBridgeZero() {
        OamJni.engineFactory = MessageEngineFactory { _, _ -> throw IllegalStateException("no model") }
        assertEquals(0L, OamJni.create(context, 1))
    }

    @Test
    fun theDefaultFactoryRunsTheBridgeEngine() {
        assertTrue(originalFactory is BridgeEngineFactory)
    }

    @Test
    fun aNativeHostExchangesProtocolMessages() {
        OamJni.engineFactory = BridgeEngineFactory(
            systemModel = {
                ScriptedLanguageModel(
                    listOf(
                        ScriptedLanguageModel.Step.ToolCalls(ScriptedLanguageModel.Step.ScriptedCall("open_gate", jsonObjectOf("gate" to "north"))),
                        ScriptedLanguageModel.Step.Template("Gate says: {toolOutput}"),
                    ),
                    capabilities = ModelCapabilities(modelName = "nano-test"),
                    style = ScriptedLanguageModel.ScriptStyle.NATIVE,
                )
            },
        )
        val id = OamJni.create(context, 42)
        assertTrue(id > 0)
        OamJni.send(id, """{"jsonrpc":"2.0","method":"session/cancel","params":{"session":"nobody"}}""") // a notification: no reply
        OamJni.send(id, "not json")
        OamJni.send(id, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1.0"}}""")
        OamJni.send(
            id,
            """{"jsonrpc":"2.0","id":2,"method":"session/create","params":{"session":"guard","tools":[{"name":"open_gate","description":"Open a gate.",""" +
                """"parameters":{"type":"object","properties":{"gate":{"type":"string"}},"required":["gate"]}}],"options":{"toolChoice":"required"}}}""",
        )
        OamJni.send(id, """{"jsonrpc":"2.0","id":3,"method":"session/respond","params":{"session":"guard","prompt":"Open the north gate.","stream":true}}""")

        val toolCall = awaitMessage { it["method"]?.jsonPrimitive?.content == "tool/call" }
        assertEquals("open_gate", toolCall["params"]!!.jsonObject["call"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val callId = toolCall["id"]!!.jsonPrimitive.content
        OamJni.send(id, """{"jsonrpc":"2.0","id":"$callId","result":{"output":{"opened":true}}}""")
        val response = awaitMessage { it["id"]?.toString() == "3" && "method" !in it }
        assertEquals("Gate says: {\"opened\":true}", response["result"]!!.jsonObject["text"]!!.jsonPrimitive.content)

        val messages = delivered.map { (handle, line) ->
            assertEquals(42L, handle)
            assertTrue('\n' !in line)
            Json.parseToJsonElement(line).jsonObject
        }
        assertEquals(-32700, messages.first()["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        val initialize = messages.first { it["id"]?.toString() == "1" }["result"]!!.jsonObject
        assertEquals("open-android-models", initialize["server"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("nano-test", initialize["model"]!!.jsonObject["variant"]!!.jsonPrimitive.content)
        // Every event and the tool call came before the turn's response.
        val responseIndex = messages.indexOfFirst { it["id"]?.toString() == "3" && "method" !in it }
        val related = messages.indices.filter { messages[it]["params"]?.jsonObject?.get("requestId")?.toString() == "3" }
        assertTrue(related.size >= 3 && related.all { it < responseIndex }, "$related before $responseIndex")
        assertTrue(messages.none { it["id"]?.toString() == "null" && it["error"]?.jsonObject?.get("code")?.jsonPrimitive?.int == -32600 })
        OamJni.destroy(id)
    }

    @Test
    fun aSystemModelThatCannotBeCreatedIsReportedUnavailable() {
        OamJni.engineFactory = BridgeEngineFactory(systemModel = { throw IllegalStateException("AICore is missing") })
        val id = OamJni.create(context, 7)
        assertTrue(id > 0)
        OamJni.send(id, """{"jsonrpc":"2.0","id":1,"method":"model/availability"}""")
        OamJni.send(id, """{"jsonrpc":"2.0","id":2,"method":"session/create","params":{"session":"a"}}""")
        OamJni.send(id, """{"jsonrpc":"2.0","id":3,"method":"session/respond","params":{"session":"a","prompt":"hi"}}""")
        OamJni.send(id, """{"jsonrpc":"2.0","id":4,"method":"session/create","params":{"session":"b","model":{"type":"scripted","steps":[{"text":"Still here."}]}}}""")
        OamJni.send(id, """{"jsonrpc":"2.0","id":5,"method":"session/respond","params":{"session":"b","prompt":"hi"}}""")
        val availability = awaitMessage { it["id"]?.toString() == "1" }["result"]!!.jsonObject
        assertEquals("false", availability["available"].toString())
        assertTrue(availability["detail"]!!.jsonPrimitive.content.contains("AICore is missing"))
        val warnings = awaitMessage { it["id"]?.toString() == "2" }["result"]!!.jsonObject["warnings"].toString()
        assertTrue("model_unavailable" in warnings, warnings)
        val failed = awaitMessage { it["id"]?.toString() == "3" }["error"]!!.jsonObject
        assertEquals(-32001, failed["code"]!!.jsonPrimitive.int)
        assertEquals("Still here.", awaitMessage { it["id"]?.toString() == "5" }["result"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        OamJni.destroy(id)
    }

    @Test
    fun destroyCancelsRunningTurnsAndStopsDelivery() {
        OamJni.engineFactory = BridgeEngineFactory(systemModel = { ScriptedLanguageModel() })
        val id = OamJni.create(context, 9)
        OamJni.send(id, """{"jsonrpc":"2.0","id":1,"method":"session/create","params":{"model":{"type":"scripted","steps":[{"text":"late","delayMs":2000}]}}}""")
        awaitMessage { it["id"]?.toString() == "1" }
        OamJni.send(id, """{"jsonrpc":"2.0","id":2,"method":"session/respond","params":{"session":"s1","prompt":"wait"}}""")
        Thread.sleep(50)
        OamJni.destroy(id)
        val count = delivered.size
        Thread.sleep(200)
        assertEquals(count, delivered.size)
    }

    @Test
    fun supplementaryCharactersAreEscapedForJni() {
        val line = """{"jsonrpc":"2.0","method":"npc/event","params":{"event":{"type":"lineDelta","delta":"Café 🗡️🐉"}}}"""
        val escaped = JniText.escapeSupplementary(line)
        assertTrue(escaped.none(Char::isSurrogate))
        assertTrue("\\uD83D\\uDDE1" in escaped)
        assertTrue("Café" in escaped)
        assertEquals(Json.parseToJsonElement(line), Json.parseToJsonElement(escaped))
        assertEquals("plain ASCII and é", JniText.escapeSupplementary("plain ASCII and é"))
    }

    private fun awaitMessage(predicate: (JsonObject) -> Boolean): JsonObject {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            delivered.toList().map { Json.parseToJsonElement(it.second).jsonObject }.firstOrNull(predicate)?.let { return it }
            Thread.sleep(2)
        }
        throw AssertionError("No matching message in ${delivered.map { it.second }}")
    }
}
