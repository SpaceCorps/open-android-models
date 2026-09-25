package com.spacecorps.oam.bridge

import com.spacecorps.oam.intValue
import com.spacecorps.oam.stringValue
import com.spacecorps.oam.toJsonString
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Parses JSON text written in a test. */
internal fun j(text: String): JsonElement = Json.parseToJsonElement(text)

/** Member access that tolerates missing values, like Swift's optional chaining. */
internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

internal operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)

internal val JsonElement?.str: String? get() = this?.stringValue

internal val JsonElement?.int: Int? get() = this?.intValue

internal val JsonElement?.keys: List<String>? get() = (this as? JsonObject)?.keys?.toList()

internal val JsonElement?.errorCode: Int? get() = this["error"]["code"].int

internal val JsonElement?.errorName: String? get() = this["error"]["data"]["code"].str

internal val JsonElement?.errorMessage: String? get() = this["error"]["message"].str

internal fun JsonElement?.strings(): List<String> = (this as? JsonArray)?.mapNotNull { it.stringValue }.orEmpty()

/**
 * Collects everything a [BridgeEngine] sends and lets tests wait for
 * specific messages. Optionally answers `tool/call` requests like a game engine.
 */
internal class MessageBox {
    /** Answers a `tool/call`: returns the `result` object, or `{"error": …}` for an error response, or `null` to not answer. */
    fun interface ToolResponder {
        fun respond(call: JsonElement?, params: JsonElement?): JsonElement?
    }

    private val lock = Any()
    private val lineList = ArrayList<String>()
    private val messageList = ArrayList<JsonElement>()

    @Volatile
    var responder: ToolResponder? = null

    @Volatile
    var engine: BridgeEngine? = null

    fun append(line: String) {
        val message = try {
            Json.parseToJsonElement(line)
        } catch (_: IllegalArgumentException) {
            JsonPrimitive("<unparseable> $line")
        }
        synchronized(lock) {
            lineList += line
            messageList += message
        }
        val responder = responder ?: return
        val engine = engine ?: return
        if (message["method"].str != "tool/call") return
        val id = message["id"] ?: return
        val reply = responder.respond(message["params"]["call"], message["params"]) ?: return
        val response = linkedMapOf<String, JsonElement>("jsonrpc" to JsonPrimitive("2.0"), "id" to id)
        val error = reply["error"]
        if (error != null && (reply as JsonObject).size == 1) response["error"] = error else response["result"] = reply
        engine.receive(JsonObject(response).toJsonString())
    }

    val lines: List<String> get() = synchronized(lock) { lineList.toList() }

    val messages: List<JsonElement> get() = synchronized(lock) { messageList.toList() }

    /** Waits until a message matching [predicate] has arrived and returns it. */
    suspend fun wait(timeout: Duration = 5.seconds, description: String = "message", predicate: (JsonElement) -> Boolean): JsonElement {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (deadline.hasNotPassedNow()) {
            messages.firstOrNull(predicate)?.let { return it }
            delay(2)
        }
        fail("Timed out waiting for $description. Received:\n" + lines.joinToString("\n"))
    }

    fun index(predicate: (JsonElement) -> Boolean): Int? = messages.indexOfFirst(predicate).takeIf { it >= 0 }

    fun lastIndex(predicate: (JsonElement) -> Boolean): Int? = messages.indexOfLast(predicate).takeIf { it >= 0 }
}

/** A bridge engine plus a fake peer. */
internal class BridgeHarness(configuration: BridgeConfiguration = BridgeConfiguration(modelAvailability = { TEST_AVAILABILITY })) {
    val box = MessageBox()
    val engine = BridgeEngine(configuration) { line -> box.append(line) }
    private var counter = 0

    init {
        box.engine = engine
    }

    fun nextId(): String = synchronized(this) { "r${++counter}" }

    /** Sends a request without waiting; returns its id. */
    fun send(method: String, params: JsonElement? = null, id: String? = null): String {
        val requestId = id ?: nextId()
        val message = linkedMapOf<String, JsonElement>("jsonrpc" to JsonPrimitive("2.0"), "id" to JsonPrimitive(requestId), "method" to JsonPrimitive(method))
        if (params != null) message["params"] = params
        engine.receive(JsonObject(message).toJsonString())
        return requestId
    }

    fun send(method: String, params: String, id: String? = null): String = send(method, j(params), id)

    fun notify(method: String, params: JsonElement? = null) {
        val message = linkedMapOf<String, JsonElement>("jsonrpc" to JsonPrimitive("2.0"), "method" to JsonPrimitive(method))
        if (params != null) message["params"] = params
        engine.receive(JsonObject(message).toJsonString())
    }

    /** Waits for the response (the full message) to request [id]. */
    suspend fun response(id: String, timeout: Duration = 5.seconds): JsonElement =
        box.wait(timeout, "response to $id") { it["id"].str == id && it["method"] == null }

