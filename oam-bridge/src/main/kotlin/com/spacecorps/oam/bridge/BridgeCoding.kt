package com.spacecorps.oam.bridge

import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentEvent
import com.spacecorps.oam.AgentResponse
import com.spacecorps.oam.AgentTool
import com.spacecorps.oam.InvalidSchemaException
import com.spacecorps.oam.JsonSchema
import com.spacecorps.oam.ModelStep
import com.spacecorps.oam.StepKind
import com.spacecorps.oam.TokenUsage
import com.spacecorps.oam.ToolCall
import com.spacecorps.oam.ToolCallingMode
import com.spacecorps.oam.ToolChoice
import com.spacecorps.oam.ToolDefinition
import com.spacecorps.oam.ToolOutput
import com.spacecorps.oam.ToolPolicy
import com.spacecorps.oam.ToolRecord
import com.spacecorps.oam.Transcript
import com.spacecorps.oam.boolValue
import com.spacecorps.oam.isJsonNull
import com.spacecorps.oam.objectValue
import com.spacecorps.oam.stringValue
import com.spacecorps.oam.toJsonString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The JSON shapes shared by bridge methods: encoders for agent results and
 * events, and decoders for tool definitions, policies, schemas, transcripts
 * and `model` parameters. Extensions use these so every method speaks the
 * same dialect.
 */
public object BridgeCoding {
    // MARK: Encoding

    /** `{"id", "name", "arguments"}`. */
    public fun json(call: ToolCall): JsonObject = obj("id" to JsonPrimitive(call.id), "name" to JsonPrimitive(call.name), "arguments" to call.arguments)

    /** Text outputs and error messages become strings; JSON outputs stay JSON. */
    public fun json(output: ToolOutput): JsonElement = when (output) {
        is ToolOutput.Text -> JsonPrimitive(output.text)
        is ToolOutput.Json -> output.value
        is ToolOutput.Error -> JsonPrimitive(output.message)
    }

    /** `{"call", "output", "isError", "durationSeconds"}` (milliseconds precision). */
    public fun json(record: ToolRecord): JsonObject = obj(
        "call" to json(record.call),
        "output" to json(record.output),
        "isError" to JsonPrimitive(record.output.isError),
        "durationSeconds" to number((record.durationSeconds * 1000).roundToLong() / 1000.0),
    )

    /** `{"inputTokens", "cachedInputTokens", "outputTokens", "totalTokens"}`. */
    public fun json(usage: TokenUsage): JsonObject = obj(
        "inputTokens" to JsonPrimitive(usage.inputTokens),
        "cachedInputTokens" to JsonPrimitive(usage.cachedInputTokens),
        "outputTokens" to JsonPrimitive(usage.outputTokens),
        "totalTokens" to JsonPrimitive(usage.totalTokens),
    )

    /**
     * `{"index", "completedToolRounds", "toolCallingMode", "enabledTools",
     * "trimmedEntries", "kind", "isRepair"}`. The first five are
     * open-apple-models' shape; `kind` (`decide`, `toolArguments`, `respond`,
     * `structured`) and `isRepair` are Android additions, because each tool
     * decision is a model step of its own there.
     */
    public fun json(step: ModelStep): JsonObject = obj(
        "index" to JsonPrimitive(step.index),
        "completedToolRounds" to JsonPrimitive(step.completedToolRounds),
        "toolCallingMode" to JsonPrimitive(name(step.toolCallingMode)),
        "enabledTools" to JsonArray(step.enabledTools.map(::JsonPrimitive)),
        "trimmedEntries" to JsonPrimitive(step.trimmedEntries),
        "kind" to JsonPrimitive(name(step.kind)),
        "isRepair" to JsonPrimitive(step.isRepair),
    )

    /**
     * `{"text", "structured"?, "toolCalls", "usage", "steps"}`: the body of a
     * turn result. Callers put their own id field first.
     */
    public fun json(response: AgentResponse): JsonObject {
        val members = linkedMapOf<String, JsonElement>("text" to JsonPrimitive(response.text))
        response.structured?.let { members["structured"] = it }
        members["toolCalls"] = JsonArray(response.toolCalls.map(::json))
        members["usage"] = json(response.usage)
        members["steps"] = JsonArray(response.steps.map(::json))
        return JsonObject(members)
    }

