package com.spacecorps.oam.openai

import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentErrorCode
import com.spacecorps.oam.FinishReason
import com.spacecorps.oam.GenerationChunk
import com.spacecorps.oam.GenerationRequest
import com.spacecorps.oam.LanguageModel
import com.spacecorps.oam.ModelAvailability
import com.spacecorps.oam.ModelCapabilities
import com.spacecorps.oam.OamJson
import com.spacecorps.oam.TokenUsage
import com.spacecorps.oam.intValue
import com.spacecorps.oam.objectValue
import com.spacecorps.oam.stringValue
import com.spacecorps.oam.toJsonString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * A development [LanguageModel] over any OpenAI-compatible
 * `/v1/chat/completions` server: open-apple-models' `oam serve` (Apple's
 * ~3B on-device model, a close stand-in for Gemini Nano), llama.cpp,
 * Ollama, vLLM, LM Studio, or a hosted API.
 *
 * It sends plain chat completions (a system message and one user message,
 * never `tools`), so the agent's prompt-envelope tool loop runs exactly as it
 * does on Gemini Nano. Use it to develop and evaluate prompts on the desktop;
 * it is not meant for production.
 *
 * @param baseUrl The API root, for example `http://127.0.0.1:19997/v1`.
 * @param model The `model` field sent with each request.
 * @param apiKey Sent as a bearer token when set.
 * @param capabilities Reported capabilities. The default mimics Gemini Nano's
 *   limits (about 4000 input tokens), so trimming behaves as on a device.
 * @param streaming Use server-sent events (true) or one JSON response.
 * @param requestTimeout Time limit per request.
 * @param extraBody Extra top-level fields merged into every request body.
 * @param httpClient The HTTP client.
 */
