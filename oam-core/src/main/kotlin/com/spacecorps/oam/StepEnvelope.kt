package com.spacecorps.oam

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Parses the JSON *step envelope* a decide step answers with:
 * `{"action": "<tool name>" | "respond", "arguments": {…}}`.
 *
 * Small models drift from the requested shape in predictable ways, so the
 * parser is lenient: it strips code fences and surrounding prose, takes the
 * first balanced JSON object (tolerating single quotes, trailing commas and
 * truncation), accepts synonyms for both keys (`tool`, `name`, `function`,
 * `args`, `parameters`, `input`, …) and for `respond` (`reply`, `answer`,
 * `respond_directly`, …), unwraps `{"tool_call": {…}}` and OpenAI-style
 * `{"function": {"name", "arguments": "<json text>"}}`, reads
 * `{"take_order": {…}}` as a call, reads arguments written next to the
 * action (`{"action": "take_order", "item": "ale"}`), and matches tool names
 * ignoring case, separators, `()` and a `functions.` prefix. A bare tool
 * name or `respond` without JSON also counts.
 */
internal object StepEnvelope {
    sealed interface Result {
        /** The model chose to reply. */
        data object Respond : Result

        /**
         * The model chose [tool]. [candidates] are possible argument objects,
         * most likely first; the caller picks the first that validates.
         */
        data class Call(val tool: String, val candidates: List<JsonObject>) : Result

        /** Unusable output. [tool] is set when the tool was clear but something else was wrong. */
        data class Invalid(val problem: String, val tool: String? = null) : Result
    }

    private val ACTION_KEYS = listOf(
        "action", "tool", "toolname", "name", "function", "functionname", "call", "nextaction", "next",
        "step", "command", "use", "choice", "decision", "tooltouse", "selectedtool",
    )
    private val ARGUMENT_KEYS = listOf(
        "arguments", "args", "parameters", "params", "input", "inputs", "toolinput", "actioninput",
        "argument", "toolarguments", "toolargs", "functionarguments",
    )
    private val WRAPPER_KEYS = setOf("toolcall", "functioncall", "step", "decision", "nextstep", "envelope", "call", "action", "toolcalls")
    private val RESPOND_WORDS = setOf(
        "respond", "respond_directly", "reply", "answer", "final", "final_answer", "none", "no_tool", "talk",
        "say", "speak", "chat", "response", "direct_response", "respond_to_user", "reply_to_user", "finish",
        "done", "no_action", "nothing", "direct_reply", "reply_directly", "answer_directly",
    )
    private val META_KEYS = setOf("reason", "reasoning", "thought", "thoughts", "explanation", "why", "rationale", "comment", "note", "type")

    /**
     * Parses a decide step's output.
     *
     * @param tools Names of the tools offered in this step.
     * @param allowRespond Whether `respond` was offered.
     */
    fun parse(text: String, tools: List<String>, allowRespond: Boolean): Result {
        val found = JsonExtraction.firstObject(text)
            ?: return bareWord(text, tools, allowRespond)
                ?: Result.Invalid("the answer was not a JSON object")
        val envelope = unwrap(found)
        return interpret(envelope, tools, allowRespond)
    }

    /** Candidate argument objects for a [ToolChoice.Tool] arguments step: the object itself, or unwrapped from an envelope. */
    fun argumentCandidates(found: JsonObject, tool: String): List<JsonObject> {
        val candidates = ArrayList<JsonObject>()
        candidates += found
        val unwrapped = unwrap(found)
        val parsed = interpret(unwrapped, listOf(tool), allowRespond = false)
        if (parsed is Result.Call) candidates += parsed.candidates
        ARGUMENT_KEYS.firstNotNullOfOrNull { key -> field(found, key) }?.let { (_, value) -> toObject(value)?.let { candidates += it } }
        return candidates.distinct()
    }

    private fun unwrap(value: JsonObject): JsonObject {
        var current = value
        repeat(3) {
            val single = current.entries.singleOrNull() ?: return current
            val key = normalizeKey(single.key)
            if (key !in WRAPPER_KEYS) return current
            current = when (val inner = single.value) {
                is JsonObject -> inner
                is JsonArray -> inner.firstOrNull() as? JsonObject ?: return current
                else -> return current
            }
        }
        return current
    }

