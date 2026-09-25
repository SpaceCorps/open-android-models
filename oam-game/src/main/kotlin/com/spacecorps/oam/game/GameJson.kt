package com.spacecorps.oam.game

import com.spacecorps.oam.OamJson
import com.spacecorps.oam.ToolChoice
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder

/**
 * The JSON configuration for the game types ([Persona], [NPCOptions],
 * [NPCMemory], [DialogueTurn], [NPCSaveState], [Decision], …).
 *
 * It is [OamJson] (key order kept, unknown keys ignored) plus two settings
 * that match open-apple-models' lenient `Codable` decoding, so a game can
 * write partial JSON and get defaults for the rest:
 *
 * - a JSON `null` for a field that cannot be null takes the field's default;
 * - `null` values are written out, so an option whose `null` means something
 *   (`secretsUnlockAtRelationship: null`) survives a round trip.
 *
 * ```kotlin
 * val persona = GameJson.decodeFromString(Persona.serializer(), """{"name": "Mira", "role": null}""")
 * ```
 */
public val GameJson: Json = Json(OamJson) {
    coerceInputValues = true
    explicitNulls = true
}

/** Serializes a [ToolChoice] in the wire form of [ToolChoice.toJson]. */
internal object ToolChoiceSerializer : KSerializer<ToolChoice> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ToolChoice) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("ToolChoice can only be written as JSON.")
        json.encodeJsonElement(value.toJson())
    }

    override fun deserialize(decoder: Decoder): ToolChoice {
        val json = decoder as? JsonDecoder ?: throw SerializationException("ToolChoice can only be read from JSON.")
        return try {
            ToolChoice.fromJson(json.decodeJsonElement())
        } catch (error: IllegalArgumentException) {
            throw SerializationException("Invalid toolChoice: ${error.message}", error)
        }
    }
}
