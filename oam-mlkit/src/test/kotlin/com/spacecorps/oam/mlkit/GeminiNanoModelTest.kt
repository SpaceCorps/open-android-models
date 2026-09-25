package com.spacecorps.oam.mlkit

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException.ErrorCode
import com.google.mlkit.genai.prompt.Candidate
import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentErrorCode
import com.spacecorps.oam.FinishReason
import com.spacecorps.oam.GenerationChunk
import com.spacecorps.oam.GenerationKind
import com.spacecorps.oam.GenerationRequest
import com.spacecorps.oam.ModelAvailability
import com.spacecorps.oam.ModelCapabilities
import com.spacecorps.oam.ToolDefinition
import com.spacecorps.oam.mlkit.FakeNanoClient.Companion.genAi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class GeminiNanoModelTest {
    private fun model(client: FakeNanoClient = FakeNanoClient(), options: GeminiNanoOptions = GeminiNanoOptions()) =
        GeminiNanoModel(client, options)

    // MARK: Availability and features

    @Test
    fun availableStatusDetectsFeaturesAndUpdatesCapabilities() = runTest {
        val client = FakeNanoClient(features = FakeNanoClient.NANO_V4.copy(tokenLimit = 6000))
        val nano = model(client)
        assertNull(nano.detectedFeatures.value)

        assertEquals(ModelAvailability.Available, nano.availability())

        val features = nano.detectedFeatures.value
        assertEquals("nano-v4", features?.baseModelName)
        assertTrue(features!!.systemInstructions && features.prefixCaching && features.thinking && features.structuredOutput)
        assertFalse(features.nativeToolCalling)
        assertEquals(
            ModelCapabilities(nativeToolCalling = false, systemInstructions = true, maxInputTokens = 6000, maxOutputTokens = 4096, modelName = "nano-v4"),
            nano.capabilities,
        )
        assertEquals(nano.capabilities, nano.capabilityUpdates.value)
    }

    @Test
    fun capabilitiesBeforeDetectionAssumeANewDevice() {
        val nano = model()
        assertEquals(
            ModelCapabilities(nativeToolCalling = false, systemInstructions = true, maxInputTokens = 4000, maxOutputTokens = 4096, modelName = "gemini-nano"),
            nano.capabilities,
        )
        assertEquals(1234, model(options = GeminiNanoOptions(maxInputTokens = 1234)).capabilities.maxInputTokens)
    }

    @Test
    fun olderDeviceReportsNoSystemInstructions() = runTest {
        val nano = model(FakeNanoClient(features = FakeNanoClient.NANO_V2))
        nano.availability()
        assertFalse(nano.capabilities.systemInstructions)
        assertEquals(3000, nano.capabilities.maxInputTokens)
        assertEquals("nano-v2", nano.capabilities.modelName)
    }

    @Test
    fun maxInputTokensOptionOverridesTheTokenLimit() = runTest {
        val nano = model(options = GeminiNanoOptions(maxInputTokens = 2500))
        nano.availability()
        assertEquals(2500, nano.capabilities.maxInputTokens)
    }

    @Test
    fun featureStatusesMapToAvailability() = runTest {
        val client = FakeNanoClient()
        val nano = model(client)
        client.status = FeatureStatus.DOWNLOADABLE
        assertEquals(ModelAvailability.Downloadable, nano.availability())
        client.status = FeatureStatus.DOWNLOADING
        assertEquals(ModelAvailability.Downloading(), nano.availability())
        client.status = FeatureStatus.UNAVAILABLE
        val unavailable = assertIs<ModelAvailability.Unavailable>(nano.availability())
        assertEquals(GeminiNanoErrors.Reasons.DEVICE_NOT_ELIGIBLE, unavailable.reason)
        client.status = 42
        assertEquals(GeminiNanoErrors.Reasons.UNKNOWN, assertIs<ModelAvailability.Unavailable>(nano.availability()).reason)
        // Nothing was detected while the model was not available.
        assertNull(nano.detectedFeatures.value)
    }

    @Test
    fun statusFailuresBecomeUnavailableReasons() = runTest {
        val client = FakeNanoClient()
        val nano = model(client)
        val cases = mapOf(
            ErrorCode.AICORE_INCOMPATIBLE to GeminiNanoErrors.Reasons.AICORE_UNAVAILABLE,
            ErrorCode.NEEDS_SYSTEM_UPDATE to GeminiNanoErrors.Reasons.NEEDS_SYSTEM_UPDATE,
            ErrorCode.NOT_ENOUGH_DISK_SPACE to GeminiNanoErrors.Reasons.NOT_ENOUGH_DISK_SPACE,
            ErrorCode.NOT_AVAILABLE to GeminiNanoErrors.Reasons.MODEL_NOT_READY,
            ErrorCode.UNKNOWN to GeminiNanoErrors.Reasons.UNKNOWN,
        )
        for ((code, reason) in cases) {
            client.statusError = genAi(code)
            val unavailable = assertIs<ModelAvailability.Unavailable>(nano.availability())
            assertEquals(reason, unavailable.reason, "code $code")
            assertTrue(unavailable.detail!!.contains("$code"), unavailable.detail)
        }
        client.statusError = IllegalStateException("AICore binder died")
        assertEquals(ModelAvailability.Unavailable(GeminiNanoErrors.Reasons.UNKNOWN, "AICore binder died"), nano.availability())
    }

    @Test
    fun detectionWaitsForTheModelAndCanRefresh() = runTest {
        val client = FakeNanoClient(status = FeatureStatus.DOWNLOADABLE)
        val nano = model(client)
        assertNull(nano.detectFeatures())
        client.status = FeatureStatus.AVAILABLE
        assertEquals("nano-v4", nano.detectFeatures()?.baseModelName)
        client.features = FakeNanoClient.NANO_V2
        assertEquals("nano-v4", nano.detectFeatures()?.baseModelName) // cached
        assertEquals("nano-v2", nano.detectFeatures(refresh = true)?.baseModelName)
        assertEquals("nano-v2", nano.capabilities.modelName)
    }

    @Test
    fun failingFeatureChecksCountAsUnsupported() = runTest {
        val client = FakeNanoClient().apply { featureError = genAi(ErrorCode.NOT_SUPPORTED) }
        val nano = model(client)
        val features = nano.detectFeatures()!!
        assertEquals(NanoFeatures(), features)
        assertFalse(nano.capabilities.systemInstructions)
        assertEquals(4000, nano.capabilities.maxInputTokens)
        assertEquals("gemini-nano", nano.capabilities.modelName)
    }

    // MARK: Generation

    @Test
    fun streamsDeltasWithRunningTextAndAFinalChunk() = runTest {
        val client = FakeNanoClient()
        client.enqueue {
            flowOf(NanoChunk(null), NanoChunk("Evening, "), NanoChunk("love."), NanoChunk(null, Candidate.FinishReason.STOP))
        }
        val chunks = model(client).generate(GenerationRequest("Hi")).toList()
        assertEquals(
            listOf(
                GenerationChunk("Evening, ", "Evening, "),
                GenerationChunk("love.", "Evening, love."),
                GenerationChunk("", "Evening, love.", isFinal = true, usage = null, finishReason = FinishReason.STOP),
            ),
            chunks,
        )
    }

    @Test
    fun finishReasonsMap() = runTest {
        val client = FakeNanoClient()
        val nano = model(client)
        client.respond("cut", finish = Candidate.FinishReason.MAX_TOKENS)
        assertEquals(FinishReason.MAX_TOKENS, nano.generate(GenerationRequest("x")).toList().last().finishReason)
        client.respond("odd", finish = Candidate.FinishReason.OTHER)
        assertEquals(FinishReason.OTHER, nano.generate(GenerationRequest("x")).toList().last().finishReason)
        client.respond("no reason", finish = null)
        assertEquals(FinishReason.STOP, nano.generate(GenerationRequest("x")).toList().last().finishReason)
        client.respond(finish = Candidate.FinishReason.STOP)
        assertEquals(GenerationChunk("", "", isFinal = true, finishReason = FinishReason.STOP), nano.generate(GenerationRequest("x")).toList().single())
    }

    @Test
    fun anEmptyAbnormalStopFails() = runTest {
        val client = FakeNanoClient().apply { respond(finish = Candidate.FinishReason.OTHER) }
        val error = assertFailsWith<AgentError> { model(client).generate(GenerationRequest("x")).toList() }
        assertEquals(AgentErrorCode.GENERATION_FAILED, error.code)
    }

    @Test
    fun systemInstructionUsesMlKitWhereSupported() = runTest {
        val client = FakeNanoClient()
        model(client).generate(GenerationRequest("Hi", systemInstruction = "You are Mira.")).toList()
        val request = client.requests.single()
        assertEquals("You are Mira.", request.systemInstruction)
        assertEquals("Hi", request.text)
        assertNull(request.promptPrefix)
    }

    @Test
    fun systemInstructionBecomesAPrefixOnOlderDevices() = runTest {
        val client = FakeNanoClient(features = FakeNanoClient.NANO_V2.copy(prefixCaching = true))
        model(client).generate(GenerationRequest("Player: Hi", systemInstruction = "You are Mira.", promptPrefix = "Menu: ale.\n")).toList()
        val request = client.requests.single()
        assertNull(request.systemInstruction)
        assertEquals("You are Mira.\n\nMenu: ale.\n", request.promptPrefix)
        assertEquals("Player: Hi", request.text)
    }

    @Test
    fun prefixIsConcatenatedWithoutCaching() = runTest {
        val noCaching = FakeNanoClient(features = FakeNanoClient.NANO_V2)
        model(noCaching).generate(GenerationRequest("Hi", systemInstruction = "Rules.", promptPrefix = "Prefix. ")).toList()
        assertEquals(NanoRequest(text = "Rules.\n\nPrefix. Hi"), noCaching.requests.single())

        val optedOut = FakeNanoClient()
        model(optedOut, GeminiNanoOptions(prefixCaching = false)).generate(GenerationRequest("Hi", promptPrefix = "Prefix. ")).toList()
        assertEquals(NanoRequest(text = "Prefix. Hi"), optedOut.requests.single())

        val cached = FakeNanoClient()
        model(cached).generate(GenerationRequest("Hi", promptPrefix = "Prefix. ")).toList()
        assertEquals(NanoRequest(text = "Hi", promptPrefix = "Prefix. "), cached.requests.single())
    }

    @Test
    fun samplingParametersAreBroughtIntoMlKitRanges() = runTest {
        val client = FakeNanoClient()
        val nano = model(client)
        nano.generate(GenerationRequest("a", temperature = 0.3, topK = 16, seed = 7, maxOutputTokens = 200)).toList()
        nano.generate(GenerationRequest("b", temperature = 1.7, topK = 0, seed = -5, maxOutputTokens = 10_000)).toList()
        nano.generate(GenerationRequest("c", maxOutputTokens = 0)).toList()
        val (a, b, c) = client.requests
        assertEquals(NanoRequest(text = "a", temperature = 0.3f, topK = 16, seed = 7, maxOutputTokens = 200), a)
        assertEquals(NanoRequest(text = "b", temperature = 1f, topK = null, seed = -5 and Int.MAX_VALUE, maxOutputTokens = 4096), b)
        assertEquals(1, c.maxOutputTokens)
        assertTrue(b.seed!! >= 0)
    }

    @Test
    fun thinkingOnlyForConfiguredKindsOnCapableDevices() = runTest {
        val client = FakeNanoClient()
        val nano = model(client, GeminiNanoOptions(thinkingFor = setOf(GenerationKind.DECIDE)))
        nano.generate(GenerationRequest("d", kind = GenerationKind.DECIDE)).toList()
        nano.generate(GenerationRequest("r", kind = GenerationKind.RESPOND)).toList()
        assertEquals(listOf(true, false), client.requests.map { it.enableThinking })

        val older = FakeNanoClient(features = FakeNanoClient.NANO_V4.copy(thinking = false))
        model(older, GeminiNanoOptions(thinkingFor = setOf(GenerationKind.DECIDE))).generate(GenerationRequest("d", kind = GenerationKind.DECIDE)).toList()
        assertFalse(older.requests.single().enableThinking)
    }

    @Test
    fun toolsAreNotSentWithoutNativeToolCalling() = runTest {
        val client = FakeNanoClient()
        model(client).generate(GenerationRequest("x", tools = listOf(ToolDefinition("check_menu", "Menu")))).toList()
        assertEquals(emptyList(), client.requests.single().tools)
    }

    @Test
    fun generateDetectsFeaturesLazily() = runTest {
        val client = FakeNanoClient(features = FakeNanoClient.NANO_V2)
        val nano = model(client)
        // Before detection the model assumes system-instruction support, but the request is adapted.
        assertTrue(nano.capabilities.systemInstructions)
        nano.generate(GenerationRequest("Hi", systemInstruction = "Rules.")).toList()
        assertEquals(NanoRequest(text = "Rules.\n\nHi"), client.requests.single())
        assertFalse(nano.capabilities.systemInstructions)
    }

    // MARK: Errors

    @Test
    fun quotaErrorsBecomeRateLimitedWithRetryDelay() = runTest {
        val client = FakeNanoClient()
        val nano = model(client)
        client.fail(genAi(ErrorCode.BUSY, Duration.ofSeconds(2)))
        val busy = assertFailsWith<AgentError> { nano.generate(GenerationRequest("x")).toList() }
        assertEquals(AgentErrorCode.RATE_LIMITED, busy.code)
        assertEquals(2.seconds, busy.retryAfter)
        assertEquals(ErrorCode.BUSY, busy.mlKitErrorCode)
        assertTrue(busy.message.contains("BUSY"))

        for (code in listOf(ErrorCode.BACKGROUND_USE_BLOCKED, ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED, GeminiNanoErrors.PER_DEVICE_BATTERY_USE_QUOTA_EXCEEDED)) {
            client.fail(genAi(code))
            val error = assertFailsWith<AgentError> { nano.generate(GenerationRequest("x")).toList() }
            assertEquals(AgentErrorCode.RATE_LIMITED, error.code, "code $code")
            assertNull(error.retryAfter)
        }
    }

    @Test
    fun safetyAndOtherErrorsMap() = runTest {
        val client = FakeNanoClient()
        val nano = model(client)
        val cases = mapOf(
            ErrorCode.REQUEST_PROCESSING_ERROR to AgentErrorCode.GUARDRAIL_VIOLATION,
            ErrorCode.RESPONSE_PROCESSING_ERROR to AgentErrorCode.GUARDRAIL_VIOLATION,
            ErrorCode.RESPONSE_GENERATION_ERROR to AgentErrorCode.GUARDRAIL_VIOLATION,
            ErrorCode.REQUEST_TOO_LARGE to AgentErrorCode.CONTEXT_SIZE_EXCEEDED,
            ErrorCode.NOT_AVAILABLE to AgentErrorCode.MODEL_UNAVAILABLE,
            ErrorCode.CANCELLED to AgentErrorCode.CANCELLED,
            ErrorCode.UNKNOWN to AgentErrorCode.GENERATION_FAILED,
        )
        for ((code, expected) in cases) {
            client.fail(genAi(code))
            val error = assertFailsWith<AgentError> { nano.generate(GenerationRequest("x")).toList() }
            assertEquals(expected, error.code, "code $code")
        }
        client.fail(IllegalStateException("boom"))
        assertEquals(AgentErrorCode.GENERATION_FAILED, assertFailsWith<AgentError> { nano.generate(GenerationRequest("x")).toList() }.code)
    }

    @Test
    fun aFailureMidStreamKeepsTheEmittedText() = runTest {
        val client = FakeNanoClient().apply { fail(genAi(ErrorCode.RESPONSE_PROCESSING_ERROR), "Well, ", "the") }
        val seen = ArrayList<GenerationChunk>()
        val error = assertFailsWith<AgentError> { model(client).generate(GenerationRequest("x")).collect { seen += it } }
        assertEquals(AgentErrorCode.GUARDRAIL_VIOLATION, error.code)
        assertEquals(listOf("Well, ", "Well, the"), seen.map { it.text })
    }

    @Test
    fun aPrefixCacheFailureFallsBackToPlainText() = runTest {
        val client = FakeNanoClient()
        val nano = model(client)
        client.fail(genAi(ErrorCode.CACHE_PROCESSING_ERROR))
        client.respond("Fine.")
        val text = nano.generate(GenerationRequest("Hi", promptPrefix = "Rules. ")).toList().last().text
        assertEquals("Fine.", text)
        assertEquals(
            listOf(NanoRequest(text = "Hi", promptPrefix = "Rules. "), NanoRequest(text = "Rules. Hi")),
            client.requests,
        )
        // Caching stays off afterwards.
        nano.generate(GenerationRequest("Again", promptPrefix = "Rules. ")).toList()
        assertEquals(NanoRequest(text = "Rules. Again"), client.requests.last())
    }

    @Test
    fun aCacheFailureAfterOutputIsNotRetried() = runTest {
        val client = FakeNanoClient().apply { fail(genAi(ErrorCode.CACHE_PROCESSING_ERROR), "Part") }
        val error = assertFailsWith<AgentError> { model(client).generate(GenerationRequest("Hi", promptPrefix = "P ")).toList() }
        assertEquals(AgentErrorCode.GENERATION_FAILED, error.code)
        assertEquals(1, client.requests.size)
    }

    @Test
    fun cancellingTheCollectorCancelsMlKit() = runTest {
        val client = FakeNanoClient()
        client.enqueue {
            flow {
                emit(NanoChunk("{\"action\":\"respond\"}"))
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val first = model(client).generate(GenerationRequest("x")).take(1).toList()
        assertEquals("{\"action\":\"respond\"}", first.single().text)
        assertEquals(1, client.cancelledStreams)
    }

    @Test
    fun downstreamExceptionsAreNotRemapped() = runTest {
        val client = FakeNanoClient().apply { respond("a", "b") }
        val failure = IllegalStateException("collector failed")
        val thrown = assertFailsWith<IllegalStateException> {
            model(client).generate(GenerationRequest("x")).onEach { throw failure }.toList()
        }
        assertSame(failure, thrown)
    }

    // MARK: Tokens and warmup

    @Test
    fun countTokensUsesMlKitAndReturnsNullOnFailure() = runTest {
        val client = FakeNanoClient().apply { countResult = { 17 } }
        val nano = model(client)
        assertEquals(17, nano.countTokens("Hello there"))
        assertEquals(listOf(NanoRequest(text = "Hello there")), client.countRequests)
        client.countResult = { throw genAi(ErrorCode.NOT_AVAILABLE) }
        assertNull(nano.countTokens("Hello"))
    }

    @Test
    fun prewarmWarmsUpAndIgnoresFailures() = runTest {
        val client = FakeNanoClient()
        val nano = model(client)
        nano.prewarm("Rules.")
        assertEquals(1, client.warmups)
        assertEquals("nano-v4", nano.detectedFeatures.value?.baseModelName)
        client.warmupError = genAi(ErrorCode.BUSY)
        nano.prewarm()
        assertEquals(2, client.warmups)
    }

    @Test
    fun closeReleasesTheClient() {
        val client = FakeNanoClient()
        model(client).close()
        assertTrue(client.closed)
    }

    // MARK: Download

    @Test
    fun downloadReportsProgressAndCompletes() = runTest {
        val client = FakeNanoClient(status = FeatureStatus.DOWNLOADABLE, features = FakeNanoClient.NANO_V4)
        val nano = model(client)
        val during = ArrayList<ModelAvailability?>()
        val availabilityDuring = ArrayList<ModelAvailability>()
        client.downloadEvents = flow {
            emit(DownloadStatus.DownloadStarted(1000))
            client.status = FeatureStatus.DOWNLOADING
            emit(DownloadStatus.DownloadProgress(400))
            availabilityDuring += nano.availability()
            emit(DownloadStatus.DownloadProgress(1000))
            client.status = FeatureStatus.AVAILABLE
            emit(DownloadStatus.DownloadCompleted)
        }
        val events = nano.download().onEach { during += nano.downloadProgress.value }.toList()
        assertEquals(
            listOf(DownloadEvent.Started(1000), DownloadEvent.Progress(400, 1000), DownloadEvent.Progress(1000, 1000), DownloadEvent.Completed),
            events,
        )
        assertEquals(0.4f, (events[1] as DownloadEvent.Progress).fraction)
        assertEquals<List<ModelAvailability?>>(
            listOf(ModelAvailability.Downloading(0, 1000), ModelAvailability.Downloading(400, 1000), ModelAvailability.Downloading(1000, 1000), null),
            during,
        )
        assertEquals(listOf<ModelAvailability>(ModelAvailability.Downloading(400, 1000)), availabilityDuring)
        assertNull(nano.downloadProgress.value)
        assertEquals("nano-v4", nano.capabilities.modelName) // detected on completion
    }

    @Test
    fun downloadOfAnAvailableModelCompletesAtOnce() = runTest {
        val client = FakeNanoClient()
        assertEquals(listOf<DownloadEvent>(DownloadEvent.Completed), model(client).download().toList())
        assertEquals(0, client.downloads)
    }

    @Test
    fun downloadOnAnUnsupportedDeviceFails() = runTest {
        val client = FakeNanoClient(status = FeatureStatus.UNAVAILABLE)
        val error = assertFailsWith<AgentError> { model(client).download().toList() }
        assertEquals(AgentErrorCode.MODEL_UNAVAILABLE, error.code)
        client.statusError = genAi(ErrorCode.AICORE_INCOMPATIBLE)
        assertEquals(ErrorCode.AICORE_INCOMPATIBLE, assertFailsWith<AgentError> { model(client).download().toList() }.mlKitErrorCode)
    }

    @Test
    fun aFailedDownloadThrowsModelUnavailable() = runTest {
        val client = FakeNanoClient(status = FeatureStatus.DOWNLOADABLE)
        val nano = model(client)
        client.downloadEvents = flowOf(DownloadStatus.DownloadStarted(10), DownloadStatus.DownloadFailed(genAi(ErrorCode.NOT_ENOUGH_DISK_SPACE)))
        val error = assertFailsWith<AgentError> { nano.download().toList() }
        assertEquals(AgentErrorCode.MODEL_UNAVAILABLE, error.code)
        assertEquals(ErrorCode.NOT_ENOUGH_DISK_SPACE, error.mlKitErrorCode)
        assertNull(nano.downloadProgress.value)

        client.downloadEvents = flowOf(DownloadStatus.DownloadFailed(genAi(ErrorCode.BUSY)))
        val busy = assertFailsWith<AgentError> { nano.download().toList() }
        assertEquals(AgentErrorCode.MODEL_UNAVAILABLE, busy.code)
        assertEquals(ErrorCode.BUSY, busy.mlKitErrorCode)
        assertTrue(busy.message.startsWith("Gemini Nano download failed"))
    }

    @Test
    fun downloadingAvailabilityWithoutProgress() = runTest {
        val nano = model(FakeNanoClient(status = FeatureStatus.DOWNLOADING))
        assertEquals(ModelAvailability.Downloading(null, null), nano.availability())
        assertNull(nano.downloadProgress.value)
    }
}
