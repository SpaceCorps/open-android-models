package com.spacecorps.oam

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Error categories, identical to open-apple-models' `AgentError.Code` so
 * hosts handle both platforms the same way. [wireName] is the string used
 * on the JSON-RPC wire.
 */
@Serializable
public enum class AgentErrorCode(public val wireName: String) {
    /** The model cannot run (unsupported device, AICore missing, not downloaded, server unreachable). */
    @SerialName("model_unavailable")
    MODEL_UNAVAILABLE("model_unavailable"),

    /** Input or output tripped the safety filters. */
    @SerialName("guardrail_violation")
    GUARDRAIL_VIOLATION("guardrail_violation"),

    /** The model refused to produce the requested content. */
    @SerialName("refusal")
    REFUSAL("refusal"),

    /** The prompt does not fit the model's input limit. */
    @SerialName("context_size_exceeded")
    CONTEXT_SIZE_EXCEEDED("context_size_exceeded"),

    /** A quota was hit (on Android: AICore's per-app or battery quota, or background use). */
    @SerialName("rate_limited")
    RATE_LIMITED("rate_limited"),

    /** The prompt's language is not supported. */
    @SerialName("unsupported_language")
    UNSUPPORTED_LANGUAGE("unsupported_language"),

    /** A schema or tool definition cannot be used. */
    @SerialName("invalid_schema")
    INVALID_SCHEMA("invalid_schema"),

    /** A tool failed in a way that aborted the turn. */
    @SerialName("tool_failed")
    TOOL_FAILED("tool_failed"),

    /** The turn was cancelled. */
    @SerialName("cancelled")
    CANCELLED("cancelled"),

    /** The model or session is busy; retry shortly. */
    @SerialName("busy")
    BUSY("busy"),

    /** Invalid input from the caller. */
    @SerialName("invalid_request")
    INVALID_REQUEST("invalid_request"),

    /** Anything else, including output that stayed invalid after repair. */
    @SerialName("generation_failed")
    GENERATION_FAILED("generation_failed"),
    ;

    public companion object {
        /** The code with the given [wireName], or `null`. */
        public fun fromWireName(wireName: String): AgentErrorCode? = entries.firstOrNull { it.wireName == wireName }
    }
}

/**
 * The single error type thrown by [Agent], [AgentRun] and well-behaved
 * [LanguageModel]s.
 *
 * @property code The category, for hosts to react to.
 * @property retryAfter For [AgentErrorCode.RATE_LIMITED] and [AgentErrorCode.BUSY]: how long to wait before retrying, when known.
 */
public class AgentError(
    public val code: AgentErrorCode,
    message: String,
    public val retryAfter: Duration? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    override val message: String get() = super.message ?: ""

    override fun toString(): String = "${code.wireName}: $message"

    public companion object {
        /**
         * Normalizes any throwable into an [AgentError]: an [AgentError] is
         * returned as is, cancellation becomes [AgentErrorCode.CANCELLED], a
         * coroutine timeout and anything unknown become
         * [AgentErrorCode.GENERATION_FAILED], and a malformed schema becomes
         * [AgentErrorCode.INVALID_SCHEMA].
         */
        public fun from(error: Throwable): AgentError = when (error) {
            is AgentError -> error
            is TimeoutCancellationException -> AgentError(AgentErrorCode.GENERATION_FAILED, "Timed out: ${error.message}", cause = error)
            is CancellationException -> AgentError(AgentErrorCode.CANCELLED, "The turn was cancelled.", cause = error)
            is InvalidSchemaException -> AgentError(AgentErrorCode.INVALID_SCHEMA, error.message ?: "Invalid schema.", cause = error)
            else -> AgentError(AgentErrorCode.GENERATION_FAILED, error.message ?: error::class.simpleName ?: "Unknown error.", cause = error)
        }
    }
}

/**
 * Retries for transient model failures, with exponential backoff.
 *
 * The agent retries a single model step, never a whole turn, so a tool that
 * already ran never runs twice. Retried by default: [AgentErrorCode.GENERATION_FAILED],
 * [AgentErrorCode.BUSY] and [AgentErrorCode.RATE_LIMITED] (unless the
 * server asks to wait longer than [maxRetryAfter]). Guardrail violations only
 * with [retriesGuardrailViolations]: the model samples differently each time,
 * so a false positive may pass.
 *
 * @property maxAttempts Total attempts per model step, including the first. `1` disables retries.
 * @property initialDelay Delay before the first retry; doubled for each further retry.
 */
public data class RetryPolicy(
    public val maxAttempts: Int = 3,
    public val initialDelay: Duration = Duration.parse("300ms"),
    public val retriesGuardrailViolations: Boolean = false,
    public val maxRetryAfter: Duration = Duration.parse("5s"),
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1." }
        require(!initialDelay.isNegative()) { "initialDelay must not be negative." }
    }

    /** Whether [error] is worth another attempt. */
    public fun shouldRetry(error: AgentError): Boolean = when (error.code) {
        AgentErrorCode.GENERATION_FAILED, AgentErrorCode.BUSY -> true
        AgentErrorCode.RATE_LIMITED -> error.retryAfter?.let { it <= maxRetryAfter } ?: true
        AgentErrorCode.GUARDRAIL_VIOLATION -> retriesGuardrailViolations
        else -> false
    }

    public companion object {
        /** The default policy: three attempts starting at 300 ms. */
        public val Default: RetryPolicy = RetryPolicy()

        /** No retries. */
        public val None: RetryPolicy = RetryPolicy(maxAttempts = 1)
    }
}
