package com.spacecorps.oam.bridge

import com.spacecorps.oam.LanguageModel
import com.spacecorps.oam.ModelAvailability
import com.spacecorps.oam.ModelCapabilities
import com.spacecorps.oam.testing.ScriptedLanguageModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Severity of a bridge log message. */
public enum class BridgeLogLevel {
    /** Details such as dropped messages. */
    DEBUG,

    /** Normal events. */
    INFO,

    /** Unexpected but handled input, such as a response to an unknown request. */
    WARNING,

    /** Failures of the host integration, such as a throwing `send` callback. */
    ERROR,
}

/**
 * Which language model a session (or NPC, decision, content request) runs
 * on, as requested by the client with the `model` parameter.
 */
public sealed interface BridgeModelSpec {
    /** A short name for listings: `"system"`, `"scripted"` or the custom type. */
    public val kind: String

    /** The device's built-in model (`"system"`, the default): Gemini Nano on Android. */
    public data object System : BridgeModelSpec {
        override val kind: String get() = "system"
    }

    /**
     * A deterministic scripted model (`{"type": "scripted", "steps": [...]}`)
     * for engine development and CI without a Gemini Nano device. See [BridgeScript].
     *
     * @property steps The parsed script, in open-apple-models' native style (a tool round takes one step,
     *   the answer another).
     * @property fallback Played when the script runs out.
     */
    public data class Scripted(
        public val steps: List<ScriptedLanguageModel.Step>,
        public val fallback: ScriptedLanguageModel.Step = ScriptedLanguageModel.Step.Text("(script exhausted)"),
    ) : BridgeModelSpec {
        override val kind: String get() = "scripted"
    }

    /**
     * Any other `{"type": "<name>", ...}` object, resolved by
     * [BridgeConfiguration.modelFactory].
     *
     * @property type The `type` member.
     * @property options The whole `model` value.
     */
    public data class Custom(public val type: String, public val options: JsonElement) : BridgeModelSpec {
        override val kind: String get() = type
    }
}

/**
 * Availability and properties of the system model, as reported by
 * `model/availability` and `initialize`.
 *
 * The first five fields are open-apple-models' shape. Android adds [status]
 * (so a host can offer a download), [detail], [maxOutputTokens] and the
 * download byte counts.
 *
 * @property available Whether requests can run now.
 * @property reason Why not: `device_not_eligible`, `model_not_ready` (downloadable or downloading),
 *   `aicore_unavailable`, `needs_system_update`, `not_enough_disk_space` or `unknown`.
 * @property contextSize The input token budget per request (Gemini Nano: about 4000).
 * @property variant The model name, when known (for example the nano version).
 * @property supportedLanguages BCP-47 languages; empty when the backend cannot say (ML Kit has no query).
 * @property status `available`, `downloadable`, `downloading` or `unavailable`.
 * @property detail A human-readable explanation, when unavailable.
 * @property maxOutputTokens The output token limit per request.
 * @property bytesDownloaded Bytes downloaded so far, while downloading (when known).
 * @property totalBytes The download size, while downloading (when known).
 */
public data class BridgeModelAvailability(
    public val available: Boolean,
    public val reason: String? = null,
    public val contextSize: Int = ModelCapabilities.GEMINI_NANO_MAX_INPUT_TOKENS,
    public val variant: String? = null,
    public val supportedLanguages: List<String> = emptyList(),
    public val status: String = if (available) "available" else "unavailable",
    public val detail: String? = null,
    public val maxOutputTokens: Int? = null,
    public val bytesDownloaded: Long? = null,
    public val totalBytes: Long? = null,
) {
    /** The `model/availability` result object. */
    public fun toJson(): JsonObject {
        val members = linkedMapOf<String, JsonElement>("available" to JsonPrimitive(available))
        reason?.let { members["reason"] = JsonPrimitive(it) }
        members["contextSize"] = JsonPrimitive(contextSize)
        variant?.let { members["variant"] = JsonPrimitive(it) }
        members["supportedLanguages"] = kotlinx.serialization.json.JsonArray(supportedLanguages.map(::JsonPrimitive))
        members["status"] = JsonPrimitive(status)
        detail?.let { members["detail"] = JsonPrimitive(it) }
        maxOutputTokens?.let { members["maxOutputTokens"] = JsonPrimitive(it) }
        bytesDownloaded?.let { members["bytesDownloaded"] = JsonPrimitive(it) }
        totalBytes?.let { members["totalBytes"] = JsonPrimitive(it) }
        return JsonObject(members)
    }

    public companion object {
        /** The reason reported while the model is downloadable or downloading. */
        public const val MODEL_NOT_READY: String = "model_not_ready"

        /**
         * Reads [model]'s availability and capabilities. Failures of the check
         * itself are reported as unavailable with reason `unknown`.
         */
        public suspend fun of(model: LanguageModel): BridgeModelAvailability {
            val availability = try {
                model.availability()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ModelAvailability.Unavailable("unknown", error.message)
            }
            val capabilities = model.capabilities
            val base = BridgeModelAvailability(
                available = availability.isAvailable,
                contextSize = capabilities.maxInputTokens,
                variant = capabilities.modelName,
                maxOutputTokens = capabilities.maxOutputTokens,
            )
            return when (availability) {
                ModelAvailability.Available -> base
                ModelAvailability.Downloadable -> base.copy(reason = MODEL_NOT_READY, status = "downloadable")
                is ModelAvailability.Downloading -> base.copy(
                    reason = MODEL_NOT_READY,
                    status = "downloading",
                    bytesDownloaded = availability.bytesDownloaded,
                    totalBytes = availability.totalBytes,
                )
                is ModelAvailability.Unavailable -> base.copy(reason = availability.reason, status = "unavailable", detail = availability.detail)
            }
        }

        /** No system model is configured (a JVM host without Gemini Nano). */
        public val NO_SYSTEM_MODEL: BridgeModelAvailability = BridgeModelAvailability(
            available = false,
            reason = "unknown",
            detail = "No system model is configured on this bridge; use scripted models or configure BridgeConfiguration.systemModel.",
        )
    }
}

