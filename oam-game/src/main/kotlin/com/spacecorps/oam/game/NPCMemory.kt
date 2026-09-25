package com.spacecorps.oam.game

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * What an [NPC] remembers across turns and saves: facts about the player
 * and world, its attitude toward the player, and a running summary of older
 * conversation.
 *
 * Memory is injected into the NPC's instructions every turn, so keep it
 * short (see [NPCOptions.maxFacts]). Instances are immutable; the
 * `…ing` functions return updated copies.
 *
 * ```kotlin
 * npc.updateMemory { it.remembering("Aria returned the lost ring.").adjustingRelationship(20) }
 * ```
 *
 * @param facts Remembered facts, oldest first.
 * @param relationship Attitude toward the player; clamped to [RELATIONSHIP_RANGE].
 * @param summary Summary of conversation that was compacted out of the history.
 */
@Serializable(with = NPCMemorySerializer::class)
public class NPCMemory(
    facts: List<String> = emptyList(),
    relationship: Int = 0,
    summary: String? = null,
) {
    /** Remembered facts, oldest first. */
    public val facts: List<String> = facts.toList()

    /** Attitude toward the player, `-100..100` (below -60 hostile, above 60 trusting). */
    public val relationship: Int = clamp(relationship.toLong())

    /** Summary of conversation that was compacted out of the history. */
    public val summary: String? = summary

    /** A one-word description of [relationship]: hostile, unfriendly, neutral, friendly or trusting. */
    public val attitude: String get() = attitude(relationship)

    /** A copy with the given fields replaced ([relationship] is clamped). */
    public fun copy(
        facts: List<String> = this.facts,
        relationship: Int = this.relationship,
        summary: String? = this.summary,
    ): NPCMemory = NPCMemory(facts, relationship, summary)

    /** Whether a fact equal to [fact] (ignoring case, spaces and punctuation) is known. */
    public fun knows(fact: String): Boolean {
        val key = normalized(fact)
        return facts.any { normalized(it) == key }
    }

    /**
     * Adds [fact] unless it is blank or already known (see [knows]). When
     * [limit] is exceeded, the oldest facts are dropped.
     *
     * @return The updated memory, or this memory when the fact was not new.
     */
    public fun remembering(fact: String, limit: Int? = null): NPCMemory {
        val cleaned = TextCleanup.trimmedOrNull(fact) ?: return this
        if (knows(cleaned)) return this
        var updated = facts + cleaned
        if (limit != null && limit >= 0 && updated.size > limit) updated = updated.drop(updated.size - limit)
        return copy(facts = updated)
    }

    /** Adds [delta] to [relationship] (clamped, without overflow). */
    public fun adjustingRelationship(delta: Int): NPCMemory =
        NPCMemory(facts, clamp(relationship.toLong() + delta), summary)

    /** The memory block appended to the NPC's instructions, or `null` when there is nothing worth saying. */
    internal fun note(includeRelationship: Boolean): String? {
        val lines = ArrayList<String>()
        if (includeRelationship || relationship != 0) {
            lines += "- You feel $attitude toward the player ($relationship on a scale from -100 to 100)."
        }
        TextCleanup.list(facts)?.let { lines += "- You remember: $it" }
        TextCleanup.trimmedOrNull(summary)?.let { lines += "- Earlier conversation: $it" }
        if (lines.isEmpty()) return null
        return "Memory:\n" + lines.joinToString("\n")
    }

    override fun equals(other: Any?): Boolean =
        other is NPCMemory && other.facts == facts && other.relationship == relationship && other.summary == summary

    override fun hashCode(): Int = (facts.hashCode() * 31 + relationship) * 31 + summary.hashCode()

    override fun toString(): String =
        "NPCMemory(relationship=$relationship, facts=$facts, summary=${summary?.let { "\"$it\"" } ?: "null"})"

    public companion object {
        /** Allowed values of [relationship]. */
        public val RELATIONSHIP_RANGE: IntRange = -100..100

        /** The one-word attitude for a relationship [value] (see [NPCMemory.attitude]). */
        public fun attitude(value: Int): String = when {
            value < -60 -> "hostile"
            value < -20 -> "unfriendly"
            value <= 20 -> "neutral"
            value <= 60 -> "friendly"
            else -> "trusting"
        }

        private fun clamp(value: Long): Int =
            value.coerceIn(RELATIONSHIP_RANGE.first.toLong(), RELATIONSHIP_RANGE.last.toLong()).toInt()

        private fun normalized(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }
    }
}

/** The JSON form of [NPCMemory]: `{"facts", "relationship", "summary"?}`. */
@Serializable
private class NPCMemorySurrogate(
    val facts: List<String> = emptyList(),
    val relationship: Int = 0,
    val summary: String? = null,
)

internal object NPCMemorySerializer : KSerializer<NPCMemory> {
    override val descriptor: SerialDescriptor = NPCMemorySurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: NPCMemory) {
        encoder.encodeSerializableValue(NPCMemorySurrogate.serializer(), NPCMemorySurrogate(value.facts, value.relationship, value.summary))
    }

    override fun deserialize(decoder: Decoder): NPCMemory {
        val surrogate = decoder.decodeSerializableValue(NPCMemorySurrogate.serializer())
        return NPCMemory(surrogate.facts, surrogate.relationship, surrogate.summary)
    }
}
