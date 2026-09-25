package com.spacecorps.oam.game

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Who a character is: the data an [NPC] turns into compact instructions for
 * the on-device model.
 *
 * Keep every field short. Gemini Nano takes about 4000 input tokens for
 * instructions, tools, the conversation and the task together, and a small
 * model follows a few crisp lines better than a page of lore. Put large or
 * changing facts (stock, prices, quest state) in tools or a [WorldState]
 * instead.
 *
 * Serializable with defaults (see [GameJson]): a JSON persona only needs a `name`.
 *
 * ```kotlin
 * val gorm = Persona(
 *     name = "Gorm",
 *     role = "the village blacksmith",
 *     personality = "Gruff and proud, but fair. Secretly soft-hearted.",
 *     speakingStyle = "Short, blunt sentences. Calls people 'lad' or 'lass'.",
 *     goals = listOf("Sell his weapons at a fair price"),
 *     secrets = listOf("He forged the blade that killed the old king."),
 * )
 * ```
 *
 * @property name The character's name, for example `"Gorm"`.
 * @property role A short noun phrase, for example `"the village blacksmith"`.
 * @property personality Temperament in a sentence or two.
 * @property speakingStyle How the character talks: vocabulary, rhythm, catchphrases.
 * @property backstory A few sentences of history.
 * @property goals What the character wants.
 * @property secrets Guarded knowledge the character keeps from the player until it is earned. See [SecretVisibility].
 * @property knowledge Facts the character knows and may share freely.
 * @property defaultEmotion Emotion used when the model gives none, and for fallback lines.
 * @property maxSentences Upper bound on sentences per reply (at least 1 is used).
 */
@Serializable
public data class Persona(
    public val name: String,
    public val role: String = "",
    public val personality: String = "",
    public val speakingStyle: String = "",
    public val backstory: String = "",
    public val goals: List<String> = emptyList(),
    public val secrets: List<String> = emptyList(),
    public val knowledge: List<String> = emptyList(),
    public val defaultEmotion: Emotion = Emotion.NEUTRAL,
    public val maxSentences: Int = 2,
) {
    /** How a persona's [secrets] appear in its instructions. */
    @Serializable
    public enum class SecretVisibility {
        /** Included, with a rule to keep them hidden unless the player earns them. */
        @SerialName("guarded")
        GUARDED,

        /** Included, and the character may share them when asked. */
        @SerialName("shareable")
        SHAREABLE,

        /** Left out entirely: the model cannot leak what it never sees. */
        @SerialName("hidden")
        HIDDEN,
    }

    /**
     * Renders compact system instructions (typically 100–250 tokens) tuned
     * for a small on-device model: role-play framing, the character sheet,
     * then a few short rules (stay in character, never mention being an AI,
     * reply length, use tools instead of inventing facts and say the facts
     * they give, guard secrets).
     *
     * @param extra Additional lines appended at the end (setting, quest hints, rules).
     * @param usesTools Whether the character has tools for looking up facts.
     *   When false, the model is told to admit ignorance instead.
     * @param secretVisibility How to include [secrets].
     */
    public fun instructions(
        extra: String? = null,
        usesTools: Boolean = true,
        secretVisibility: SecretVisibility = SecretVisibility.GUARDED,
    ): String {
        val lines = ArrayList<String>()
        val who = TextCleanup.trimmedOrNull(role)?.let { "$name, $it" } ?: name
        lines += "You play $who, a character in a video game."
        TextCleanup.trimmedOrNull(personality)?.let { lines += "Personality: ${TextCleanup.sentence(it)}" }
        TextCleanup.trimmedOrNull(speakingStyle)?.let { lines += "Speaking style: ${TextCleanup.sentence(it)}" }
        TextCleanup.trimmedOrNull(backstory)?.let { lines += "Background: ${TextCleanup.sentence(it)}" }
        TextCleanup.list(goals)?.let { lines += "Goals: $it" }
        TextCleanup.list(knowledge)?.let { lines += "You know: $it" }
        val secretText = TextCleanup.list(secrets)
        if (secretText != null && secretVisibility != SecretVisibility.HIDDEN) lines += "Your secret: $secretText"

        lines += "Rules:"
        lines += "- Speak only as $name. Never say you are an AI, a model or an assistant."
        val limit = maxSentences.coerceAtLeast(1)
        lines += "- Reply in at most $limit short ${if (limit == 1) "sentence" else "sentences"}."
        if (usesTools) {
            lines += "- Use your tools to check facts about the world, such as items, prices, people and places. Never invent them."
            lines += "- When a tool gives you facts, say the specific ones the player asked about (names, prices, numbers)."
        } else {
            lines += "- If you do not know a fact about the world, say so in character. Never invent it."
        }
        if (secretText != null) {
            when (secretVisibility) {
                SecretVisibility.GUARDED -> lines += "- Keep your secret unless the player has truly earned your trust."
                SecretVisibility.SHAREABLE -> lines += "- The player has earned your trust. You may share your secret if asked."
                SecretVisibility.HIDDEN -> Unit
            }
        }
        TextCleanup.trimmedOrNull(extra)?.let { lines += it }
        return lines.joinToString("\n")
    }
}