    /** Sends a request and waits for its full response message. */
    suspend fun call(method: String, params: JsonElement? = null, timeout: Duration = 5.seconds): JsonElement =
        response(send(method, params), timeout)

    suspend fun call(method: String, params: String): JsonElement = call(method, j(params))

    /** Sends a request and returns its result, failing the test on an error response. */
    suspend fun result(method: String, params: JsonElement? = null): JsonElement {
        val response = call(method, params)
        response["error"]?.let { fail("$method failed: $it") }
        return response["result"] ?: JsonNull
    }

    suspend fun result(method: String, params: String): JsonElement = result(method, j(params))

    /** Creates a scripted session and returns its id. */
    suspend fun createSession(
        id: String? = null,
        steps: String = "[]",
        tools: String? = null,
        options: String? = null,
        instructions: String? = null,
    ): String {
        val params = linkedMapOf<String, JsonElement>("model" to JsonObject(mapOf("type" to JsonPrimitive("scripted"), "steps" to j(steps))))
        if (id != null) params["session"] = JsonPrimitive(id)
        if (tools != null) params["tools"] = j(tools)
        if (options != null) params["options"] = j(options)
        if (instructions != null) params["instructions"] = JsonPrimitive(instructions)
        return result("session/create", JsonObject(params))["session"].str ?: fail("no session id")
    }

    /** Creates a scripted NPC and returns the `npc/create` result. */
    suspend fun createNpc(
        id: String? = "gorm",
        steps: String = "[]",
        persona: String = GORM,
        tools: String? = null,
        options: String? = null,
        world: String? = null,
    ): JsonElement {
        val params = linkedMapOf("persona" to j(persona), "model" to JsonObject(mapOf("type" to JsonPrimitive("scripted"), "steps" to j(steps))))
        if (id != null) params["npc"] = JsonPrimitive(id)
        if (tools != null) params["tools"] = j(tools)
        if (options != null) params["options"] = j(options)
        if (world != null) params["world"] = JsonPrimitive(world)
        return result("npc/create", JsonObject(params))
    }

    fun notifications(method: String, requestId: String): List<JsonElement> =
        box.messages.filter { it["method"].str == method && it["params"]["requestId"].str == requestId }

    fun events(method: String, requestId: String): List<JsonElement> = notifications(method, requestId).mapNotNull { it["params"]["event"] }

    fun responseIndex(id: String): Int? = box.index { it["id"].str == id && it["method"] == null }

    /** Answers the `tool/call` message [call] with `{"text": text}` from another thread after [micros] µs. */
    fun answerLater(call: JsonElement, micros: Long, text: String = "ok"): Thread {
        val answer = """{"jsonrpc": "2.0", "id": ${call["id"]}, "result": {"text": ${JsonPrimitive(text)}}}"""
        return Thread {
            spinMicros(micros)
            engine.receive(answer)
        }.also { it.start() }
    }

    companion object {
        val TEST_AVAILABILITY = BridgeModelAvailability(available = true, contextSize = 4096, variant = "Scripted", supportedLanguages = listOf("en"))

        const val GORM = """{"name": "Gorm", "role": "the village blacksmith", "personality": "Gruff but fair.", "speakingStyle": "Short, blunt sentences."}"""

        const val OPEN_GATE =
            """{"name": "open_gate", "description": "Ask the game to open a gate.", "parameters": {"type": "object", "properties": {"gate": {"type": "string"}}, "required": ["gate"]}}"""

        const val CHECK_INVENTORY =
            """{"name": "check_inventory", "description": "Look up stock and price of an item.", "parameters": {"type": "object", "properties": {"item": {"type": "string"}}, "required": ["item"]}}"""

        /** A structured NPC reply step. */
        fun reply(line: String, emotion: String = "neutral", options: List<String> = listOf("Buy one.", "Goodbye."), ends: Boolean = false): String {
            val quotedOptions = options.joinToString(", ") { JsonPrimitive(it).toString() }
            return """{"json": {"emotion": "$emotion", "line": ${JsonPrimitive(line)}, "player_options": [$quotedOptions], "ends_conversation": $ends}}"""
        }
    }
}

/** Busy-waits [micros] µs (finer than `delay`, for racing two events). */
internal fun spinMicros(micros: Long) {
    val end = System.nanoTime() + micros * 1000
    while (System.nanoTime() < end) Thread.onSpinWait()
}

/** Collects log messages. */
internal class LogCollector {
    private val entries = java.util.Collections.synchronizedList(ArrayList<String>())

    val messages: List<String> get() = synchronized(entries) { entries.toList() }

    val logger: (BridgeLogLevel, String) -> Unit = { level, message -> entries += "${level.name.lowercase()}: $message" }
}
