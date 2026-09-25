package com.spacecorps.oam.mlkit

import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.GenAiException.ErrorCode
import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentErrorCode
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.toKotlinDuration

/**
 * Maps ML Kit GenAI failures (`GenAiException.ErrorCode`) to open-apple-models'
 * error codes, so hosts handle both platforms the same way.
 *
 * | ML Kit code | [AgentErrorCode] |
 * |---|---|
 * | `BUSY` (9): per-app inference quota | [AgentErrorCode.RATE_LIMITED] |
 * | `PER_APP_BATTERY_USE_QUOTA_EXCEEDED` (27), [PER_DEVICE_BATTERY_USE_QUOTA_EXCEEDED] (28): long-duration quotas | [AgentErrorCode.RATE_LIMITED] |
 * | `BACKGROUND_USE_BLOCKED` (30): app not in the foreground | [AgentErrorCode.RATE_LIMITED] |
 * | `REQUEST_PROCESSING_ERROR` (4), `RESPONSE_PROCESSING_ERROR` (11), `RESPONSE_GENERATION_ERROR` (15): safety policy | [AgentErrorCode.GUARDRAIL_VIOLATION] |
 * | `REQUEST_TOO_LARGE` (12) | [AgentErrorCode.CONTEXT_SIZE_EXCEEDED] |
 * | `NOT_AVAILABLE` (8), `NOT_SUPPORTED` (16), `NOT_ENOUGH_DISK_SPACE` (501), `NEEDS_SYSTEM_UPDATE` (604), `AICORE_INCOMPATIBLE` (-101) | [AgentErrorCode.MODEL_UNAVAILABLE] |
 * | `CANCELLED` (7) | [AgentErrorCode.CANCELLED] |
 * | `REQUEST_TOO_SMALL` (-100), `INVALID_INPUT_IMAGE` (-102) | [AgentErrorCode.INVALID_REQUEST] |
 * | `STRUCTURED_OUTPUT_REQUEST_ERROR` (-104) | [AgentErrorCode.INVALID_SCHEMA] |
 * | `UNKNOWN` (0), `CACHE_PROCESSING_ERROR` (-103), -105, -106, -107, anything else | [AgentErrorCode.GENERATION_FAILED] |
 *
 * Quotas map to `rate_limited`, as Apple's rate limit does, carrying ML Kit's
 * `retryDelay` as [AgentError.retryAfter]; the agent's retry policy then backs
 * off (Google recommends exponential backoff) unless the wait is long. The
 * original exception stays the [AgentError.cause]; [mlKitErrorCode] reads it back.
 */
public object GeminiNanoErrors {
    /**
     * An ML Kit error code missing from `GenAiException.ErrorCode` (beta4) but
     * produced by AICore: the device-wide long-duration (battery) quota was exceeded.
     */
    public const val PER_DEVICE_BATTERY_USE_QUOTA_EXCEEDED: Int = 28

    /** Stable reasons for `ModelAvailability.Unavailable`, matching open-apple-models' wire names where they overlap. */
    public object Reasons {
        /** The device has no Gemini Nano support (`FeatureStatus.UNAVAILABLE`). */
        public const val DEVICE_NOT_ELIGIBLE: String = "device_not_eligible"

        /** AICore is missing or too old (`AICORE_INCOMPATIBLE`). */
        public const val AICORE_UNAVAILABLE: String = "aicore_unavailable"

        /** Android is too old (`NEEDS_SYSTEM_UPDATE`). */
        public const val NEEDS_SYSTEM_UPDATE: String = "needs_system_update"

        /** Not enough storage for the model (`NOT_ENOUGH_DISK_SPACE`). */
        public const val NOT_ENOUGH_DISK_SPACE: String = "not_enough_disk_space"

        /** The feature is not available right now (`NOT_AVAILABLE`, `NOT_SUPPORTED`). */
        public const val MODEL_NOT_READY: String = "model_not_ready"

        /** Anything else. */
        public const val UNKNOWN: String = "unknown"
    }