    /**
     * The `event` payload of a streaming notification, or `null` for
     * [AgentEvent.Completed] (delivered as the response instead).
     */
    public fun json(event: AgentEvent): JsonObject? = when (event) {
        is AgentEvent.ModelStep -> obj("type" to JsonPrimitive("modelStep"), "step" to json(event.step))
        is AgentEvent.Text -> obj(
            "type" to JsonPrimitive("text"),
            "delta" to JsonPrimitive(event.delta),
            "text" to JsonPrimitive(event.text),
            "isReset" to JsonPrimitive(event.isReset),
        )
        is AgentEvent.Partial -> obj("type" to JsonPrimitive("partial"), "value" to event.value)
        is AgentEvent.ToolCallStarted -> toolStarted(event.call, client = false)
        is AgentEvent.ToolCallRequested -> toolStarted(event.call, client = true)
        is AgentEvent.ToolCallCompleted -> obj("type" to JsonPrimitive("toolCallCompleted"), "record" to json(event.record))
        is AgentEvent.Completed -> null
    }

    /** `{"type": "toolCallStarted", "call", "execution": "client" | "local"}`. */
    public fun toolStarted(call: ToolCall, client: Boolean): JsonObject = obj(
        "type" to JsonPrimitive("toolCallStarted"),
        "call" to json(call),
        "execution" to JsonPrimitive(if (client) "client" else "local"),
    )

    /**
     * A transcript as a JSON object (`{"type": "open-android-models.Transcript",
     * "version", "transcript"}`), suitable for saving and for `history` in `session/create`.
     */
    public fun json(transcript: Transcript): JsonElement = transcript.toJson()

    /** A tool as `{"name", "description", "parameters", "execution"}`. */
    public fun json(tool: AgentTool): JsonObject = obj(
        "name" to JsonPrimitive(tool.name),
        "description" to JsonPrimitive(tool.description),
        "parameters" to tool.parameters.json,
        "execution" to JsonPrimitive(if (tool.isExternal) "client" else "local"),
    )

    /** A number of seconds, written without a fraction when it is whole (`30`, `0.25`). */
    public fun number(value: Double): JsonPrimitive =
        if (value.isFinite() && value == Math.rint(value) && kotlin.math.abs(value) < 1e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)

    private fun name(mode: ToolCallingMode): String = when (mode) {
        ToolCallingMode.ALLOWED -> "allowed"
        ToolCallingMode.REQUIRED -> "required"
        ToolCallingMode.DISALLOWED -> "disallowed"
    }

    private fun name(kind: StepKind): String = when (kind) {
        StepKind.DECIDE -> "decide"
        StepKind.TOOL_ARGUMENTS -> "toolArguments"
        StepKind.RESPOND -> "respond"
        StepKind.STRUCTURED -> "structured"
    }

    // MARK: Decoding

    /**
     * Decodes a transcript saved from `session/transcript`. Accepts the
     * transcript object itself or the whole `session/transcript` result.
     *
     * @throws BridgeError `invalid_params`.
     */
    public fun transcript(value: JsonElement, path: String = "history"): Transcript {
        val obj = value as? JsonObject ?: throw BridgeError.invalidParams("'$path' must be a transcript object from session/transcript.")
        val inner = obj["transcript"]
        if (obj["type"]?.stringValue != Transcript.FORMAT && "entries" !in obj) {
            if (inner is JsonObject) return transcript(inner, path)
            throw BridgeError.invalidParams("'$path' is not a valid transcript: pass the 'transcript' value from session/transcript.")
        }
        return try {
            Transcript.fromJson(obj)
        } catch (error: AgentError) {
            throw BridgeError.invalidParams("'$path' is not a valid transcript: ${error.message}")
        }
    }

    /**
     * The instructions and tool definitions saved in a transcript.
     *
     * @property instructions The instruction text, or `null` when there was none.
     * @property tools The tool definitions the model saw.
     */
    public class SavedSetup(public val instructions: String?, public val tools: List<ToolDefinition>)

