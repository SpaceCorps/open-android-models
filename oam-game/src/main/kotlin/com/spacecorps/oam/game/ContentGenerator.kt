package com.spacecorps.oam.game

import com.spacecorps.oam.Agent
import com.spacecorps.oam.AgentConfiguration
import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentErrorCode
import com.spacecorps.oam.AgentTool
import com.spacecorps.oam.JsonSchema
import com.spacecorps.oam.LanguageModel
import com.spacecorps.oam.ToolChoice
import com.spacecorps.oam.ToolPolicy
import com.spacecorps.oam.toJsonString
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.serializer

/**
 * Generates structured game content (items, quests, level text, loot
 * tables, rumors) that matches a [JsonSchema].
 *
 * ```kotlin
 * @Serializable data class Item(val name: String, val description: String, val rarity: String, val damage: Int)
 *
 * val sword = ContentGenerator(geminiNano).generateTyped<Item>(
 *     "A cursed sword found in a drowned temple.",
 *     JsonSchema.obj(
 *         "name" to JsonSchema.string(description = "Two or three words"),
 *         "description" to JsonSchema.string(description = "One sentence of flavor text"),
 *         "rarity" to JsonSchema.string(enum = listOf("common", "rare", "legendary")),
 *         "damage" to JsonSchema.integer(minimum = 1, maximum = 50),
 *     ),
 * )
 * ```
 *
 * Gemini Nano has no constrained decoding for runtime schemas: the output
 * is generated from a prompt, then coerced, validated (enums, ranges,
 * required fields, types) and repaired once by oam-core. Tips for a small
 * model: keep schemas flat and short, put guidance in property descriptions,
 * order properties so earlier ones inform later ones (name, description,
 * then stats), and generate lists with [JsonSchema.array] in one call rather
 * than one call per item.
 *
 * @property model The language model.
 * @property instructions Default instructions, used when a call passes none.
 * @property temperature Sampling temperature (`null` = model default). Raise it for variety.
 */
public data class ContentGenerator(
    public val model: LanguageModel,
    public val instructions: String = DEFAULT_INSTRUCTIONS,
    public val temperature: Double? = null,
) {
    /**
     * Generates JSON matching [schema], with object keys in schema order.
     *
     * @param prompt What to generate.
     * @param schema The output shape.
     * @param instructions Overrides [ContentGenerator.instructions] for this call.
     * @param context Extra facts as JSON (player level, biome, names already used, …).
     * @param tools Tools the model may call first (for example to look up game data).
     * @throws AgentError [AgentErrorCode.INVALID_SCHEMA] for a malformed schema,
     *   [AgentErrorCode.GENERATION_FAILED] when the output stays invalid after a repair,
     *   [AgentErrorCode.GUARDRAIL_VIOLATION] when the safety filters block it, or any other model error.
     */
    public suspend fun generate(
        prompt: String,
        schema: JsonSchema,
        instructions: String? = null,
        context: JsonElement? = null,
        tools: List<AgentTool> = emptyList(),
    ): JsonElement {
        if (temperature != null && !(temperature >= 0.0)) throw AgentError(AgentErrorCode.INVALID_REQUEST, "temperature must not be negative.")
        var text = prompt
        if (context != null && context != JsonNull) text += "\nFacts: ${context.toJsonString()}"
        val response = coroutineScope {
            Agent(
                model,
                TextCleanup.trimmedOrNull(instructions) ?: this@ContentGenerator.instructions,
                tools,
                AgentConfiguration(temperature = temperature),
                scope = this,
            ).use { agent ->
                agent.respond(text, schema, ToolPolicy(choice = if (tools.isEmpty()) ToolChoice.None else ToolChoice.Auto))
            }
        }
        return response.structured ?: throw AgentError(AgentErrorCode.GENERATION_FAILED, "The model produced no structured content.")
    }

    /**
     * Generates content and decodes it with [deserializer]. Property names in
     * [schema] must match the target's serial names.
     *
     * @throws AgentError as [generate], and [AgentErrorCode.GENERATION_FAILED] when the content does not decode.
     */
    public suspend fun <T> generateWith(
        deserializer: DeserializationStrategy<T>,
        prompt: String,
        schema: JsonSchema,
        instructions: String? = null,
        context: JsonElement? = null,
        tools: List<AgentTool> = emptyList(),
    ): T {
        val json = generate(prompt, schema, instructions, context, tools)
        return try {
            GameJson.decodeFromJsonElement(deserializer, json)
        } catch (error: SerializationException) {
            throw AgentError(AgentErrorCode.GENERATION_FAILED, "Could not decode ${deserializer.descriptor.serialName} from generated content ${json.toJsonString()}: ${error.message}", cause = error)
        } catch (error: IllegalArgumentException) {
            throw AgentError(AgentErrorCode.GENERATION_FAILED, "Could not decode ${deserializer.descriptor.serialName} from generated content ${json.toJsonString()}: ${error.message}", cause = error)
        }
    }

    /** Generates content and decodes it into [T] (a `@Serializable` type). See [generateWith]. */
    public suspend inline fun <reified T> generateTyped(
        prompt: String,
        schema: JsonSchema,
        instructions: String? = null,
        context: JsonElement? = null,
        tools: List<AgentTool> = emptyList(),
    ): T = generateWith(serializer<T>(), prompt, schema, instructions, context, tools)

    public companion object {
        /** The default instructions. */
        public const val DEFAULT_INSTRUCTIONS: String =
            "You write content for a video game. Follow the requested format exactly. " +
                "Keep text short, vivid and consistent with the request."
    }
}