    /** The [AgentErrorCode] for an ML Kit `GenAiException.ErrorCode` value. */
    public fun codeFor(mlKitErrorCode: Int): AgentErrorCode = when (mlKitErrorCode) {
        ErrorCode.BUSY, ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED, PER_DEVICE_BATTERY_USE_QUOTA_EXCEEDED,
        ErrorCode.BACKGROUND_USE_BLOCKED,
        -> AgentErrorCode.RATE_LIMITED
        ErrorCode.REQUEST_PROCESSING_ERROR, ErrorCode.RESPONSE_PROCESSING_ERROR, ErrorCode.RESPONSE_GENERATION_ERROR -> AgentErrorCode.GUARDRAIL_VIOLATION
        ErrorCode.REQUEST_TOO_LARGE -> AgentErrorCode.CONTEXT_SIZE_EXCEEDED
        ErrorCode.NOT_AVAILABLE, ErrorCode.NOT_SUPPORTED, ErrorCode.NOT_ENOUGH_DISK_SPACE,
        ErrorCode.NEEDS_SYSTEM_UPDATE, ErrorCode.AICORE_INCOMPATIBLE,
        -> AgentErrorCode.MODEL_UNAVAILABLE
        ErrorCode.CANCELLED -> AgentErrorCode.CANCELLED
        ErrorCode.REQUEST_TOO_SMALL, ErrorCode.INVALID_INPUT_IMAGE -> AgentErrorCode.INVALID_REQUEST
        ErrorCode.STRUCTURED_OUTPUT_REQUEST_ERROR -> AgentErrorCode.INVALID_SCHEMA
        else -> AgentErrorCode.GENERATION_FAILED
    }

    /** The `ModelAvailability.Unavailable` reason for an ML Kit error code (see [Reasons]). */
    public fun unavailableReason(mlKitErrorCode: Int): String = when (mlKitErrorCode) {
        ErrorCode.AICORE_INCOMPATIBLE -> Reasons.AICORE_UNAVAILABLE
        ErrorCode.NEEDS_SYSTEM_UPDATE -> Reasons.NEEDS_SYSTEM_UPDATE
        ErrorCode.NOT_ENOUGH_DISK_SPACE -> Reasons.NOT_ENOUGH_DISK_SPACE
        ErrorCode.NOT_AVAILABLE, ErrorCode.NOT_SUPPORTED -> Reasons.MODEL_NOT_READY
        else -> Reasons.UNKNOWN
    }

    /** The ML Kit constant's name for [mlKitErrorCode], for messages and logs. */
    public fun nameOf(mlKitErrorCode: Int): String = NAMES[mlKitErrorCode] ?: "error $mlKitErrorCode"

    /**
     * Converts any failure from ML Kit into an [AgentError]. A `GenAiException`
     * anywhere in the cause chain is mapped by [codeFor]; an [AgentError] is
     * returned as is; anything else goes through [AgentError.from].
     */
    public fun toAgentError(error: Throwable): AgentError {
        if (error is AgentError) return error
        val genAi = error.genAiException() ?: return AgentError.from(error)
        val code = genAi.errorCode
        val retryAfter = when (codeFor(code)) {
            // ML Kit reports zero when it has no estimate.
            AgentErrorCode.RATE_LIMITED -> genAi.retryDelay.takeUnless { it.isNegative || it.isZero }?.toKotlinDuration()
            else -> null
        }
        val detail = genAi.message?.takeIf { it.isNotBlank() } ?: DESCRIPTIONS[code] ?: "ML Kit GenAI failed."
        return AgentError(codeFor(code), "Gemini Nano: $detail (ML Kit ${nameOf(code)}, $code)", retryAfter, error)
    }

    /** Whether [error] is a coroutine cancellation (not an ML Kit failure) that must propagate unchanged. */
    internal fun isPlainCancellation(error: Throwable): Boolean = error is CancellationException && error.genAiException() == null

    private fun Throwable.genAiException(): GenAiException? {
        var current: Throwable? = this
        var depth = 0
        while (current != null && depth < 8) {
            if (current is GenAiException) return current
            current = current.cause
            depth++
        }
        return null
    }

