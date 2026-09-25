package com.spacecorps.oam.testing

import com.spacecorps.oam.EmptyJsonObject
import com.spacecorps.oam.FinishReason
import com.spacecorps.oam.GenerationChunk
import com.spacecorps.oam.GenerationKind
import com.spacecorps.oam.GenerationRequest
import com.spacecorps.oam.LanguageModel
import com.spacecorps.oam.ModelAvailability
import com.spacecorps.oam.ModelCapabilities
import com.spacecorps.oam.TokenUsage
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
 * A deterministic [LanguageModel] that plays back a script, for testing
 * agent logic (tool loops, NPC flows, bridges) and for running a game
 * without a Gemini Nano device.
 *
 * Scripts come in two [styles][ScriptStyle]:
 *
 * - [ScriptStyle.ENVELOPE] (default): every model request plays the next
 *   step verbatim, so a script lists the raw outputs of each step,
 *   including the agent's decide steps:
 *   ```kotlin
 *   val model = ScriptedLanguageModel(
 *       Step.call("check_menu"),           // decide: call a tool
 *       Step.respond(),                    // decide: reply
 *       Step.Text("Ale is 3 gold."),       // the reply
 *   )
 *   ```
 * - [ScriptStyle.NATIVE]: the script describes what a model with native tool
 *   calling does, exactly like open-apple-models' scripted model (and its
 *   JSON-RPC `{"type": "scripted"}` model): a tool round takes one step and
 *   the answer another. The agent's decide steps are answered from the
 *   script without consuming answers:
 *   ```kotlin
 *   val model = ScriptedLanguageModel(
 *       listOf(Step.ToolCalls(Step.ScriptedCall("check_menu")), Step.Text("Ale is 3 gold.")),
 *       style = ScriptStyle.NATIVE,
 *   )
 *   ```
 *
 * @param steps The script.
 * @param fallback Played when the script runs out.
 * @param capabilities Reported capabilities.
 * @param availability Reported availability (changeable through [currentAvailability]).
 * @param recordsRequests Whether [requests] are recorded (turn off for long-running scripted sessions).
 * @param countsTokens Whether [countTokens] answers (with an estimate) or returns `null`.
 * @param style How steps map to model requests.
 */
