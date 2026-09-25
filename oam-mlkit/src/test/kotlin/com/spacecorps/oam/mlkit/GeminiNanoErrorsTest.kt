package com.spacecorps.oam.mlkit

import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.GenAiException.ErrorCode
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentErrorCode
import com.spacecorps.oam.mlkit.FakeNanoClient.Companion.genAi
import com.spacecorps.oam.mlkit.MlKitNanoClient.Companion.toMlKit
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ExecutionException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class GeminiNanoErrorsTest {
    @Test
    fun everyMlKitCodeHasAMapping() {
        val expected = mapOf(
            ErrorCode.UNKNOWN to AgentErrorCode.GENERATION_FAILED,
            ErrorCode.REQUEST_PROCESSING_ERROR to AgentErrorCode.GUARDRAIL_VIOLATION,
            ErrorCode.CANCELLED to AgentErrorCode.CANCELLED,
            ErrorCode.NOT_AVAILABLE to AgentErrorCode.MODEL_UNAVAILABLE,
            ErrorCode.BUSY to AgentErrorCode.RATE_LIMITED,
            ErrorCode.RESPONSE_PROCESSING_ERROR to AgentErrorCode.GUARDRAIL_VIOLATION,
            ErrorCode.REQUEST_TOO_LARGE to AgentErrorCode.CONTEXT_SIZE_EXCEEDED,
            ErrorCode.REQUEST_TOO_SMALL to AgentErrorCode.INVALID_REQUEST,
            ErrorCode.RESPONSE_GENERATION_ERROR to AgentErrorCode.GUARDRAIL_VIOLATION,
            ErrorCode.NOT_SUPPORTED to AgentErrorCode.MODEL_UNAVAILABLE,
            ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED to AgentErrorCode.RATE_LIMITED,
            GeminiNanoErrors.PER_DEVICE_BATTERY_USE_QUOTA_EXCEEDED to AgentErrorCode.RATE_LIMITED,
            ErrorCode.BACKGROUND_USE_BLOCKED to AgentErrorCode.RATE_LIMITED,
            ErrorCode.NOT_ENOUGH_DISK_SPACE to AgentErrorCode.MODEL_UNAVAILABLE,
            ErrorCode.NEEDS_SYSTEM_UPDATE to AgentErrorCode.MODEL_UNAVAILABLE,
            ErrorCode.AICORE_INCOMPATIBLE to AgentErrorCode.MODEL_UNAVAILABLE,
            ErrorCode.INVALID_INPUT_IMAGE to AgentErrorCode.INVALID_REQUEST,
            ErrorCode.CACHE_PROCESSING_ERROR to AgentErrorCode.GENERATION_FAILED,
            ErrorCode.STRUCTURED_OUTPUT_REQUEST_ERROR to AgentErrorCode.INVALID_SCHEMA,
            ErrorCode.STRUCTURED_OUTPUT_RESPONSE_ERROR to AgentErrorCode.GENERATION_FAILED,
            ErrorCode.STRUCTURED_OUTPUT_MAX_TOKENS_ERROR to AgentErrorCode.GENERATION_FAILED,
            ErrorCode.AUDIO_BUFFER_OVERFLOW to AgentErrorCode.GENERATION_FAILED,
            12345 to AgentErrorCode.GENERATION_FAILED,
        )
        for ((code, agentCode) in expected) {
            assertEquals(agentCode, GeminiNanoErrors.codeFor(code), "ML Kit code $code")
            assertEquals(agentCode, GeminiNanoErrors.toAgentError(genAi(code)).code, "ML Kit code $code")
        }
        assertEquals("BUSY", GeminiNanoErrors.nameOf(ErrorCode.BUSY))
        assertEquals("error 12345", GeminiNanoErrors.nameOf(12345))
    }

    @Test
    fun retryDelayOnlyForQuotas() {
        val limited = GeminiNanoErrors.toAgentError(genAi(ErrorCode.BUSY, Duration.ofMillis(1500)))
        assertEquals(1500.milliseconds, limited.retryAfter)
        // ML Kit's "no estimate" is zero.
        assertNull(GeminiNanoErrors.toAgentError(genAi(ErrorCode.BUSY, Duration.ZERO)).retryAfter)
        assertNull(GeminiNanoErrors.toAgentError(genAi(ErrorCode.BUSY)).retryAfter)
        assertNull(GeminiNanoErrors.toAgentError(genAi(ErrorCode.NOT_AVAILABLE, Duration.ofSeconds(3))).retryAfter)
    }

    @Test
    fun wrappedExceptionsAreFoundInTheCauseChain() {
        val inner = genAi(ErrorCode.REQUEST_TOO_LARGE)
        val error = GeminiNanoErrors.toAgentError(ExecutionException(inner))
        assertEquals(AgentErrorCode.CONTEXT_SIZE_EXCEEDED, error.code)
        assertEquals(ErrorCode.REQUEST_TOO_LARGE, error.mlKitErrorCode)
    }

    @Test
    fun nonMlKitErrorsUseTheCoreMapping() {
        val agentError = AgentError(AgentErrorCode.BUSY, "busy")
        assertSame(agentError, GeminiNanoErrors.toAgentError(agentError))
        assertNull(agentError.mlKitErrorCode)
        assertEquals(AgentErrorCode.GENERATION_FAILED, GeminiNanoErrors.toAgentError(IllegalStateException("x")).code)
        assertEquals(AgentErrorCode.CANCELLED, GeminiNanoErrors.toAgentError(kotlinx.coroutines.CancellationException("stop")).code)
        assertTrue(GeminiNanoErrors.isPlainCancellation(kotlinx.coroutines.CancellationException("stop")))
        assertFalse(GeminiNanoErrors.isPlainCancellation(genAi(ErrorCode.CANCELLED)))
    }

    @Test
    fun messagesNameTheMlKitCode() {
        val error = GeminiNanoErrors.toAgentError(GenAiException("Background usage is blocked.", null, ErrorCode.BACKGROUND_USE_BLOCKED))
        assertEquals("Gemini Nano: Background usage is blocked. (ML Kit BACKGROUND_USE_BLOCKED, 30)", error.message)
    }

    @Test
    fun unavailableReasons() {
        assertEquals("aicore_unavailable", GeminiNanoErrors.unavailableReason(ErrorCode.AICORE_INCOMPATIBLE))
        assertEquals("needs_system_update", GeminiNanoErrors.unavailableReason(ErrorCode.NEEDS_SYSTEM_UPDATE))
        assertEquals("not_enough_disk_space", GeminiNanoErrors.unavailableReason(ErrorCode.NOT_ENOUGH_DISK_SPACE))
        assertEquals("model_not_ready", GeminiNanoErrors.unavailableReason(ErrorCode.NOT_AVAILABLE))
        assertEquals("unknown", GeminiNanoErrors.unavailableReason(ErrorCode.BUSY))
    }

    /** The conversion to ML Kit's real request type (built from the AAR on the JVM). */
    @Test
    fun requestsConvertToMlKit() {
        val request: GenerateContentRequest = NanoRequest(
            text = "Player: hi",
            systemInstruction = "You are Mira.",
            promptPrefix = "Menu: ale.",
            temperature = 0.2f,
            topK = 8,
            seed = 3,
            maxOutputTokens = 256,
            enableThinking = true,
        ).toMlKit()
        assertEquals("Player: hi", request.text.textString)
        assertEquals("You are Mira.", request.systemInstruction?.textString)
        assertEquals("Menu: ale.", request.promptPrefix?.textString)
        assertEquals(0.2f, request.temperature)
        assertEquals(8, request.topK)
        assertEquals(3, request.seed)
        assertEquals(256, request.maxOutputTokens)
        assertEquals(1, request.candidateCount)
        assertTrue(request.enableThinking)

        val plain = NanoRequest(text = "hello").toMlKit()
        assertNull(plain.systemInstruction)
        assertNull(plain.promptPrefix)
        assertFalse(plain.enableThinking)
    }

    @Test
    fun optionEnumsCarryMlKitValues() {
        assertEquals(ModelPreference.FAST, GeminiNanoOptions.Preference.FAST.mlKitValue)
        assertEquals(ModelPreference.FULL, GeminiNanoOptions.Preference.FULL.mlKitValue)
        assertEquals(ModelReleaseStage.STABLE, GeminiNanoOptions.ReleaseStage.STABLE.mlKitValue)
        assertEquals(ModelReleaseStage.PREVIEW, GeminiNanoOptions.ReleaseStage.PREVIEW.mlKitValue)
    }
}
