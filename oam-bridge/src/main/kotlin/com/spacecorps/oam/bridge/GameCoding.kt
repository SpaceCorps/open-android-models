package com.spacecorps.oam.bridge

import com.spacecorps.oam.arrayValue
import com.spacecorps.oam.boolValue
import com.spacecorps.oam.bridge.BridgeCoding.obj
import com.spacecorps.oam.doubleValue
import com.spacecorps.oam.game.Decision
import com.spacecorps.oam.game.DecisionOption
import com.spacecorps.oam.game.DialogueEvent
import com.spacecorps.oam.game.DialogueTurn
import com.spacecorps.oam.game.Emotion
import com.spacecorps.oam.game.GameJson
import com.spacecorps.oam.game.NPCMemory
import com.spacecorps.oam.game.NPCMemoryTool
import com.spacecorps.oam.game.NPCOptions
import com.spacecorps.oam.game.NPCSaveState
import com.spacecorps.oam.game.Persona
import com.spacecorps.oam.game.WorldStateChange
import com.spacecorps.oam.intValue
import com.spacecorps.oam.longValue
import com.spacecorps.oam.stringValue
import com.spacecorps.oam.toJsonString
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/**
 * The JSON shapes of the game methods (`npc/…`, `decision/…`, `world/…`,
 * `content/generate`): encoders for dialogue turns, events, decisions and save
 * states, and decoders for personas, NPC options and memory whose errors name
 * the offending parameter (`persona.goals`, `options.replyFormat`).
 *
 * Tool calls, tool records and token usage use the shared [BridgeCoding]
 * shapes, so a client parses them the same way for sessions and NPCs.
 */
public object GameCoding {
    // MARK: Encoding

    /** `{"line", "emotion", "playerOptions", "endsConversation", "toolCalls", "relationship", "isFallback", "usage"}`. */
    public fun json(turn: DialogueTurn): JsonObject = obj(
        "line" to JsonPrimitive(turn.line),
        "emotion" to JsonPrimitive(turn.emotion.wireName),
        "playerOptions" to BridgeCoding.strings(turn.playerOptions),
        "endsConversation" to JsonPrimitive(turn.endsConversation),
        "toolCalls" to JsonArray(turn.toolCalls.map(BridgeCoding::json)),
        "relationship" to JsonPrimitive(turn.relationship),
        "isFallback" to JsonPrimitive(turn.isFallback),
        "usage" to BridgeCoding.json(turn.usage),
    )

    /**
     * The `event` payload of an `npc/event` notification, or `null` for
     * [DialogueEvent.Completed] (delivered as the `npc/talk` response instead):
     *
     * - `{"type": "emotion", "emotion"}`
     * - `{"type": "lineDelta", "delta"}`: append to the displayed line
     * - `{"type": "lineReset", "line"}`: replace the displayed line
     * - `{"type": "toolCallStarted", "call", "execution": "local" | "client"}`
     * - `{"type": "toolCallCompleted", "record"}`
     */
    public fun json(event: DialogueEvent): JsonObject? = when (event) {
        is DialogueEvent.Emotion -> obj("type" to JsonPrimitive("emotion"), "emotion" to JsonPrimitive(event.emotion.wireName))
        is DialogueEvent.LineDelta -> obj("type" to JsonPrimitive("lineDelta"), "delta" to JsonPrimitive(event.text))
        is DialogueEvent.LineReset -> obj("type" to JsonPrimitive("lineReset"), "line" to JsonPrimitive(event.text))
        is DialogueEvent.ToolCall -> BridgeCoding.toolStarted(event.call, client = false)
        is DialogueEvent.ExternalToolCall -> BridgeCoding.toolStarted(event.call, client = true)
        is DialogueEvent.ToolResult -> obj("type" to JsonPrimitive("toolCallCompleted"), "record" to BridgeCoding.json(event.record))
        is DialogueEvent.Completed -> null
    }

    /** `{"optionID", "reasoning", "confidence", "toolCalls", "usage", "isFallback"}`. */
    public fun json(decision: Decision): JsonObject = obj(
        "optionID" to JsonPrimitive(decision.optionId),
        "reasoning" to JsonPrimitive(decision.reasoning),
        "confidence" to JsonPrimitive(decision.confidence),
        "toolCalls" to JsonArray(decision.toolCalls.map(BridgeCoding::json)),
        "usage" to BridgeCoding.json(decision.usage),
        "isFallback" to JsonPrimitive(decision.isFallback),
    )

