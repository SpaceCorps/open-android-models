package com.spacecorps.oam.game

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * How a character feels while speaking a line. Drive portraits, animations,
 * voice or text color from it.
 *
 * Serialized as its lowercase [wireName] (`"angry"`), as in
 * open-apple-models. Unknown values decode as [NEUTRAL], so saves stay
 * loadable if emotions are added or removed.
 *
 * @property wireName The lowercase name used in JSON and in prompts.
 */
@Serializable(with = EmotionSerializer::class)
public enum class Emotion(public val wireName: String) {
    /** No particular feeling. */
    NEUTRAL("neutral"),

    /** Glad, cheerful, pleased. */
    HAPPY("happy"),

    /** Unhappy, sorrowful. */
    SAD("sad"),

    /** Mad, furious, hostile. */
    ANGRY("angry"),

    /** Scared, fearful. */
    AFRAID("afraid"),

    /** Shocked, astonished. */
    SURPRISED("surprised"),

    /** Wary, distrustful. */
    SUSPICIOUS("suspicious"),

    /** Entertained, playful. */
    AMUSED("amused"),

    /** Revolted. */
    DISGUSTED("disgusted"),

    /** Eager, thrilled. */
    EXCITED("excited"),

    /** Interested, intrigued. */
    CURIOUS("curious"),

    /** Puzzled, uncertain. */
    CONFUSED("confused"),

    /** Anxious, concerned. */
    WORRIED("worried"),

    /** Thankful. */
    GRATEFUL("grateful"),

    /** Irritated, grumpy, impatient. */
    ANNOYED("annoyed"),

    /** Confident, smug. */
    PROUD("proud"),
    ;

    override fun toString(): String = wireName

    public companion object {
        private val byName = entries.associateBy { it.wireName }

        private val synonyms: Map<String, Emotion> = mapOf(
            "calm" to NEUTRAL, "content" to NEUTRAL, "indifferent" to NEUTRAL, "bored" to NEUTRAL,
            "joyful" to HAPPY, "glad" to HAPPY, "cheerful" to HAPPY, "pleased" to HAPPY, "friendly" to HAPPY, "warm" to HAPPY,
            "unhappy" to SAD, "sorrowful" to SAD, "melancholy" to SAD, "grieving" to SAD,
            "mad" to ANGRY, "furious" to ANGRY, "enraged" to ANGRY, "hostile" to ANGRY,
            "scared" to AFRAID, "fearful" to AFRAID, "frightened" to AFRAID, "terrified" to AFRAID, "nervous" to WORRIED,
            "shocked" to SURPRISED, "astonished" to SURPRISED, "startled" to SURPRISED,
            "wary" to SUSPICIOUS, "distrustful" to SUSPICIOUS, "skeptical" to SUSPICIOUS, "sceptical" to SUSPICIOUS,
            "amusement" to AMUSED, "playful" to AMUSED, "laughing" to AMUSED,
            "disgust" to DISGUSTED, "revolted" to DISGUSTED,
            "eager" to EXCITED, "thrilled" to EXCITED, "enthusiastic" to EXCITED,
            "interested" to CURIOUS, "intrigued" to CURIOUS,
            "puzzled" to CONFUSED, "uncertain" to CONFUSED,
            "anxious" to WORRIED, "concerned" to WORRIED,
            "thankful" to GRATEFUL,
            "irritated" to ANNOYED, "grumpy" to ANNOYED, "impatient" to ANNOYED, "gruff" to ANNOYED,
            "confident" to PROUD, "smug" to PROUD,
        )

        /**
         * Parses model output leniently: case-insensitive, surrounding
         * punctuation ignored, common synonyms mapped (`"mad"` → [ANGRY],
         * `"scared"` → [AFRAID], `"gruff"` → [ANNOYED]).
         *
         * @return The emotion, or `null` if nothing matches.
         */
        public fun matching(text: String): Emotion? {
            val key = text.lowercase().trim { !it.isLetter() }
            return byName[key] ?: synonyms[key]
        }

        /** The emotion whose [wireName] is exactly [name], or `null`. */
        public fun fromWireName(name: String): Emotion? = byName[name]
    }
}

/** Writes [Emotion.wireName]; reads leniently with [Emotion.matching], unknown values as [Emotion.NEUTRAL]. */
internal object EmotionSerializer : KSerializer<Emotion> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.spacecorps.oam.game.Emotion", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Emotion) {
        encoder.encodeString(value.wireName)
    }

    override fun deserialize(decoder: Decoder): Emotion = Emotion.matching(decoder.decodeString()) ?: Emotion.NEUTRAL
}
