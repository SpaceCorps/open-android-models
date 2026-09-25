package com.spacecorps.oam.game

import com.spacecorps.oam.Agent
import com.spacecorps.oam.AgentConfiguration
import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentErrorCode
import com.spacecorps.oam.AgentResponse
import com.spacecorps.oam.AgentTool
import com.spacecorps.oam.EmptyJsonObject
import com.spacecorps.oam.JsonSchema
import com.spacecorps.oam.LanguageModel
import com.spacecorps.oam.TokenUsage
import com.spacecorps.oam.ToolChoice
import com.spacecorps.oam.ToolPolicy
import com.spacecorps.oam.ToolRecord
import com.spacecorps.oam.doubleValue
import com.spacecorps.oam.objectValue
import com.spacecorps.oam.stringValue
import com.spacecorps.oam.toJsonString
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * One choice offered to a [DecisionEngine].
 *
 * @property id Stable identifier returned in [Decision.optionId] (for example `"attack"`).
 *   Short, lowercase snake_case ids work best with a small model.
 * @property description What choosing it means, in a short sentence.
 */
@Serializable
public data class DecisionOption(
    public val id: String,
    public val description: String = "",
)

/**
 * The outcome of [DecisionEngine.decide]. Serializable (see [GameJson]);
 * the id is written as `optionID`, as on open-apple-models' wire.
 *
 * @property optionId The chosen [DecisionOption.id].
 * @property reasoning The model's one-sentence justification (generated before the choice).
 * @property confidence Self-reported confidence, `0..100`. Useful as a tie-breaker or to fall back to
 *   scripted behavior when low; not a calibrated probability.
 * @property toolCalls Tools executed while deciding.
 * @property usage Tokens used (estimated when the backend does not report usage).
 * @property isFallback True when the model's answer was unusable and the request's fallback option was returned.
 */
@Serializable
public data class Decision(
    @SerialName("optionID")
    public val optionId: String,
    public val reasoning: String = "",
    public val confidence: Int = 50,
    public val toolCalls: List<ToolRecord> = emptyList(),
    public val usage: TokenUsage = TokenUsage(),
    public val isFallback: Boolean = false,
)

/**
 * A decision to make, for [DecisionEngine.decideMany]. See [DecisionEngine.decide] for the fields.
 *
 * @property situation What is happening, from the actor's point of view.
 * @property options The choices; ids must be unique and non-empty.
 * @property actor The character deciding.
 * @property context Extra facts as JSON (health, distances, inventory, …).
 * @property tools Tools the model may call before choosing.
 * @property toolChoice [ToolChoice.Required] or [ToolChoice.Tool] force a lookup first.
 * @property fallbackOptionId Returned (with [Decision.isFallback]) when the model's answer is unusable.
 */
public data class DecisionRequest(
    public val situation: String,
    public val options: List<DecisionOption>,
    public val actor: Persona? = null,
    public val context: JsonElement? = null,
    public val tools: List<AgentTool> = emptyList(),
    public val toolChoice: ToolChoice = ToolChoice.Auto,
    public val fallbackOptionId: String? = null,
)

/**
 * Picks one of a fixed set of options with the on-device model: enemy
 * tactics, companion reactions, shopkeeper haggling, crowd behavior.
 *
 * The model answers with JSON whose `choice` is an enum of the option ids.
 * It writes a one-sentence `reasoning` first (a tiny chain of thought that
 * improves choices), then the `choice`, then a `confidence`. Gemini Nano
 * has no constrained decoding, so oam-core validates the answer (matching
 * near-misses such as `"Flee"` for `"flee"`) and re-asks once when the
 * choice is not one of the ids.
 *
 * ```kotlin
 * val decision = DecisionEngine(geminiNano).decide(
 *     situation = "The goblin has 3 HP left and the player is at full health.",
 *     options = listOf(
 *         DecisionOption("attack", "Keep fighting"),
 *         DecisionOption("flee", "Run into the woods"),
 *         DecisionOption("beg", "Beg for mercy"),
 *     ),
 *     fallbackOptionId = "flee",
 * )
 * when (decision.optionId) { "flee" -> goblin.flee() … }
 * ```
 *
 * Every decision uses a fresh request (no history), so the engine is
 * stateless and safe to share across coroutines.
 *
 * @property model The language model.
 * @property instructions Replaces the default instructions (the actor's persona is still added).
 * @property temperature Sampling temperature (`null` = model default). Use a low value for consistent behavior.
 * @property maxToolRounds Maximum tool rounds before the choice.
 */