/**
 * Settings for a [BridgeEngine].
 *
 * ```kotlin
 * val engine = BridgeEngine(BridgeConfiguration(systemModel = GeminiNanoModel())) { line -> host.deliver(line) }
 * ```
 *
 * @property systemModel The model behind `"model": "system"` (the default), shared by every session, NPC
 *   and decision: `GeminiNanoModel` on Android. `null` makes system-model turns fail with
 *   `model_unavailable` (scripted models still work).
 * @property modelFactory Creates the model for a `model` parameter. The default ([defaultModelFactory])
 *   returns [systemModel] for [BridgeModelSpec.System], a native-style [ScriptedLanguageModel] for
 *   [BridgeModelSpec.Scripted] and rejects custom types. Replace it to route custom types (for example an
 *   `OpenAICompatibleModel` for development).
 * @property modelAvailability Reports the system model's availability for `initialize` and
 *   `model/availability`. Runs in the order-preserving request loop, so it must be quick.
 * @property maxSessions Most live sessions; `session/create` fails beyond it.
 * @property allowsScriptedModels Whether clients may use scripted models.
 * @property defaultToolTimeout Time limit for client-executed tools (`tool/call` round trips) when a
 *   session, NPC or tool sets none. `null` waits indefinitely.
 * @property logger Receives diagnostics (unknown response ids, dropped messages, failing callbacks).
 * @property extensions Method sets registered after the built-in methods. Defaults to
 *   [standardExtensions] (the game methods); pass an empty list for the built-ins only.
 * @property onShutdown Called once after the response to `shutdown` has been delivered.
 * @property dispatcher Where the engine's work runs.
 */
public class BridgeConfiguration(
    public val systemModel: LanguageModel? = null,
    public val modelFactory: (BridgeModelSpec) -> LanguageModel = defaultModelFactory(systemModel),
    public val modelAvailability: suspend () -> BridgeModelAvailability = defaultAvailability(systemModel),
    public val maxSessions: Int = 64,
    public val allowsScriptedModels: Boolean = true,
    public val defaultToolTimeout: Duration? = 120.seconds,
    public val logger: ((BridgeLogLevel, String) -> Unit)? = null,
    public val extensions: List<BridgeExtension> = standardExtensions(),
    public val onShutdown: (() -> Unit)? = null,
    public val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    init {
        require(maxSessions >= 1) { "maxSessions must be at least 1." }
        require(defaultToolTimeout == null || !defaultToolTimeout.isNegative()) { "defaultToolTimeout must not be negative." }
    }

    public companion object {
        /**
         * The extensions every transport serves by default: a fresh
         * [GameExtension] (`npc/…`, `decision/…`, `world/…`, `content/generate`).
         * Each engine owns its extensions' state, so this returns new instances.
         */
        public fun standardExtensions(): List<BridgeExtension> = listOf(GameExtension())

        /**
         * The default model factory: [systemModel] for `"system"`, a scripted
         * model for scripts, and an `invalid_params` error for custom types.
         */
        public fun defaultModelFactory(systemModel: LanguageModel?): (BridgeModelSpec) -> LanguageModel = { spec ->
            when (spec) {
                BridgeModelSpec.System -> systemModel ?: throw BridgeError.named(
                    BridgeError.MODEL_UNAVAILABLE, "model_unavailable",
                    "No system model is configured on this bridge; use a scripted model or configure BridgeConfiguration.systemModel.",
                )
                is BridgeModelSpec.Scripted -> scriptedModel(spec)
                is BridgeModelSpec.Custom -> throw BridgeError.invalidParams("Unknown model type '${spec.type}'. Supported: \"system\", \"scripted\".")
            }
        }

        /** A native-style scripted model for [spec] that does not record requests. */
        public fun scriptedModel(spec: BridgeModelSpec.Scripted): ScriptedLanguageModel = ScriptedLanguageModel(
            steps = spec.steps,
            fallback = spec.fallback,
            recordsRequests = false,
            style = ScriptedLanguageModel.ScriptStyle.NATIVE,
        )

        /** Availability of [systemModel], or [BridgeModelAvailability.NO_SYSTEM_MODEL]. */
        public fun defaultAvailability(systemModel: LanguageModel?): suspend () -> BridgeModelAvailability = {
            if (systemModel == null) BridgeModelAvailability.NO_SYSTEM_MODEL else BridgeModelAvailability.of(systemModel)
        }
    }
}
