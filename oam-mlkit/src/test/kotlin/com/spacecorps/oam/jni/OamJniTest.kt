package com.spacecorps.oam.jni

import android.content.Context
import android.content.ContextWrapper
import kotlinx.serialization.json.Json
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
    fun theUnwiredDefaultAnswersRequestsWithAnError() {
        OamJni.engineFactory = originalFactory
        val id = OamJni.create(context, 42)
        OamJni.send(id, """{"jsonrpc":"2.0","method":"session/cancel","params":{}}""") // notification: no reply
        OamJni.send(id, """{"jsonrpc":"2.0","id":"a-1","method":"initialize","params":{}}""")
        OamJni.send(id, "not json")
        awaitDelivered(2)
        Thread.sleep(50)
        assertEquals(2, delivered.size)

        val (handle, reply) = delivered[0]
        assertEquals(42L, handle)
        val json = Json.parseToJsonElement(reply).jsonObject
        assertEquals("2.0", json["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals("a-1", json["id"]!!.jsonPrimitive.content)
        assertEquals(UnwiredMessageEngine.INTERNAL_ERROR, json["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertTrue(json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains("OamJni.engineFactory"))

        val parse = Json.parseToJsonElement(delivered[1].second).jsonObject
        assertEquals(UnwiredMessageEngine.PARSE_ERROR, parse["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals("null", parse["id"].toString())
    }
}