    /** Reads the instructions and tools saved in [transcript]. */
    public fun savedSetup(transcript: Transcript): SavedSetup =
        SavedSetup(transcript.instructions?.takeIf { it.isNotEmpty() }, transcript.tools)

    /**
     * Parsed tool definitions.
     *
     * @property tools The tools, in definition order.
     * @property warnings Problems worth telling the client about, prefixed with the tool name.
     */
    public class ParsedTools(public val tools: List<AgentTool>, public val warnings: List<String>)

    /**
     * Recreates client-executed tools from definitions saved in a transcript.
     * Definitions that cannot be restored are skipped with a warning.
     */
    public fun clientTools(restoring: List<ToolDefinition>, defaultTimeout: Duration?): ParsedTools {
        val tools = ArrayList<AgentTool>()
        val warnings = ArrayList<String>()
        val names = HashSet<String>()
        for (definition in restoring) {
            if (!names.add(definition.name)) continue
            try {
                tools += AgentTool.external(definition, defaultTimeout)
            } catch (error: AgentError) {
                warnings += "${definition.name}: could not be restored from 'history' (${error.message}); send its definition in 'tools'."
            }
        }
        return ParsedTools(tools, warnings)
    }

    /**
     * `"auto" | "none" | "required" | "explicit" | {"tool": name}`; OpenAI's
     * `{"type": "function", "function": {"name": …}}` is accepted too. A bare
     * tool name is not, so typos are caught.
     *
     * @throws BridgeError `invalid_params`.
     */
    public fun toolChoice(value: JsonElement, path: String = "toolChoice"): ToolChoice {
        value.stringValue?.let { text ->
            return when (text) {
                "auto" -> ToolChoice.Auto
                "none" -> ToolChoice.None
                "required" -> ToolChoice.Required
                "explicit" -> ToolChoice.Explicit
                else -> throw badToolChoice(value, path)
            }
        }
        val obj = value as? JsonObject ?: throw badToolChoice(value, path)
        obj["tool"]?.stringValue?.takeIf { it.isNotEmpty() }?.let { return ToolChoice.Tool(it) }
        obj["function"]?.objectValue?.get("name")?.stringValue?.takeIf { it.isNotEmpty() }?.let { return ToolChoice.Tool(it) }
        throw BridgeError.invalidParams("'$path' object must be {\"tool\": \"<name>\"}.")
    }

    private fun badToolChoice(value: JsonElement, path: String) = BridgeError.invalidParams(
        "'$path' must be \"auto\", \"none\", \"required\", \"explicit\" or {\"tool\": \"<name>\"}; got ${JsonText.shown(value)}.",
    )

    /**
     * Applies `toolChoice`, `maxToolRounds`, `maxToolCalls` and `enabledTools`
     * from [params] on top of [base].
     *
     * @throws BridgeError `invalid_params`.
     */
    public fun toolPolicy(params: BridgeParams, base: ToolPolicy): ToolPolicy {
        var policy = base
        params["toolChoice"]?.let { policy = policy.copy(choice = toolChoice(it, params.name("toolChoice"))) }
        params.optionalInt("maxToolRounds", minimum = 0)?.let { policy = policy.copy(maxToolRounds = it) }
        params.optionalInt("maxToolCalls", minimum = 0)?.let { policy = policy.copy(maxToolCalls = it) }
        params.optionalStrings("enabledTools")?.let { policy = policy.copy(enabledTools = it.toSet()) }
        return policy
    }

    /**
     * A JSON Schema object (or `true`). Schemas sent as JSON text are tolerated.
     *
     * @throws BridgeError `invalid_params`.
     */
    public fun schema(value: JsonElement, path: String = "schema"): JsonSchema {
        val parsed = value.stringValue?.let { text ->
            try {
                JsonText.parse(text)
            } catch (_: IllegalArgumentException) {
                throw BridgeError.invalidParams("'$path' must be a JSON Schema object.")
            }
        } ?: value
        if (parsed is JsonObject) return JsonSchema(parsed)
        if (parsed.boolValue == true) return JsonSchema.any
        throw BridgeError.invalidParams("'$path' must be a JSON Schema object.")
    }