    private fun interpret(envelope: JsonObject, tools: List<String>, allowRespond: Boolean): Result {
        // Find the action: prefer a key whose value names a known action.
        val actionFields = ACTION_KEYS.mapNotNull { key -> field(envelope, key) }
        var chosen: Pair<String, JsonElement>? = null
        var resolved: Resolved? = null
        for (candidate in actionFields) {
            val name = actionName(candidate.second) ?: continue
            val match = resolve(name, tools)
            if (match != null) {
                chosen = candidate
                resolved = match
                break
            }
            if (chosen == null) chosen = candidate
        }

        if (resolved == null) {
            // {"take_order": {...}} or {"respond": "..."}
            val single = envelope.entries.singleOrNull()
            if (single != null) {
                when (val match = resolve(single.key, tools)) {
                    is Resolved.Tool -> return call(match.name, listOfNotNull(toObject(single.value) ?: EmptyJsonObject))
                    Resolved.Respond -> return respondOrInvalid(tools, allowRespond)
                    null -> Unit
                }
            }
            val name = chosen?.let { actionName(it.second) }
            return if (name == null) {
                Result.Invalid("the JSON object has no \"action\"")
            } else {
                Result.Invalid("\"$name\" is not an available action; use one of: ${options(tools, allowRespond)}")
            }
        }

        val actionKey = chosen!!.first
        return when (val match = resolved) {
            Resolved.Respond -> respondOrInvalid(tools, allowRespond)
            is Resolved.Tool -> call(match.name, arguments(envelope, actionKey, chosen.second))
        }
    }

    private fun call(tool: String, candidates: List<JsonObject>): Result = Result.Call(tool, candidates.ifEmpty { listOf(EmptyJsonObject) })

    private fun respondOrInvalid(tools: List<String>, allowRespond: Boolean): Result =
        if (allowRespond) {
            Result.Respond
        } else {
            Result.Invalid("\"respond\" is not allowed now; you must call one of the tools: ${tools.joinToString(", ")}")
        }

    private fun arguments(envelope: JsonObject, actionKey: String, actionValue: JsonElement): List<JsonObject> {
        val candidates = ArrayList<JsonObject>()
        // {"function": {"name": "x", "arguments": {...}}}
        (actionValue as? JsonObject)?.let { nested ->
            ARGUMENT_KEYS.firstNotNullOfOrNull { field(nested, it) }?.let { (_, value) -> toObject(value)?.let(candidates::add) }
        }
        ARGUMENT_KEYS.firstNotNullOfOrNull { key -> field(envelope, key)?.takeIf { it.first != actionKey } }?.let { (_, value) ->
            val parsed = toObject(value)
            if (parsed != null) candidates += parsed
        }
        // Arguments written next to the action.
        val rest = envelope.filterKeys { key -> key != actionKey && normalizeKey(key) !in ARGUMENT_KEYS.toSet() && key.lowercase() !in META_KEYS }
        if (rest.isNotEmpty()) candidates += JsonObject(rest)
        if (candidates.isEmpty()) candidates += EmptyJsonObject
        return candidates
    }

    private fun toObject(value: JsonElement): JsonObject? = when (value) {
        is JsonObject -> value
        else -> value.stringValue?.let { text -> if (text.isBlank()) EmptyJsonObject else JsonExtraction.firstObject(text) }
            ?: if (value.isJsonNull) EmptyJsonObject else null
    }

    private fun actionName(value: JsonElement): String? = when (value) {
        is JsonObject -> value["name"]?.stringValue ?: value["tool"]?.stringValue
        else -> value.stringValue
    }

    private fun field(obj: JsonObject, normalizedKey: String): Pair<String, JsonElement>? =
        obj.entries.firstOrNull { normalizeKey(it.key) == normalizedKey }?.let { it.key to it.value }

    private sealed interface Resolved {
        data class Tool(val name: String) : Resolved
        data object Respond : Resolved
    }

    private fun resolve(raw: String, tools: List<String>): Resolved? {
        val cleaned = raw.trim().trim('`', '"', '\'', '.', ' ', '*').removeSuffix("()").trim()
            .removePrefix("functions.").removePrefix("tools.").removePrefix("tool:").trim()
        if (cleaned.isEmpty()) return null
        tools.firstOrNull { it == cleaned }?.let { return Resolved.Tool(it) }
        val words = normalizeWords(cleaned)
        tools.firstOrNull { normalizeWords(it) == words }?.let { return Resolved.Tool(it) }
        val key = normalizeKey(cleaned)
        tools.firstOrNull { normalizeKey(it) == key }?.let { return Resolved.Tool(it) }
        if (words in RESPOND_WORDS || RESPOND_WORDS.any { normalizeKey(it) == key }) return Resolved.Respond
        return null
    }

    private fun bareWord(text: String, tools: List<String>, allowRespond: Boolean): Result? {
        val trimmed = JsonExtraction.candidates(text).first().trim().trim('`', '"', '\'', '.', ' ', '*', '\n')
        if (trimmed.isEmpty() || trimmed.length > 64 || trimmed.contains('\n')) return null
        return when (val match = resolve(trimmed, tools)) {
            is Resolved.Tool -> Result.Call(match.name, listOf(EmptyJsonObject))
            Resolved.Respond -> respondOrInvalid(tools, allowRespond)
            null -> null
        }
    }

    private fun options(tools: List<String>, allowRespond: Boolean): String =
        (tools + if (allowRespond) listOf(AgentTool.RESPOND_ACTION) else emptyList()).joinToString(", ")

    /** Lowercase with spaces, hyphens and camel-case humps turned into underscores. */
    private fun normalizeWords(text: String): String =
        text.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").trim().lowercase().replace(Regex("[\\s_-]+"), "_")

    private fun normalizeKey(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }
}