public data class DecisionEngine(
    public val model: LanguageModel,
    public val instructions: String? = null,
    public val temperature: Double? = null,
    public val maxToolRounds: Int = 2,
) {
    /**
     * Chooses one of [options].
     *
     * @param situation What is happening, from the actor's point of view.
     * @param options The choices. Ids must be unique and non-empty (they are trimmed).
     * @param actor The character deciding; its role, personality and goals shape the choice.
     * @param context Extra facts as JSON (health, distances, inventory, …).
     * @param tools Tools the model may call before choosing.
     * @param toolChoice [ToolChoice.Required] or [ToolChoice.Tool] force a lookup first.
     * @param fallbackOptionId Returned (with [Decision.isFallback] set) when the model's answer is unusable:
     *   blocked by the safety filters (combat trips them fairly often), refused, or still not one of the ids
     *   after a repair.
     * @return The decision. With a single option it is returned immediately, without calling the model.
     * @throws AgentError [AgentErrorCode.INVALID_REQUEST] for invalid options or an unknown fallback (before any
     *   model call), or any model error that no fallback covers (unavailable model, cancellation, …).
     */
    public suspend fun decide(
        situation: String,
        options: List<DecisionOption>,
        actor: Persona? = null,
        context: JsonElement? = null,
        tools: List<AgentTool> = emptyList(),
        toolChoice: ToolChoice = ToolChoice.Auto,
        fallbackOptionId: String? = null,
    ): Decision {
        val ids = validate(options)
        val fallback = fallbackOptionId?.trim()
        if (fallback != null && fallback !in ids) {
            throw AgentError(AgentErrorCode.INVALID_REQUEST, "fallbackOptionId '$fallback' is not one of: ${ids.joinToString(", ")}.")
        }
        if (ids.size == 1) return Decision(optionId = ids[0], reasoning = "It is the only option.", confidence = 100)
        if (maxToolRounds < 0) throw AgentError(AgentErrorCode.INVALID_REQUEST, "maxToolRounds must not be negative.")
        if (temperature != null && !(temperature >= 0.0)) throw AgentError(AgentErrorCode.INVALID_REQUEST, "temperature must not be negative.")
        val configuration = AgentConfiguration(
            temperature = temperature,
            userLabel = GAME_LABEL,
            assistantLabel = TextCleanup.trimmedOrNull(actor?.name) ?: DEFAULT_ACTOR_LABEL,
        )
        val policy = ToolPolicy(choice = if (tools.isEmpty()) ToolChoice.None else toolChoice, maxToolRounds = maxToolRounds)
        return try {
            val response = coroutineScope {
                Agent(model, instructions(actor), tools, configuration, scope = this).use { agent ->
                    agent.respond(prompt(situation, options, context), schema(ids), policy)
                }
            }
            decision(response, ids)
        } catch (error: AgentError) {
            if (fallback == null || error.code !in NPCPrompting.UNUSABLE) throw error
            Decision(optionId = fallback, reasoning = "", confidence = 0, isFallback = true)
        }
    }

    /** Makes one decision for [request]. See the other overload. */
    public suspend fun decide(request: DecisionRequest): Decision = decide(
        situation = request.situation,
        options = request.options,
        actor = request.actor,
        context = request.context,
        tools = request.tools,
        toolChoice = request.toolChoice,
        fallbackOptionId = request.fallbackOptionId,
    )

    /**
     * Makes several independent decisions (for example for a crowd of NPCs)
     * with at most [maxConcurrency] running at once. The on-device model
     * processes requests largely one at a time, so higher concurrency mostly
     * adds queueing; 2 keeps the model busy without starving other work.
     *
     * @return One result per request, in request order. A failed decision (an
     *   [AgentError]) does not affect the others. Cancelling the caller cancels them all.
     */
    public suspend fun decideMany(requests: List<DecisionRequest>, maxConcurrency: Int = 2): List<Result<Decision>> {
        if (requests.isEmpty()) return emptyList()
        val permits = Semaphore(maxConcurrency.coerceAtLeast(1))
        return coroutineScope {
            requests.map { request ->
                async {
                    permits.withPermit {
                        try {
                            Result.success(decide(request))
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            Result.failure(AgentError.from(error))
                        }
                    }
                }
            }.awaitAll()
        }
    }

    // MARK: Prompting

    internal fun instructions(actor: Persona?): String {
        val lines = arrayListOf(TextCleanup.trimmedOrNull(instructions) ?: DEFAULT_INSTRUCTIONS)
        if (actor != null) {
            val who = TextCleanup.trimmedOrNull(actor.role)?.let { "${actor.name}, $it" } ?: actor.name
            lines += "You decide for $who."
            TextCleanup.trimmedOrNull(actor.personality)?.let { lines += "Personality: ${TextCleanup.sentence(it)}" }
            TextCleanup.list(actor.goals)?.let { lines += "Goals: $it" }
        }
        return lines.joinToString("\n")
    }

    public companion object {
        /** The default instructions. */
        public const val DEFAULT_INSTRUCTIONS: String =
            "You make decisions for characters in a video game. " +
                "Pick the option that best fits the character and the situation. " +
                "Give one short reason, then the option id, then your confidence from 0 to 100."

        private const val GAME_LABEL = "Game"
        private const val DEFAULT_ACTOR_LABEL = "Character"

        internal fun prompt(situation: String, options: List<DecisionOption>, context: JsonElement?): String {
            val lines = arrayListOf("Situation: ${TextCleanup.trimmedOrNull(situation) ?: "(none given)"}")
            if (context != null && context != JsonNull && context != EmptyJsonObject) lines += "Facts: ${context.toJsonString()}"
            lines += "Options:"
            for (option in options) {
                val id = option.id.trim()
                lines += TextCleanup.trimmedOrNull(option.description)?.let { "- $id: $it" } ?: "- $id"
            }
            return lines.joinToString("\n")
        }

        /**
         * Reasoning first (one sentence), then the enum-constrained choice, then
         * confidence. The confidence range is described rather than validated:
         * an out-of-range number is clamped instead of costing a repair step.
         */
        internal fun schema(ids: List<String>): JsonSchema = JsonSchema.obj(
            "reasoning" to JsonSchema.string(description = "One short sentence explaining the choice."),
            "choice" to JsonSchema.string(description = "The id of the chosen option.", enum = ids),
            "confidence" to JsonSchema.integer(description = "How sure you are, from 0 to 100."),
        )

        /** Validates options and returns their trimmed ids. */
        internal fun validate(options: List<DecisionOption>): List<String> {
            if (options.isEmpty()) throw AgentError(AgentErrorCode.INVALID_REQUEST, "A decision needs at least one option.")
            val ids = ArrayList<String>()
            for (option in options) {
                val id = option.id.trim()
                if (id.isEmpty()) throw AgentError(AgentErrorCode.INVALID_REQUEST, "Decision option ids must not be empty.")
                if (id in ids) throw AgentError(AgentErrorCode.INVALID_REQUEST, "Duplicate decision option id '$id'.")
                ids += id
            }
            return ids
        }

        internal fun decision(response: AgentResponse, ids: List<String>): Decision {
            val structured = response.structured?.objectValue
            val raw = structured?.get("choice")?.stringValue?.trim().orEmpty()
            // oam-core already matched near-misses against the enum; match case-insensitively once more
            // for safety (and for backends that bypass validation).
            val id = ids.firstOrNull { it == raw } ?: ids.firstOrNull { it.equals(raw, ignoreCase = true) }
                ?: throw AgentError(AgentErrorCode.GENERATION_FAILED, "The model chose '$raw', which is not one of: ${ids.joinToString(", ")}.")
            val confidence = structured?.get("confidence")?.doubleValue?.takeIf { it.isFinite() }?.roundToInt() ?: 50
            return Decision(
                optionId = id,
                reasoning = structured?.get("reasoning")?.stringValue?.trim().orEmpty(),
                confidence = confidence.coerceIn(0, 100),
                toolCalls = response.toolCalls,
                usage = response.usage,
            )
        }
    }
}
