package com.spacecorps.oam.bridge

import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentErrorCode
import com.spacecorps.oam.EmptyJsonObject
import com.spacecorps.oam.testing.ScriptedLanguageModel.Step
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Parses the JSON form of a scripted model, which lets engine developers and
 * CI exercise the whole protocol (streaming, client tools, structured output,
 * errors, cancellation) without a Gemini Nano device.
 *
 * ```json
 * {"type": "scripted",
 *  "steps": [
 *    {"toolCalls": [{"name": "open_gate", "arguments": {"gate": "north"}}]},
 *    {"template": "The gate says: {toolOutput}"}
 *  ],
 *  "fallback": {"text": "..."}}
 * ```
 *
 * Scripts are written exactly as for open-apple-models: each model inference
 * plays the next step, a tool round takes one step and the answer another.
 * Gemini Nano has no native tool calling, so the agent asks the model for a
 * JSON step envelope before each tool call and before the answer; the scripted
 * model answers those decisions from the script
 * ([com.spacecorps.oam.testing.ScriptedLanguageModel.ScriptStyle.NATIVE]): a
 * `toolCalls` step becomes one envelope per call (the calls of a round run one
 * after another), and an answer step makes the decision `respond`.
 *
 * Step forms:
 * - `{"text": "...", "chunks"?: n}`: answer text, streamed in about `n` pieces (default 3).
 * - `{"toolCalls": [{"name", "arguments"?, "id"?}]}`: one tool round. Call ids are
 *   generated on Android; a scripted `id` is ignored.
 * - `{"json": <value>}`: structured output for schema turns.
 * - `{"template": "..."}`: text with `{prompt}`, `{toolOutput}` (the latest tool
 *   output of this turn) and `{toolOutputs}` (all of this turn's, one per line).
 * - `{"error": "<code>", "message"?}`: fail with an agent error code such as
 *   `guardrail_violation`, `refusal`, `context_size_exceeded` or `rate_limited`.
 * - Any step may add `"delayMs": n` to wait first (for cancellation tests).
 */
public object BridgeScript {
    /** The default [step] for a script that ran out. */
    public val DEFAULT_FALLBACK: Step = Step.Text("(script exhausted)")

    /**
     * Parses `{"type": "scripted", "steps": [...], "fallback"?: step}`.
     *
     * @throws BridgeError `invalid_params`, naming the offending step (`model.steps[2]`).
     */
    public fun parse(value: JsonElement, path: String = "model"): BridgeModelSpec.Scripted {
        val obj = value as? JsonObject ?: throw BridgeError.invalidParams("'$path' must be an object.")
        val params = BridgeParams(obj, "$path.")
        val steps = params.optionalArray("steps").orEmpty().mapIndexed { index, step -> step(step, "$path.steps[$index]") }
        val fallback = params["fallback"]?.let { step(it, "$path.fallback") } ?: DEFAULT_FALLBACK
        return BridgeModelSpec.Scripted(steps, fallback)
    }

    /**
     * Parses one step.
     *
     * @throws BridgeError `invalid_params`.
     */
    public fun step(value: JsonElement, path: String): Step {
        val obj = value as? JsonObject ?: throw BridgeError.invalidParams("'$path' must be an object.")
        val params = BridgeParams(obj, "$path.")
        val text = params.optionalString("text")
        val calls = params.optionalArray("toolCalls")
        val template = params.optionalString("template")
        val code = params.optionalString("error")
        val step: Step = when {
            text != null -> Step.Text(text, params.optionalInt("chunks", minimum = 1) ?: DEFAULT_CHUNKS)
            calls != null -> {
                if (calls.isEmpty()) throw BridgeError.invalidParams("'$path.toolCalls' must not be empty.")
                Step.ToolCalls(
                    calls.mapIndexed { index, call ->
                        val callObject = call as? JsonObject ?: throw BridgeError.invalidParams("'$path.toolCalls[$index]' must be an object.")
                        val callParams = BridgeParams(callObject, "$path.toolCalls[$index].")
                        callParams.optionalString("id") // type-checked only: Android generates call ids
                        Step.ScriptedCall(callParams.string("name"), callParams.optionalObject("arguments") ?: EmptyJsonObject)
                    },
                )
            }
            "json" in obj -> Step.Json(obj.getValue("json"))
            template != null -> Step.Template(template, params.optionalInt("chunks", minimum = 1) ?: DEFAULT_CHUNKS)
            code != null -> {
                val agentCode = AgentErrorCode.fromWireName(code) ?: throw BridgeError.invalidParams(
                    "'$path.error' must be one of: ${AgentErrorCode.entries.joinToString(", ") { it.wireName }}; got '$code'.",
                )
                Step.Fail(AgentError(agentCode, params.optionalString("message") ?: "Scripted $code."))
            }
            else -> throw BridgeError.invalidParams("'$path' must contain one of 'text', 'toolCalls', 'json', 'template' or 'error'.")
        }
        val delay = params.optionalInt("delayMs", minimum = 0) ?: 0
        return if (delay > 0) Step.Delay(delay.milliseconds, step) else step
    }

    private const val DEFAULT_CHUNKS = 3
}