    /** A persona with every field present. */
    public fun json(persona: Persona): JsonObject = GameJson.encodeToJsonElement(Persona.serializer(), persona) as JsonObject

    /** Memory as `{"facts", "relationship", "summary"?}`. */
    public fun json(memory: NPCMemory): JsonObject {
        val members = linkedMapOf<String, JsonElement>(
            "facts" to BridgeCoding.strings(memory.facts),
            "relationship" to JsonPrimitive(memory.relationship),
        )
        memory.summary?.let { members["summary"] = JsonPrimitive(it) }
        return JsonObject(members)
    }

    /**
     * NPC options in the form [npcOptions] reads back: `memoryTools` as a list
     * of names, `toolChoice` in its wire form, unset optional values left out,
     * except an unset `secretsUnlockAtRelationship`, written as `null` (absent
     * would mean the default threshold).
     */
    public fun json(options: NPCOptions): JsonObject {
        val encoded = GameJson.encodeToJsonElement(NPCOptions.serializer(), options) as JsonObject
        return JsonObject(encoded.filter { (key, value) -> value !is JsonNull || key == "secretsUnlockAtRelationship" })
    }

    /** `{"path", "oldValue"?, "newValue"?}`: a missing value (created or removed) is left out, a JSON `null` is written. */
    public fun json(change: WorldStateChange): JsonObject {
        val members = linkedMapOf<String, JsonElement>("path" to JsonPrimitive(change.path))
        change.oldValue?.let { members["oldValue"] = it }
        change.newValue?.let { members["newValue"] = it }
        return JsonObject(members)
    }

    /** A save state as `{"version", "persona", "memory", "transcript"}`. */
    public fun json(state: NPCSaveState): JsonObject = state.toJson() as JsonObject

    // MARK: Decoding

    /**
     * A decoded value and the warnings about keys that were ignored.
     *
     * @property value The value.
     * @property warnings `Unknown parameter '…' was ignored.` messages.
     */
    public class Decoded<T>(public val value: T, public val warnings: List<String>)

    /**
     * Decodes a persona. Only `name` is required; unknown keys produce warnings.
     *
     * @throws BridgeError `invalid_params`.
     */
    public fun persona(value: JsonElement, path: String = "persona"): Decoded<Persona> {
        val obj = value as? JsonObject ?: throw BridgeError.invalidParams("'$path' must be an object with at least a 'name'.")
        val descriptor = Persona.serializer().descriptor
        checkShape(obj, descriptor, path)
        val persona = decode(Persona.serializer(), obj, path)
        if (persona.name.isBlank()) throw BridgeError.invalidParams("'$path.name' must not be empty.")
        return Decoded(persona, BridgeParams(obj, "$path.").unknownKeys(names(descriptor)))
    }

    /**
     * NPC options and the bridge-only `toolTimeoutSeconds` (`null` when absent).
     *
     * @property options The options.
     * @property toolTimeoutSeconds The client-tool time limit in seconds (`0` = none), or `null`.
     * @property warnings Unknown keys.
     */
    public class ParsedOptions(public val options: NPCOptions, public val toolTimeoutSeconds: Double?, public val warnings: List<String>)

    /**
     * Decodes [NPCOptions] (missing fields take their defaults). Accepts
     * `memoryTools` as a list of names (`"rememberFact"`, `"changeRelationship"`,
     * or the tool names), `"all"`/`"none"` or a bit mask, and `toolChoice` in
     * the wire form.
     *
     * @throws BridgeError `invalid_params`.
     */
    public fun npcOptions(value: JsonElement?, path: String = "options"): ParsedOptions {
        if (value == null || value is JsonNull) return ParsedOptions(NPCOptions(), null, emptyList())
        val obj = value as? JsonObject ?: throw BridgeError.invalidParams("'$path' must be an object.")
        val descriptor = NPCOptions.serializer().descriptor
        val params = BridgeParams(obj, "$path.")
        val warnings = params.unknownKeys(names(descriptor) + TOOL_TIMEOUT)
        val timeout = params.optionalSeconds(TOOL_TIMEOUT)
        val members = LinkedHashMap(obj)
        members.remove(TOOL_TIMEOUT)
        members["memoryTools"]?.takeUnless { it is JsonNull }?.let { tools ->
            members["memoryTools"] = JsonArray(memoryTools(tools, "$path.memoryTools").map { JsonPrimitive(it.wireName) })
        }
        members["toolChoice"]?.takeUnless { it is JsonNull }?.let { choice ->
            members["toolChoice"] = BridgeCoding.toolChoice(choice, "$path.toolChoice").toJson()
        }
        val normalized = JsonObject(members)
        checkShape(normalized, descriptor, path)
        val options = decode(NPCOptions.serializer(), normalized, path)
        if (options.playerOptionCount !in 0..NPCOptions.MAX_PLAYER_OPTIONS) {
            throw BridgeError.invalidParams("'$path.playerOptionCount' must be between 0 and ${NPCOptions.MAX_PLAYER_OPTIONS}.")
        }
        return ParsedOptions(options, timeout, warnings)
    }