public class OpenAICompatibleModel(
    public val baseUrl: String,
    public val model: String = "default",
    private val apiKey: String? = null,
    override val capabilities: ModelCapabilities = ModelCapabilities(modelName = model),
    public val streaming: Boolean = true,
    public val requestTimeout: Duration = 2.minutes,
    private val extraBody: JsonObject = JsonObject(emptyMap()),
    private val httpClient: HttpClient = defaultClient(),
) : LanguageModel {
    private val root = baseUrl.trimEnd('/')

    override suspend fun availability(): ModelAvailability {
        val request = newRequest("$root/models").GET().timeout(10.seconds.toJavaDuration()).build()
        return try {
            val response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            when (response.statusCode()) {
                in 200..299 -> ModelAvailability.Available
                else -> ModelAvailability.Unavailable("http_${response.statusCode()}", response.body().take(300))
            }
        } catch (error: IOException) {
            ModelAvailability.Unavailable("unreachable", "Cannot reach $root: ${error.message ?: error::class.simpleName}")
        }
    }

    /** Always `null`: chat completion servers do not count tokens; the agent estimates. */
    override suspend fun countTokens(text: String): Int? = null

    override fun generate(request: GenerationRequest): Flow<GenerationChunk> = flow {
        val body = requestBody(request)
        val httpRequest = newRequest("$root/chat/completions")
            .header("Content-Type", "application/json")
            .header("Accept", if (streaming) "text/event-stream" else "application/json")
            .timeout(requestTimeout.toJavaDuration())
            .POST(HttpRequest.BodyPublishers.ofString(body.toJsonString()))
            .build()
        val response = try {
            httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream()).await()
        } catch (error: IOException) {
            throw transportError(error)
        }
        response.body().use { stream ->
            if (response.statusCode() !in 200..299) {
                val text = runInterruptible { stream.readNBytes(MAX_ERROR_BYTES).decodeToString() }
                throw httpError(response.statusCode(), text, response.headers().firstValue("Retry-After").orElse(null))
            }
            if (streaming) readEvents(stream) else readSingle(stream)
        }
    }.flowOn(Dispatchers.IO)

    private fun requestBody(request: GenerationRequest): JsonObject = buildJsonObject {
        put("model", model)
        put(
            "messages",
            buildJsonArray {
                request.systemInstruction?.let { add(message("system", it)) }
                add(message("user", request.fullPrompt))
            },
        )
        put("stream", streaming)
        if (streaming) put("stream_options", buildJsonObject { put("include_usage", true) })
        request.temperature?.let { put("temperature", it) }
        request.maxOutputTokens?.let { put("max_tokens", it) }
        request.seed?.let { put("seed", it) }
        for ((key, value) in extraBody) put(key, value)
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private suspend fun FlowCollector<GenerationChunk>.readEvents(stream: InputStream) {
        val reader = stream.bufferedReader()
        val text = StringBuilder()
        var usage: TokenUsage? = null
        var finish: FinishReason? = null
        while (true) {
            val line = runInterruptible { reader.readLine() } ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            val event = parse(data) ?: continue
            event["error"]?.objectValue?.let { throw apiError(0, it, null) }
            usage(event)?.let { usage = it }
            val choice = (event["choices"] as? JsonArray)?.firstOrNull()?.objectValue ?: continue
            choice["finish_reason"]?.stringValue?.let { finish = finishReason(it) }
            val delta = choice["delta"]?.objectValue?.get("content")?.stringValue.orEmpty()
            if (delta.isNotEmpty()) {
                text.append(delta)
                emit(GenerationChunk(delta, text.toString()))
            }
        }
        emit(GenerationChunk("", text.toString(), isFinal = true, usage = usage, finishReason = finish ?: FinishReason.STOP))
    }

    private suspend fun FlowCollector<GenerationChunk>.readSingle(stream: InputStream) {
        val body = runInterruptible { stream.readAllBytes().decodeToString() }
        val json = parse(body) ?: throw AgentError(AgentErrorCode.GENERATION_FAILED, "The server returned invalid JSON: ${body.take(200)}")
        val choice = (json["choices"] as? JsonArray)?.firstOrNull()?.objectValue
            ?: throw AgentError(AgentErrorCode.GENERATION_FAILED, "The server returned no choices.")
        val content = choice["message"]?.objectValue?.get("content")?.stringValue.orEmpty()
        emit(GenerationChunk(content, content, isFinal = true, usage = usage(json), finishReason = choice["finish_reason"]?.stringValue?.let(::finishReason)))
    }

    private fun parse(text: String): JsonObject? = try {
        OamJson.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    private fun usage(json: JsonObject): TokenUsage? {
        val usage = json["usage"]?.objectValue ?: return null
        val input = usage["prompt_tokens"]?.intValue ?: return null
        val cached = usage["prompt_tokens_details"]?.objectValue?.get("cached_tokens")?.intValue ?: 0
        return TokenUsage(input, cached, usage["completion_tokens"]?.intValue ?: 0)
    }

    private fun finishReason(value: String): FinishReason = when (value) {
        "stop" -> FinishReason.STOP
        "length" -> FinishReason.MAX_TOKENS
        else -> FinishReason.OTHER
    }

    private fun newRequest(url: String): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create(url))
        apiKey?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun transportError(error: IOException): AgentError = when (error) {
        is HttpTimeoutException -> AgentError(AgentErrorCode.GENERATION_FAILED, "Timed out: the server did not answer within $requestTimeout.", cause = error)
        is ConnectException -> AgentError(AgentErrorCode.MODEL_UNAVAILABLE, "Cannot connect to $root: ${error.message ?: "connection refused"}.", cause = error)
        else -> AgentError(AgentErrorCode.GENERATION_FAILED, "Request to $root failed: ${error.message ?: error::class.simpleName}.", cause = error)
    }

    private fun httpError(status: Int, body: String, retryAfter: String?): AgentError {
        val error = parse(body)?.get("error")?.objectValue
        return if (error != null) apiError(status, error, retryAfter) else statusError(status, body.take(300), retryAfter)
    }

    /** Maps OpenAI's error envelope (and open-apple-models' codes) to [AgentError]. */
    private fun apiError(status: Int, error: JsonObject, retryAfterHeader: String?): AgentError {
        val message = error["message"]?.stringValue ?: "HTTP $status"
        val code = error["code"]?.stringValue
        val type = error["type"]?.stringValue
        val retryAfter = retryAfterHeader?.toLongOrNull()?.seconds
        val mapped = when (code) {
            "content_filter", "guardrail_violation" -> AgentErrorCode.GUARDRAIL_VIOLATION
            "refusal" -> AgentErrorCode.REFUSAL
            "context_length_exceeded", "context_size_exceeded" -> AgentErrorCode.CONTEXT_SIZE_EXCEEDED
            "rate_limited", "rate_limit_exceeded" -> AgentErrorCode.RATE_LIMITED
            "unsupported_language" -> AgentErrorCode.UNSUPPORTED_LANGUAGE
            "invalid_schema" -> AgentErrorCode.INVALID_SCHEMA
            "model_unavailable", "model_not_found" -> AgentErrorCode.MODEL_UNAVAILABLE
            "busy" -> AgentErrorCode.BUSY
            else -> AgentErrorCode.fromWireName(code.orEmpty()) ?: statusCode(status, type)
        }
        return AgentError(mapped, message, retryAfter = retryAfter)
    }

    private fun statusError(status: Int, body: String, retryAfter: String?): AgentError =
        AgentError(statusCode(status, null), "HTTP $status: $body", retryAfter = retryAfter?.toLongOrNull()?.seconds)

    private fun statusCode(status: Int, type: String?): AgentErrorCode = when {
        status == 429 || type == "rate_limit_error" -> AgentErrorCode.RATE_LIMITED
        status == 401 || status == 403 || status == 404 -> AgentErrorCode.MODEL_UNAVAILABLE
        status == 503 -> AgentErrorCode.BUSY
        status in 400..499 -> AgentErrorCode.INVALID_REQUEST
        else -> AgentErrorCode.GENERATION_FAILED
    }

    private companion object {
        const val MAX_ERROR_BYTES = 16_384

        fun defaultClient(): HttpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(10.seconds.toJavaDuration())
            .build()
    }
}
