package com.spacecorps.oam.testing

import com.spacecorps.oam.EmptyJsonObject
import com.spacecorps.oam.GenerationChunk
import com.spacecorps.oam.GenerationKind
import com.spacecorps.oam.GenerationRequest
import com.spacecorps.oam.LanguageModel
import com.spacecorps.oam.ModelAvailability
import com.spacecorps.oam.ModelCapabilities
import com.spacecorps.oam.TokenUsage
import com.spacecorps.oam.FinishReason
import com.spacecorps.oam.estimateTokens
import com.spacecorps.oam.jsonObjectOf
import com.spacecorps.oam.toJsonString
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration

/**
 * A deterministic [LanguageModel] that plays back a script, one step per
 * model request, for testing agent logic (tool loops, NPC flows, bridges) and
 * for running a game without a Gemini Nano device.
 *
 * ```kotlin
 * val model = ScriptedLanguageModel(
 *     ScriptedLanguageModel.Step.call("check_menu"),        // decide step
 *     ScriptedLanguageModel.Step.respond(),                 // decide step
 *     ScriptedLanguageModel.Step.Text("Ale is 3 gold."),    // reply
 * )
 * val agent = Agent(model, tools = listOf(menu))
 * agent.respond("What's on tap?")
 * assertEquals(GenerationKind.DECIDE, model.requests[0].kind)
 * ```
 *
 * @param steps The script.
 * @param fallback Played when the script runs out.
 * @param capabilities Reported capabilities.
 * @param availability Reported availability.
 * @param recordsRequests Whether [requests] are recorded (turn off for long-running scripted sessions).
 * @param countsTokens Whether [countTokens] answers (with an estimate) or returns `null`.
 */
public class ScriptedLanguageModel(
    steps: List<Step> = emptyList(),
    public val fallback: Step = Step.Text("(script exhausted)"),
    override val capabilities: ModelCapabilities = ModelCapabilities(),
    availability: ModelAvailability = ModelAvailability.Available,
    public val recordsRequests: Boolean = true,
    private val countsTokens: Boolean = false,
) : LanguageModel {
    /** Convenience constructor from steps. */
    public constructor(vararg steps: Step) : this(steps.toList())

    /** One scripted model response. */
    public sealed interface Step {
        /** Streams [text] in about [chunks] pieces. */
        public data class Text(public val text: String, public val chunks: Int = 3) : Step

        /** Answers with [value] as compact JSON (for structured steps). */
        public data class Json(public val value: JsonElement) : Step

        /**
         * Answers with a step envelope `{"action": action, "arguments": arguments}`
         * (for decide steps; `action` is a tool name or `"respond"`).
         */
        public data class Envelope(public val action: String, public val arguments: JsonObject = EmptyJsonObject) : Step

        /** Throws [error] from the model. */
        public data class Fail(public val error: Throwable) : Step

        /** Waits [duration], then plays [then] (for cancellation and timeout tests). */
        public data class Delay(public val duration: Duration, public val then: Step) : Step

        /** Chooses the step from the request. */
        public class Dynamic(public val decide: (GenerationRequest) -> Step) : Step

        public companion object {
            /** A decide step that chooses to reply. */
            public fun respond(): Step = Envelope("respond")

            /** A decide step that calls [tool] with [arguments] (pairs go through [jsonObjectOf]). */
            public fun call(tool: String, vararg arguments: Pair<String, Any?>): Step = Envelope(tool, jsonObjectOf(*arguments))
        }
    }

    private val lock = Any()
    private val queue = ArrayDeque(steps)
    private val recorded = ArrayList<GenerationRequest>()

    /** The availability reported by [availability]. */
    @Volatile
    public var currentAvailability: ModelAvailability = availability

    /** Requests received so far, in order. */
    public val requests: List<GenerationRequest> get() = synchronized(lock) { recorded.toList() }

    /** Steps not yet played. */
    public val remainingSteps: Int get() = synchronized(lock) { queue.size }

    /** Appends steps to the script. */
    public fun append(vararg steps: Step) {
        synchronized(lock) { queue.addAll(steps) }
    }

    override suspend fun availability(): ModelAvailability = currentAvailability

    override suspend fun countTokens(text: String): Int? = if (countsTokens) estimateTokens(text) else null

    override fun generate(request: GenerationRequest): Flow<GenerationChunk> = flow {
        val step = synchronized(lock) {
            if (recordsRequests) recorded += request
            queue.removeFirstOrNull() ?: fallback
        }
        play(step, request)
    }

    private suspend fun FlowCollector<GenerationChunk>.play(step: Step, request: GenerationRequest) {
        when (step) {
            is Step.Text -> stream(step.text, step.chunks, request)
            is Step.Json -> stream(step.value.toJsonString(), 1, request)
            is Step.Envelope -> stream(jsonObjectOf("action" to step.action, "arguments" to step.arguments).toJsonString(), 1, request)
            is Step.Fail -> throw step.error
            is Step.Delay -> {
                delay(step.duration)
                play(step.then, request)
            }
            is Step.Dynamic -> play(step.decide(request), request)
        }
    }

    private suspend fun FlowCollector<GenerationChunk>.stream(text: String, chunks: Int, request: GenerationRequest) {
        val pieces = split(text, chunks.coerceAtLeast(1))
        var sent = ""
        pieces.forEachIndexed { index, piece ->
            sent += piece
            val last = index == pieces.lastIndex
            emit(
                GenerationChunk(
                    delta = piece,
                    text = sent,
                    isFinal = last,
                    usage = if (last) TokenUsage(estimateTokens(request.systemInstruction.orEmpty() + request.fullPrompt), 0, estimateTokens(text)) else null,
                    finishReason = if (last) FinishReason.STOP else null,
                ),
            )
        }
        if (pieces.isEmpty()) {
            emit(GenerationChunk("", "", isFinal = true, usage = TokenUsage(estimateTokens(request.fullPrompt), 0, 0), finishReason = FinishReason.STOP))
        }
    }

    private fun split(text: String, chunks: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        if (chunks <= 1 || text.length <= chunks) return listOf(text)
        val size = (text.length + chunks - 1) / chunks
        return text.chunked(size)
    }

    public companion object {
        /** Requests of the given [kind] among [requests]. */
        public fun List<GenerationRequest>.ofKind(kind: GenerationKind): List<GenerationRequest> = filter { it.kind == kind }
    }
}