    private val NAMES: Map<Int, String> = mapOf(
        ErrorCode.UNKNOWN to "UNKNOWN",
        ErrorCode.REQUEST_PROCESSING_ERROR to "REQUEST_PROCESSING_ERROR",
        ErrorCode.CANCELLED to "CANCELLED",
        ErrorCode.NOT_AVAILABLE to "NOT_AVAILABLE",
        ErrorCode.BUSY to "BUSY",
        ErrorCode.RESPONSE_PROCESSING_ERROR to "RESPONSE_PROCESSING_ERROR",
        ErrorCode.REQUEST_TOO_LARGE to "REQUEST_TOO_LARGE",
        ErrorCode.REQUEST_TOO_SMALL to "REQUEST_TOO_SMALL",
        ErrorCode.RESPONSE_GENERATION_ERROR to "RESPONSE_GENERATION_ERROR",
        ErrorCode.NOT_SUPPORTED to "NOT_SUPPORTED",
        ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED to "PER_APP_BATTERY_USE_QUOTA_EXCEEDED",
        PER_DEVICE_BATTERY_USE_QUOTA_EXCEEDED to "PER_DEVICE_BATTERY_USE_QUOTA_EXCEEDED",
        ErrorCode.BACKGROUND_USE_BLOCKED to "BACKGROUND_USE_BLOCKED",
        ErrorCode.NOT_ENOUGH_DISK_SPACE to "NOT_ENOUGH_DISK_SPACE",
        ErrorCode.NEEDS_SYSTEM_UPDATE to "NEEDS_SYSTEM_UPDATE",
        ErrorCode.AICORE_INCOMPATIBLE to "AICORE_INCOMPATIBLE",
        ErrorCode.INVALID_INPUT_IMAGE to "INVALID_INPUT_IMAGE",
        ErrorCode.CACHE_PROCESSING_ERROR to "CACHE_PROCESSING_ERROR",
        ErrorCode.STRUCTURED_OUTPUT_REQUEST_ERROR to "STRUCTURED_OUTPUT_REQUEST_ERROR",
        ErrorCode.STRUCTURED_OUTPUT_RESPONSE_ERROR to "STRUCTURED_OUTPUT_RESPONSE_ERROR",
        ErrorCode.STRUCTURED_OUTPUT_MAX_TOKENS_ERROR to "STRUCTURED_OUTPUT_MAX_TOKENS_ERROR",
        ErrorCode.AUDIO_BUFFER_OVERFLOW to "AUDIO_BUFFER_OVERFLOW",
    )

    private val DESCRIPTIONS: Map<Int, String> = mapOf(
        ErrorCode.BUSY to "AICore's per-app inference quota was hit; retry with backoff.",
        ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED to "The app exceeded AICore's long-duration (battery) quota.",
        PER_DEVICE_BATTERY_USE_QUOTA_EXCEEDED to "The device exceeded AICore's long-duration (battery) quota.",
        ErrorCode.BACKGROUND_USE_BLOCKED to "Gemini Nano only runs while the app is the top foreground app.",
        ErrorCode.REQUEST_PROCESSING_ERROR to "The request did not pass the safety policy check.",
        ErrorCode.RESPONSE_PROCESSING_ERROR to "The response did not pass the safety policy check.",
        ErrorCode.RESPONSE_GENERATION_ERROR to "The model could not respond because of the safety policy.",
        ErrorCode.REQUEST_TOO_LARGE to "The prompt is too large for Gemini Nano.",
        ErrorCode.NOT_AVAILABLE to "Gemini Nano is not available.",
        ErrorCode.AICORE_INCOMPATIBLE to "AICore is not installed or too old.",
    )
}

/**
 * The ML Kit `GenAiException.ErrorCode` behind this error, when it came from
 * ML Kit (see [GeminiNanoErrors.toAgentError]); `null` otherwise.
 */
public val AgentError.mlKitErrorCode: Int?
    get() {
        var current: Throwable? = cause
        var depth = 0
        while (current != null && depth < 8) {
            if (current is GenAiException) return current.errorCode
            current = current.cause
            depth++
        }
        return null
    }
