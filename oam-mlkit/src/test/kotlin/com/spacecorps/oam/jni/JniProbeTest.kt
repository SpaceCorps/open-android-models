package com.spacecorps.oam.jni

import android.content.ContextWrapper
import com.spacecorps.oam.bridge.BridgeConfiguration
import com.spacecorps.oam.bridge.BridgeEngine
import com.spacecorps.oam.testing.ScriptedLanguageModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The probe library's own export (see `src/test/native/probe_host.c`). */
private object ProbeHost {
    @JvmStatic
    external fun maxConcurrent(): Int
}

/**
 * [OamJni] through real JNI: a native library (`src/test/native/probe_host.c`)
 * implements `nativeDeliver` the way a naive C or Rust host does, reading each
 * line with `GetStringUTFChars` and writing it to a file.
 *
 * Opt-in, because it needs the library built for the test JVM's platform:
 * ```
 * clang -shared -fPIC -I$JAVA_HOME/include -I$JAVA_HOME/include/darwin \
 *   -o /tmp/libprobehost.dylib oam-mlkit/src/test/native/probe_host.c
 * OAM_JNI_PROBE_LIB=/tmp/libprobehost.dylib OAM_JNI_PROBE_OUT=/tmp/probe.txt \
 *   ./gradlew :oam-mlkit:testDebugUnitTest --tests '*JniProbeTest*'
 * ```
 */
@EnabledIfEnvironmentVariable(named = "OAM_JNI_PROBE_LIB", matches = ".+")
class JniProbeTest {
    private val out = File(System.getenv("OAM_JNI_PROBE_OUT") ?: "build/jni-probe.txt")
    private val original = OamJni.engineFactory
    private val reply = "Welcome, traveller! Café 🗡️🐉"

    init {
        System.load(checkNotNull(System.getenv("OAM_JNI_PROBE_LIB")))
    }

    @AfterEach
    fun tearDown() {
        OamJni.engineFactory = original
    }

    private fun model() = ScriptedLanguageModel(listOf(ScriptedLanguageModel.Step.Text(reply, chunks = 4)), style = ScriptedLanguageModel.ScriptStyle.NATIVE)

    /** Runs a streamed turn through create/send/destroy and returns the raw bytes the native side received. */
    private fun exchange(handle: Long): ByteArray {
        out.delete()
        val id = OamJni.create(ContextWrapper(null), handle)
        assertTrue(id > 0)
        OamJni.send(id, """{"jsonrpc":"2.0","id":1,"method":"session/create","params":{"session":"inn"}}""")
        OamJni.send(id, """{"jsonrpc":"2.0","id":2,"method":"session/respond","params":{"session":"inn","prompt":"Hello!","stream":true}}""")
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && !(out.exists() && out.readText().contains("\"id\":2,\"result\""))) Thread.sleep(5)
        OamJni.destroy(id)
        return out.readBytes()
    }

    private fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    @Test
    fun aNativeHostReceivesValidUtf8JsonRpcLines() {
        OamJni.engineFactory = BridgeEngineFactory(systemModel = ::model)
        val text = strictUtf8(exchange(0x5EED))
        val messages = text.lines().filter { it.isNotEmpty() }.map { line ->
            val (handle, json) = line.split('\t', limit = 2)
            assertEquals(0x5EED.toString(), handle)
            Json.parseToJsonElement(json).jsonObject
        }
        val response = messages.single { it["id"]?.toString() == "2" }
        assertEquals(reply, response["result"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        val events = messages.filter { it["method"]?.jsonPrimitive?.content == "session/event" }
        assertTrue(events.isNotEmpty() && messages.indexOf(events.last()) < messages.indexOf(response))
        // Deliveries never overlap: the host callback is not re-entered.
        assertEquals(1, ProbeHost.maxConcurrent())
        println("JNI probe: ${messages.size} messages, ${text.length} chars, strict UTF-8 OK, max concurrent deliveries ${ProbeHost.maxConcurrent()}")
    }

    @Test
    fun withoutEscapingEmojiReachTheHostAsModifiedUtf8() {
        // The same engine without JniText.escapeSupplementary: what a host would get from a raw engine.
        OamJni.engineFactory = MessageEngineFactory { _, output ->
            BridgeMessageEngine(BridgeEngine(BridgeConfiguration(systemModel = model())) { line -> output.deliver(line) })
        }
        val bytes = exchange(0xBAD)
        assertFailsWith<CharacterCodingException> { strictUtf8(bytes) }
        println("JNI probe: unescaped emoji are not valid UTF-8 for the host (${bytes.size} bytes)")
    }
}
