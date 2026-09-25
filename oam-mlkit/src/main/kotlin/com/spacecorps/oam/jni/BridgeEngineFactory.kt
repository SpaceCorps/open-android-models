package com.spacecorps.oam.jni

import android.content.Context
import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentErrorCode
import com.spacecorps.oam.GenerationChunk
import com.spacecorps.oam.GenerationRequest
import com.spacecorps.oam.LanguageModel
import com.spacecorps.oam.ModelAvailability
import com.spacecorps.oam.ModelCapabilities
import com.spacecorps.oam.bridge.BridgeConfiguration
import com.spacecorps.oam.bridge.BridgeEngine
import com.spacecorps.oam.mlkit.GeminiNanoErrors
import com.spacecorps.oam.mlkit.GeminiNanoModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The default [MessageEngineFactory] of [OamJni]: every bridge runs
 * oam-bridge's [BridgeEngine], the JSON-RPC 2.0 protocol v1.0 shared with
 * open-apple-models (`session/…`, `tool/call`, `npc/…`, `decision/…`,
 * `world/…`, `content/generate`, scripted models), over Gemini Nano.
 *
 * All bridges created by one factory share one system model, created on
 * first use: creating a bridge never touches ML Kit, and if the ML Kit client
 * cannot be created, `"system"` requests fail with `model_unavailable` while
 * scripted models keep working.
 *
 * ```kotlin
 * // In Application.onCreate, before the host calls OamJni.create:
 * OamJni.engineFactory = BridgeEngineFactory(
 *     systemModel = { GeminiNanoModel(GeminiNanoOptions(preference = GeminiNanoOptions.Preference.FAST)) },
 *     configure = { model -> BridgeConfiguration(systemModel = model, maxSessions = 16) },
 * )
 * ```
 *
 * @param systemModel Creates the model behind `"model": "system"` (called once, lazily).
 * @param configure Builds each engine's configuration around the shared system model.
 */
public class BridgeEngineFactory(
    systemModel: () -> LanguageModel = { GeminiNanoModel() },
    private val configure: (systemModel: LanguageModel) -> BridgeConfiguration = { model -> BridgeConfiguration(systemModel = model) },
) : MessageEngineFactory {
    /** The shared system model (created on first use). */
    public val systemModel: LanguageModel = LazyLanguageModel(systemModel)

    override fun create(context: Context, output: MessageSink): MessageEngine =
        BridgeMessageEngine(BridgeEngine(configure(systemModel)) { line -> output.deliver(line) })
}

/** Adapts a [BridgeEngine] to the line-oriented [MessageEngine] that [BridgeHost] runs. */
public class BridgeMessageEngine(
    /** The engine. */
    public val engine: BridgeEngine,
) : MessageEngine {
    override fun receive(line: String) {
        engine.receive(line)
    }

    /** Cancels the engine's work; it delivers nothing afterwards. */
    override fun close() {
        engine.close()
    }
}

/**
 * A [LanguageModel] created on first use. If creating it fails (the ML Kit
 * client is unavailable), availability reports `unavailable` and generation
 * fails with [AgentErrorCode.MODEL_UNAVAILABLE].
 */
internal class LazyLanguageModel(create: () -> LanguageModel) : LanguageModel {
    private val delegate: Result<LanguageModel> by lazy {
        try {
            Result.success(create())
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun unavailable(error: Throwable) =
        AgentError(AgentErrorCode.MODEL_UNAVAILABLE, "The on-device model could not be created: ${error.message ?: error::class.simpleName}", cause = error)

    override val capabilities: ModelCapabilities get() = delegate.getOrNull()?.capabilities ?: ModelCapabilities()

    override suspend fun availability(): ModelAvailability = delegate.fold(
        onSuccess = { it.availability() },
        onFailure = { ModelAvailability.Unavailable(GeminiNanoErrors.Reasons.UNKNOWN, unavailable(it).message) },
    )

    override suspend fun countTokens(text: String): Int? = delegate.getOrNull()?.countTokens(text)

    override fun generate(request: GenerationRequest): Flow<GenerationChunk> = delegate.fold(
        onSuccess = { it.generate(request) },
        onFailure = { error -> flow { throw unavailable(error) } },
    )

    override suspend fun prewarm(systemInstruction: String?, promptPrefix: String?) {
        delegate.getOrNull()?.prewarm(systemInstruction, promptPrefix)
    }
}