    /**
     * Decodes memory `{"facts"?, "relationship"?, "summary"?}`.
     *
     * @throws BridgeError `invalid_params`.
     */
    public fun memory(value: JsonElement?, path: String = "memory"): NPCMemory {
        if (value == null || value is JsonNull) return NPCMemory()
        val obj = value as? JsonObject ?: throw BridgeError.invalidParams("'$path' must be an object.")
        checkShape(obj, NPCMemory.serializer().descriptor, path)
        return decode(NPCMemory.serializer(), obj, path)
    }

    /**
     * Decodes a save state from `npc/state` (`{"version", "persona", "memory",
     * "transcript", …}`); the whole `npc/state` result is accepted too.
     *
     * @throws BridgeError `invalid_params`.
     */
    public fun saveState(value: JsonElement, path: String = "state"): NPCSaveState {
        val obj = value as? JsonObject ?: throw BridgeError.invalidParams("'$path' must be a save state object from npc/state.")
        val inner = obj["state"]
        if ("persona" !in obj && inner is JsonObject) return saveState(inner, "$path.state")
        checkShape(obj, NPCSaveState.serializer().descriptor, path)
        val state = decode(NPCSaveState.serializer(), obj, path)
        if (state.persona.name.isBlank()) throw BridgeError.invalidParams("'$path.persona.name' must not be empty.")
        return state
    }

    /**
     * Decision options: `[{"id", "description"?}]`, or plain id strings. Ids
     * are trimmed and must be unique and non-empty.
     *
     * @throws BridgeError `invalid_params`.
     */
    public fun decisionOptions(value: JsonElement, path: String = "options"): List<DecisionOption> {
        val array = value.arrayValue
        if (array.isNullOrEmpty()) throw BridgeError.invalidParams("'$path' must be a non-empty array of {\"id\", \"description\"} objects.")
        val seen = HashSet<String>()
        return array.mapIndexed { index, element ->
            val elementPath = "$path[$index]"
            val option = element.stringValue?.let { DecisionOption(it) } ?: (element as? JsonObject)?.let { obj ->
                val params = BridgeParams(obj, "$elementPath.")
                DecisionOption(params.string("id"), params.optionalString("description") ?: "")
            } ?: throw BridgeError.invalidParams("'$elementPath' must be an object {\"id\", \"description\"} or an id string.")
            val id = option.id.trim()
            if (id.isEmpty()) throw BridgeError.invalidParams("'$elementPath.id' must not be empty.")
            if (!seen.add(id)) throw BridgeError.invalidParams("Duplicate option id '$id' in '$path'.")
            option.copy(id = id)
        }
    }