    /**
     * Checks that [schema] is usable, mapping problems to `invalid_schema`
     * (with `data.path` = [path] and `data.schemaPath`).
     *
     * @return Warnings about keywords that are ignored.
     * @throws BridgeError `invalid_schema`.
     */
    public fun checkSchema(schema: JsonSchema, path: String): List<String> = try {
        schema.check()
    } catch (error: InvalidSchemaException) {
        throw BridgeError.invalidSchema(error, path)
    }

    /**
     * Parses client tool definitions:
     * `[{"name", "description", "parameters"?, "execution"?: "client", "timeoutSeconds"?}]`.
     * OpenAI's `{"type": "function", "function": {…}}` wrapper is accepted.
     *
     * @param defaultTimeout Applied to tools without `timeoutSeconds`.
     * @throws BridgeError `invalid_params` or `invalid_schema`.
     */
    public fun tools(value: JsonElement, defaultTimeout: Duration?, path: String = "tools"): ParsedTools {
        val array = value as? JsonArray ?: throw BridgeError.invalidParams("'$path' must be an array of tool definitions.")
        val tools = ArrayList<AgentTool>()
        val warnings = ArrayList<String>()
        val names = HashSet<String>()
        for ((index, element) in array.withIndex()) {
            val elementPath = "$path[$index]"
            val definition = (element as? JsonObject)?.get("function")?.takeIf { it is JsonObject } ?: element
            val obj = definition as? JsonObject ?: throw BridgeError.invalidParams("'$elementPath' must be an object.")
            val params = BridgeParams(obj, "$elementPath.")
            val name = params.string("name")
            if (!isValidToolName(name)) {
                throw BridgeError.invalidParams("'$elementPath.name' must be 1-64 characters of letters, digits, '_', '-' or '.'; got '$name'.")
            }
            if (name in AgentTool.RESERVED_NAMES) {
                throw BridgeError.invalidParams("'$elementPath.name' '$name' is reserved for the model's reply step; choose another name.")
            }
            if (!names.add(name)) throw BridgeError.invalidParams("Duplicate tool name '$name'.")
            val description = params.optionalString("description") ?: ""
            if (description.isEmpty()) warnings += "$name: no description; the model may not know when to call it."
            val execution = params.optionalString("execution") ?: "client"
            if (execution != "client") {
                throw BridgeError.invalidParams(
                    "'$elementPath.execution' must be \"client\" (the engine executes the tool via tool/call); got '$execution'.",
                )
            }
            val parameters = params["parameters"]?.let { schema(it, "$elementPath.parameters") } ?: JsonSchema.empty
            checkSchema(parameters, "$elementPath.parameters")
            val timeout = params.optionalSeconds("timeoutSeconds")?.let(::timeout) ?: defaultTimeout
            val tool = try {
                AgentTool.external(name, description, parameters, timeout)
            } catch (error: AgentError) {
                throw BridgeError.invalidParams("'$elementPath': ${error.message}")
            }
            tools += tool
            warnings += tool.schemaWarnings
        }
        return ParsedTools(tools, warnings)
    }

    /**
     * Seconds to a time limit; `0` means none. Validate the input with
     * [BridgeParams.optionalSeconds]; out-of-range values are clamped here only
     * as a safety net.
     */
    public fun timeout(seconds: Double): Duration? {
        if (!(seconds > 0)) return null
        return (seconds.coerceAtMost(BridgeParams.MAX_TIMEOUT_SECONDS) * 1000).roundToLong().milliseconds
    }

    /** A time limit to `…TimeoutSeconds` (`0` = none), capped so it parses again. */
    public fun seconds(duration: Duration?): Double {
        if (duration == null) return 0.0
        return (duration.inWholeMilliseconds / 1000.0).coerceIn(0.0, BridgeParams.MAX_TIMEOUT_SECONDS)
    }

    /** Whether [name] is 1–64 of `A–Z a–z 0–9 _ - .`. */
    public fun isValidToolName(name: String): Boolean =
        name.length in 1..64 && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' || it == '.' }

