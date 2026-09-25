package com.spacecorps.oam.mlkit

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Candidate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import java.time.Duration

/** A scriptable [NanoClient] standing in for ML Kit on the JVM. */
internal class FakeNanoClient(
    @Volatile var status: Int = FeatureStatus.AVAILABLE,
    @Volatile var features: NanoFeatures = NANO_V4,
) : NanoClient {
    private val lock = Any()
    private val scripted = ArrayDeque<(NanoRequest) -> Flow<NanoChunk>>()
    private val recorded = ArrayList<NanoRequest>()

    /** Thrown by [checkStatus] when set. */
    @Volatile var statusError: Throwable? = null

    /** Thrown by every feature check when set. */
    @Volatile var featureError: Throwable? = null

    /** Played by [download]. */
    @Volatile var downloadEvents: Flow<DownloadStatus> = emptyFlow()

    @Volatile var countResult: (NanoRequest) -> Int = { it.text.length }
    @Volatile var warmupError: Throwable? = null

    @Volatile var statusChecks = 0
    @Volatile var downloads = 0
    @Volatile var warmups = 0
    @Volatile var closed = false
    @Volatile var cancelledStreams = 0

    val requests: List<NanoRequest> get() = synchronized(lock) { recorded.toList() }

    /** Queues a response streaming [chunks] then a terminal chunk with [finish]. */
    fun respond(vararg chunks: String, finish: Int? = Candidate.FinishReason.STOP) {
        enqueue {
            flow {
                chunks.forEach { emit(NanoChunk(it)) }
                if (finish != null) emit(NanoChunk(null, finish))
            }
        }
    }

    /** Queues a response that streams [chunks] and then fails with [error]. */
    fun fail(error: Throwable, vararg chunks: String) {
        enqueue {
            flow {
                chunks.forEach { emit(NanoChunk(it)) }
                throw error
            }
        }
    }

    fun enqueue(response: (NanoRequest) -> Flow<NanoChunk>) {
        synchronized(lock) { scripted.addLast(response) }
    }

    override suspend fun checkStatus(): Int {
        statusChecks++
        statusError?.let { throw it }
        return status
    }

    override fun download(): Flow<DownloadStatus> {
        downloads++
        return downloadEvents
    }

    override suspend fun warmup() {
        warmups++
        warmupError?.let { throw it }
    }

    private fun <T> feature(value: T): T {
        featureError?.let { throw it }
        return value
    }

    override suspend fun tokenLimit(): Int = feature(features.tokenLimit ?: throw genAi(GenAiException.ErrorCode.NOT_SUPPORTED))
    override suspend fun baseModelName(): String = feature(features.baseModelName ?: throw genAi(GenAiException.ErrorCode.NOT_SUPPORTED))
    override suspend fun isSystemPromptAvailable(): Boolean = feature(features.systemInstructions)
    override suspend fun isCachingFeatureAvailable(): Boolean = feature(features.prefixCaching)
    override suspend fun isStructuredOutputFeatureAvailable(): Boolean = feature(features.structuredOutput)
    override suspend fun isThinkingModeAvailable(): Boolean = feature(features.thinking)

    val countRequests: List<NanoRequest> get() = synchronized(lock) { counted.toList() }
    private val counted = ArrayList<NanoRequest>()

    override suspend fun countTokens(request: NanoRequest): Int {
        synchronized(lock) { counted += request }
        return countResult(request)
    }

    override fun generateStream(request: NanoRequest): Flow<NanoChunk> {
        val response = synchronized(lock) {
            recorded += request
            scripted.removeFirstOrNull()
        } ?: return flowOf(NanoChunk("ok"), NanoChunk(null, Candidate.FinishReason.STOP))
        return response(request).onCompletion { cause -> if (cause is kotlinx.coroutines.CancellationException) cancelledStreams++ }
    }

    override fun close() {
        closed = true
    }

    companion object {
        val NANO_V4 = NanoFeatures(
            baseModelName = "nano-v4",
            tokenLimit = 4000,
            systemInstructions = true,
            prefixCaching = true,
            structuredOutput = true,
            thinking = true,
        )

        val NANO_V2 = NanoFeatures(baseModelName = "nano-v2", tokenLimit = 3000)

        fun genAi(code: Int, retryDelay: Duration? = null, message: String? = null): GenAiException =
            if (retryDelay == null) GenAiException(message ?: "ML Kit failure $code", null, code) else GenAiException(message ?: "ML Kit failure $code", null, code, retryDelay)
    }
}