    /** Parses `memoryTools`: a list of names, `"all"`, `"none"`, one name, or open-apple-models' bit mask. */
    internal fun memoryTools(value: JsonElement, path: String): Set<NPCMemoryTool> {
        value.intValue?.let { mask -> return NPCMemoryTool.entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet() }
        val names: List<JsonElement> = value.stringValue?.let { name ->
            when (name) {
                "all" -> return NPCMemoryTool.ALL
                "none" -> return emptySet()
                else -> listOf(value)
            }
        } ?: value.arrayValue ?: throw BridgeError.invalidParams(
            "'$path' must be a list such as [\"rememberFact\", \"changeRelationship\"], \"all\" or \"none\".",
        )
        return names.map { name ->
            name.stringValue?.let(NPCMemoryTool::matching) ?: throw BridgeError.invalidParams(
                "'$path' contains unknown memory tool ${name.toJsonString()}; known: ${NPCMemoryTool.entries.joinToString(", ") { "\"${it.wireName}\"" }}.",
            )
        }.toSet()
    }

    /** RFC 7386 JSON Merge Patch: objects merge recursively, `null` members delete keys, anything else replaces. */
    internal fun merged(patch: JsonElement, target: JsonElement?): JsonElement {
        if (patch !is JsonObject) return patch
        val result = LinkedHashMap((target as? JsonObject).orEmpty())
        for ((key, value) in patch) {
            if (value is JsonNull) result.remove(key) else result[key] = merged(value, result[key])
        }
        return JsonObject(result)
    }

    /** Text for a free-form `context`/`situation` parameter: strings as is, other JSON as compact JSON. */
    internal fun text(value: JsonElement?): String? {
        if (value == null || value is JsonNull) return null
        return value.stringValue ?: value.toJsonString()
    }

    // MARK: Shape checks

    private const val TOOL_TIMEOUT = "toolTimeoutSeconds"

    private val emotionName = serializer<Emotion>().descriptor.serialName

    private fun names(descriptor: SerialDescriptor): Set<String> = (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()

    private fun <T> decode(deserializer: DeserializationStrategy<T>, value: JsonElement, path: String): T = try {
        GameJson.decodeFromJsonElement(deserializer, value)
    } catch (error: SerializationException) {
        throw BridgeError.invalidParams("'$path' is invalid: ${error.message}")
    } catch (error: IllegalArgumentException) {
        throw BridgeError.invalidParams("'$path' is invalid: ${error.message}")
    }

    /**
     * Checks [value] against the fields of [descriptor] before decoding, so a
     * mistake is reported with the parameter's full name (`persona.goals`)
     * instead of a serializer message. Values decoded by custom serializers
     * (tool choice, memory tools, schemas, transcript entries) are left to them.
     */
    private fun checkShape(value: JsonObject, descriptor: SerialDescriptor, path: String) {
        for (index in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(index)
            val location = "$path.$name"
            val optional = descriptor.isElementOptional(index)
            val member = value[name]
            if (member == null) {
                if (!optional) throw BridgeError.invalidParams("Missing required parameter '$location'.")
                continue
            }
            checkValue(member, descriptor.getElementDescriptor(index), location, optional)
        }
    }

    private fun checkValue(value: JsonElement, descriptor: SerialDescriptor, path: String, optional: Boolean) {
        if (value is JsonNull) {
            // A null takes the field's default (GameJson coerces it) unless the field is required.
            if (descriptor.isNullable || optional) return
            throw BridgeError.invalidParams("Missing required parameter '$path'.")
        }
        when (val kind = descriptor.kind) {
            PrimitiveKind.STRING -> {
                val text = value.stringValue ?: throw mistyped(path, "a string")
                if (descriptor.serialName.removeSuffix("?") == emotionName && Emotion.matching(text) == null) {
                    throw BridgeError.invalidParams(
                        "Parameter '$path' must be one of ${Emotion.entries.joinToString(", ") { it.wireName }}; got '$text'.",
                    )
                }
            }
            PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE ->
                if (value.longValue == null) throw mistyped(path, "an integer")
            PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> if (value.doubleValue == null) throw mistyped(path, "a number")
            PrimitiveKind.BOOLEAN -> if (value.boolValue == null) throw mistyped(path, "a boolean")
            SerialKind.ENUM -> {
                val text = value.stringValue ?: throw mistyped(path, "a string")
                val allowed = (0 until descriptor.elementsCount).map(descriptor::getElementName)
                if (text !in allowed) {
                    throw BridgeError.invalidParams("Parameter '$path' must be one of ${allowed.joinToString(", ") { "\"$it\"" }}; got '$text'.")
                }
            }
            StructureKind.LIST -> {
                val array = value as? JsonArray ?: throw mistyped(path, "an array")
                val item = descriptor.getElementDescriptor(0)
                array.forEachIndexed { index, element -> checkValue(element, item, "$path[$index]", optional = false) }
            }
            StructureKind.CLASS -> checkShape(value as? JsonObject ?: throw mistyped(path, "an object"), descriptor, path)
            StructureKind.MAP -> if (value !is JsonObject) throw mistyped(path, "an object")
            else -> if (kind == StructureKind.OBJECT && value !is JsonObject) throw mistyped(path, "an object")
        }
    }

    private fun mistyped(path: String, kind: String) = BridgeError.invalidParams("Parameter '$path' must be $kind.")
}