public class ScriptedLanguageModel(
    steps: List<Step> = emptyList(),
    public val fallback: Step = Step.Text("(script exhausted)"),
    override val capabilities: ModelCapabilities = ModelCapabilities(),
    availability: ModelAvailability = ModelAvailability.Available,
    public val recordsRequests: Boolean = true,
    private val countsTokens: Boolean = false,
    public val style: ScriptStyle = ScriptStyle.ENVELOPE,
) : LanguageModel {
    /** Convenience constructor for an [ScriptStyle.ENVELOPE] script. */
    public constructor(vararg steps: Step) : this(steps.toList())

    /** How a script's steps map to the agent's model requests. */
    public enum class ScriptStyle {
        /** Each request plays the next step verbatim. */
        ENVELOPE,

        /**
         * Steps describe a model with native tool calling: [Step.ToolCalls]
         * answers decide steps (one call per decide step), and answers
         * ([Step.Text], [Step.Json], [Step.Template]) wait for the reply step
         * while decide steps get `respond`.
         */
        NATIVE,
    }

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

        /**
         * A tool round of a natively tool-calling model. In an envelope-style
         * script it plays as the envelope of its first call.
         */
        public data class ToolCalls(public val calls: List<ScriptedCall>) : Step {
            /** A round of [calls]. */
            public constructor(vararg calls: ScriptedCall) : this(calls.toList())
        }

        /** One call of a [ToolCalls] step. */
        public data class ScriptedCall(public val name: String, public val arguments: JsonObject = EmptyJsonObject)

        /**
         * Answers with [template], substituting `{prompt}` (the user's prompt),
         * `{toolOutput}` (the latest tool output of the turn) and
         * `{toolOutputs}` (all of them, one per line).
         */
        public data class Template(public val template: String, public val chunks: Int = 3) : Step

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
            if (style == ScriptStyle.NATIVE) nextNative(request) else queue.removeFirstOrNull() ?: fallback
        }
        play(step, request)
    }

    /** Picks the step for [request] in a native-style script. Called under [lock]. */
    private fun nextNative(request: GenerationRequest): Step {
        val deciding = request.kind == GenerationKind.DECIDE || request.kind == GenerationKind.TOOL_ARGUMENTS
        if (!deciding) {
            // Tool rounds cannot happen in a reply step: skip any left over.
            while (queue.firstOrNull() is Step.ToolCalls) queue.removeFirst()
            return queue.removeFirstOrNull() ?: fallback
        }
        var next = queue.firstOrNull() ?: fallback
        if (next is Step.Dynamic) {
            next = next.decide(request)
            if (queue.isNotEmpty()) queue[0] = next
        }
        val inner = (next as? Step.Delay)?.then ?: next
        return when {
            inner is Step.ToolCalls && inner.calls.isNotEmpty() -> {
                // One call per decide step; the rest of the round stays queued.
                val call = inner.calls.first()
                val rest = inner.calls.drop(1)
                if (queue.isNotEmpty()) {
                    if (rest.isEmpty()) {
                        queue.removeFirst()
                    } else {
                        queue[0] = Step.ToolCalls(rest)
                    }
                }
                val played: Step = if (request.kind == GenerationKind.TOOL_ARGUMENTS) Step.Json(call.arguments) else Step.Envelope(call.name, call.arguments)
                if (next is Step.Delay) Step.Delay(next.duration, played) else played
            }
            inner is Step.Fail -> {
                if (queue.isNotEmpty()) queue.removeFirst()
                next
            }
            // An answer: the model replies without tools. Keep the answer for the reply step.
            request.kind == GenerationKind.TOOL_ARGUMENTS -> Step.Json(EmptyJsonObject)
            else -> Step.respond()
        }
    }

    private suspend fun FlowCollector<GenerationChunk>.play(step: Step, request: GenerationRequest) {
        when (step) {
            is Step.Text -> stream(step.text, step.chunks, request)
            is Step.Json -> stream(step.value.toJsonString(), 1, request)
            is Step.Envelope -> stream(envelope(step.action, step.arguments), 1, request)
            is Step.ToolCalls -> {
                val call = step.calls.firstOrNull()
                stream(if (call == null) envelope("respond", EmptyJsonObject) else envelope(call.name, call.arguments), 1, request)
            }
            is Step.Template -> stream(render(step.template, request), step.chunks, request)
            is Step.Fail -> throw step.error
            is Step.Delay -> {
                delay(step.duration)
                play(step.then, request)
            }
            is Step.Dynamic -> play(step.decide(request), request)
        }
    }

    private fun envelope(action: String, arguments: JsonObject) = jsonObjectOf("action" to action, "arguments" to arguments).toJsonString()

    private fun render(template: String, request: GenerationRequest): String {
        val outputs = request.turn?.toolUses.orEmpty().map { it.output.modelText }
        return template
            .replace("{prompt}", request.turn?.prompt.orEmpty())
            .replace("{toolOutputs}", outputs.joinToString("\n"))
            .replace("{toolOutput}", outputs.lastOrNull().orEmpty())
    }

    private suspend fun FlowCollector<GenerationChunk>.stream(text: String, chunks: Int, request: GenerationRequest) {
        val pieces = split(text, chunks.coerceAtLeast(1))
        val usage = TokenUsage(estimateTokens(request.systemInstruction.orEmpty() + request.fullPrompt), 0, estimateTokens(text))
        if (pieces.isEmpty()) {
            emit(GenerationChunk("", "", isFinal = true, usage = usage, finishReason = FinishReason.STOP))
            return
        }
        var sent = ""
        pieces.forEachIndexed { index, piece ->
            sent += piece
            val last = index == pieces.lastIndex
            emit(GenerationChunk(piece, sent, isFinal = last, usage = if (last) usage else null, finishReason = if (last) FinishReason.STOP else null))
        }
    }

    private fun split(text: String, chunks: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        if (chunks <= 1 || text.length <= chunks) return listOf(text)
        return text.chunked((text.length + chunks - 1) / chunks)
    }
}