    /**
     * Converts a peer's `tool/call` response into tool output:
     * `{"output": "text"}` → text, `{"output": <JSON>}` → JSON,
     * `"isError": true` → an error the model sees, and a JSON-RPC error
     * response → an error output with its message. A result without `output`
     * is itself the output.
     */
    public fun toolOutput(result: Result<JsonElement>): ToolOutput {
        val value = result.getOrElse { error ->
            return ToolOutput.Error((error as? BridgeError)?.message ?: error.message ?: "The tool call failed.")
        }
        val obj = value as? JsonObject
        val isError = obj?.get("isError")?.boolValue ?: false
        val output = obj?.get("output") ?: value
        if (isError) return ToolOutput.Error(output.stringValue ?: output.toJsonString())
        output.stringValue?.let { return ToolOutput.Text(it) }
        return ToolOutput.Json(output)
    }

    /**
     * Sampling settings from open-apple-models' `sampling` option.
     *
     * @property topK Top-K (`1` for `"greedy"`).
     * @property seed The random seed.
     * @property warnings Parts Gemini Nano cannot honor.
     */
    public class Sampling(public val topK: Int?, public val seed: Int?, public val warnings: List<String>)

    /**
     * `"greedy" | {"topK": n, "seed"?: n} | {"topP": p, "seed"?: n}`. ML Kit
     * has no nucleus sampling, so `topP` is accepted with a warning and only
     * its `seed` is used.
     *
     * @throws BridgeError `invalid_params`.
     */
    public fun sampling(value: JsonElement, path: String = "sampling"): Sampling {
        if (value.stringValue == "greedy") return Sampling(topK = 1, seed = null, warnings = emptyList())
        val obj = value as? JsonObject ?: throw BridgeError.invalidParams("'$path' must be \"greedy\", {\"topK\": n} or {\"topP\": p}.")
        val params = BridgeParams(obj, "$path.")
        val seed = params.optionalInt("seed", minimum = 0)
        params.optionalInt("topK", minimum = 1)?.let { return Sampling(it, seed, emptyList()) }
        params.optionalDouble("topP", minimum = 0.0, maximum = 1.0)?.let {
            return Sampling(null, seed, listOf("'$path.topP' is not supported by Gemini Nano and was ignored; use {\"topK\": n} or \"greedy\"."))
        }
        throw BridgeError.invalidParams("'$path' must contain 'topK' or 'topP'.")
    }

    /**
     * Parses a `model` parameter: absent or `"system"`, `"scripted"` (an empty
     * script), `{"type": "system"}`, `{"type": "scripted", "steps": [...]}` (see
     * [BridgeScript]), or `{"type": "<custom>", …}` for [BridgeConfiguration.modelFactory].
     *
     * @throws BridgeError `invalid_params`.
     */
    public fun modelSpec(value: JsonElement?, path: String = "model"): BridgeModelSpec {
        if (value == null || value.isJsonNull) return BridgeModelSpec.System
        value.stringValue?.let { name ->
            return when (name) {
                "system" -> BridgeModelSpec.System
                "scripted" -> BridgeModelSpec.Scripted(emptyList())
                else -> BridgeModelSpec.Custom(name, obj("type" to JsonPrimitive(name)))
            }
        }
        val model = value as? JsonObject ?: throw BridgeError.invalidParams("'$path' must be \"system\" or an object with a 'type'.")
        return when (val type = BridgeParams(model, "$path.").string("type")) {
            "system" -> BridgeModelSpec.System
            "scripted" -> BridgeScript.parse(model, path)
            else -> BridgeModelSpec.Custom(type, model)
        }
    }

    // MARK: Helpers shared by the methods

    /** Gemini Nano does best with about three to five tools per request. */
    internal const val RECOMMENDED_MAX_TOOLS: Int = 5

    internal fun toolCountWarning(count: Int): String? {
        if (count <= RECOMMENDED_MAX_TOOLS) return null
        return "$count tools: the on-device model chooses best among at most 3-5 tools per request; narrow them per turn with 'enabledTools'."
    }

    /** Session, NPC and world ids: 1–128 characters, no control characters. */
    internal fun isValidId(id: String): Boolean {
        val length = id.codePointCount(0, id.length)
        if (length !in 1..128) return false
        return id.codePoints().noneMatch { point ->
            val type = Character.getType(point)
            type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()
        }
    }

    internal fun obj(vararg members: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*members))

    internal fun strings(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
}
