package com.spacecorps.oam

import com.spacecorps.oam.openai.OpenAICompatibleModel
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class OpenAICompatibleModelTest {
    private lateinit var server: HttpServer
    private val bodies = CopyOnWriteArrayList<JsonObject>()
    private val headers = CopyOnWriteArrayList<Map<String, List<String>>>()

    @Volatile
    private var handler: (HttpExchange) -> Unit = { respond(it, 500, "{}") }

    private val baseUrl get() = "http://127.0.0.1:${server.address.port}/v1"

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/") { exchange ->
            if (exchange.requestMethod == "POST") {
                bodies += OamJson.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()) as JsonObject
            }
            headers += exchange.requestHeaders.toMap()
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String, extraHeaders: Map<String, String> = emptyMap()) {
        extraHeaders.forEach { (key, value) -> exchange.responseHeaders.add(key, value) }
        val bytes = body.encodeToByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun sse(exchange: HttpExchange, events: List<String>, pause: CountDownLatch? = null) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0)
        val out = exchange.responseBody
        for ((index, event) in events.withIndex()) {
            out.write("data: $event\n\n".encodeToByteArray())
            out.flush()
            if (index == 1) pause?.await(5, TimeUnit.SECONDS)
        }
    }

    private fun delta(text: String) = """{"choices":[{"index":0,"delta":{"content":${jsonOf(text).toJsonString()}},"finish_reason":null}]}"""

    @Test
    fun streamsChunksAndUsage() = runBlocking {
        handler = { exchange ->
            sse(
                exchange,
                listOf(
                    """{"choices":[{"index":0,"delta":{"role":"assistant"}}]}""",
                    delta("Hello"),
                    delta(", traveler!"),
                    """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                    """{"choices":[],"usage":{"prompt_tokens":42,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":40}}}""",
                    "[DONE]",
                ),
            )
        }
        val model = OpenAICompatibleModel(baseUrl, model = "system", apiKey = "secret")
        val chunks = model.generate(
            GenerationRequest(prompt = "Hi", systemInstruction = "You are Mira.", promptPrefix = "Tools: none. ", temperature = 0.0, seed = 7, maxOutputTokens = 64),
        ).toList()
        assertEquals(listOf("Hello", ", traveler!", ""), chunks.map { it.delta })
        val last = chunks.last()
        assertTrue(last.isFinal)
        assertEquals("Hello, traveler!", last.text)
        assertEquals(TokenUsage(42, 40, 5), last.usage)
        assertEquals(FinishReason.STOP, last.finishReason)

        val body = bodies.single()
        assertEquals(
            """{"model":"system","messages":[{"role":"system","content":"You are Mira."},{"role":"user","content":"Tools: none. Hi"}],""" +
                """"stream":true,"stream_options":{"include_usage":true},"temperature":0.0,"max_tokens":64,"seed":7}""",
            body.toJsonString(),
        )
        assertEquals(listOf("Bearer secret"), headers.single()["Authorization"])
    }

    @Test
    fun nonStreamingMode() = runBlocking {
        handler = { exchange ->
            respond(exchange, 200, """{"choices":[{"message":{"role":"assistant","content":"{\"action\":\"respond\"}"},"finish_reason":"length"}],"usage":{"prompt_tokens":10,"completion_tokens":3}}""")
        }
        val model = OpenAICompatibleModel(baseUrl, streaming = false, extraBody = jsonObjectOf("top_p" to 0.9))
        val chunk = model.generate(GenerationRequest("x")).toList().single()
        assertEquals("""{"action":"respond"}""", chunk.text)
        assertEquals(FinishReason.MAX_TOKENS, chunk.finishReason)
        assertEquals(TokenUsage(10, 0, 3), chunk.usage)
        val body = bodies.single()
        assertEquals(false, body["stream"]!!.boolValue)
        assertNull(body["stream_options"])
        assertEquals(0.9, body["top_p"]!!.doubleValue)
        assertNull(body["temperature"])
    }

    @Test
    fun errorsMapToAgentErrorCodes() = runBlocking {
        suspend fun errorFor(status: Int, body: String, extra: Map<String, String> = emptyMap()): AgentError {
            handler = { respond(it, status, body, extra) }
            return assertFailsWith<AgentError> { OpenAICompatibleModel(baseUrl).generate(GenerationRequest("x")).toList() }
        }
        fun envelope(code: String?, type: String = "invalid_request_error") =
            """{"error":{"message":"m-$code","type":"$type","param":null,"code":${code?.let { "\"$it\"" } ?: "null"}}}"""

        val limited = errorFor(429, envelope("rate_limited", "rate_limit_error"), mapOf("Retry-After" to "2"))
        assertEquals(AgentErrorCode.RATE_LIMITED, limited.code)
        assertEquals(2.seconds, limited.retryAfter)
        assertEquals("m-rate_limited", limited.message)
        assertEquals(AgentErrorCode.GUARDRAIL_VIOLATION, errorFor(400, envelope("content_filter")).code)
        assertEquals(AgentErrorCode.CONTEXT_SIZE_EXCEEDED, errorFor(400, envelope("context_length_exceeded")).code)
        assertEquals(AgentErrorCode.REFUSAL, errorFor(400, envelope("refusal")).code)
        assertEquals(AgentErrorCode.BUSY, errorFor(503, envelope("busy", "server_error")).code)
        assertEquals(AgentErrorCode.MODEL_UNAVAILABLE, errorFor(503, envelope("model_unavailable", "server_error")).code)
        assertEquals(AgentErrorCode.INVALID_REQUEST, errorFor(400, envelope(null)).code)
        assertEquals(AgentErrorCode.GENERATION_FAILED, errorFor(500, "oops").code)
        assertEquals(AgentErrorCode.MODEL_UNAVAILABLE, errorFor(401, "no").code)
    }

    @Test
    fun errorsInsideTheStreamAreReported() = runBlocking {
        handler = { sse(it, listOf(delta("Hi"), """{"error":{"message":"blocked","code":"content_filter"}}""")) }
        val error = assertFailsWith<AgentError> { OpenAICompatibleModel(baseUrl).generate(GenerationRequest("x")).toList() }
        assertEquals(AgentErrorCode.GUARDRAIL_VIOLATION, error.code)
    }

    @Test
    fun stoppingEarlyCancelsQuietly() = runBlocking {
        val gate = CountDownLatch(1)
        handler = { exchange ->
            try {
                sse(exchange, listOf(delta("{\"action\":"), delta("\"respond\"}"), delta(" and a long explanation"), "[DONE]"), pause = gate)
            } catch (_: java.io.IOException) {
                // The client went away.
            }
        }
        val model = OpenAICompatibleModel(baseUrl)
        val first = withTimeout(5.seconds) { model.generate(GenerationRequest("x")).first { it.text.endsWith("}") } }
        assertEquals("{\"action\":\"respond\"}", first.text)
        gate.countDown()
    }

    @Test
    fun drivesTheAgentLoopOverHttp() = runBlocking {
        val replies = ArrayDeque(listOf("""{"action": "check_menu", "arguments": {}}""", """{"action": "respond"}""", "Ale is 3 gold."))
        handler = { exchange -> synchronized(replies) { sse(exchange, listOf(delta(replies.removeFirst()), "[DONE]")) } }
        val agent = Agent(OpenAICompatibleModel(baseUrl), tools = listOf(Tavern.menu()))
        val response = withTimeout(10.seconds) { agent.respond("Ale?") }
        assertEquals("Ale is 3 gold.", response.text)
        assertEquals(listOf("check_menu"), response.toolCalls.map { it.call.name })
        assertTrue(response.usage.inputTokens > 0, "estimated when the server reports none")
        agent.close()
    }

    @Test
    fun availability() = runBlocking {
        handler = { respond(it, 200, """{"object":"list","data":[{"id":"system"}]}""") }
        assertIs<ModelAvailability.Available>(OpenAICompatibleModel(baseUrl).availability())
        handler = { respond(it, 404, "missing") }
        assertEquals("http_404", (OpenAICompatibleModel(baseUrl).availability() as ModelAvailability.Unavailable).reason)
        val port = server.address.port
        server.stop(0)
        val unreachable = OpenAICompatibleModel("http://127.0.0.1:$port/v1")
        assertEquals("unreachable", (unreachable.availability() as ModelAvailability.Unavailable).reason)
        assertEquals(
            AgentErrorCode.MODEL_UNAVAILABLE,
            assertFailsWith<AgentError> { unreachable.generate(GenerationRequest("x")).toList() }.code,
        )
        assertNull(unreachable.countTokens("anything"))
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
    }
}
