package com.spacecorps.oam.game

/** Small, conservative fixes for quirks of small on-device models. */
internal object TextCleanup {
    private val openingQuotes = setOf('"', '“', '\'', '‘', '«')
    private val closingQuotes = setOf('"', '”', '\'', '’', '»')
    private val doubleQuotes = setOf('"', '“', '”')

    /**
     * Cleans a spoken line: trims whitespace, removes a leading speaker label
     * (`Gorm:`, `**Gorm**:`) and quotes wrapping the whole line.
     */
    fun spokenLine(raw: String, speaker: String): String {
        var text = stripSpeakerLabel(raw.trim(), speaker)
        if (text.length >= 2 && text.first() in openingQuotes && text.last() in closingQuotes) {
            val inner = text.substring(1, text.length - 1)
            // Only unwrap when the quotes enclose the whole line.
            if (inner.none { it in doubleQuotes }) text = inner.trim()
        }
        return text
    }

    /**
     * Cleans the prefix of a line that is still streaming. Returns `null`
     * while the text could still turn out to be a speaker label, so nothing
     * that would later be removed is shown.
     */
    fun streamingLine(raw: String, speaker: String): String? {
        val text = raw.trimStart()
        val lowered = text.lowercase()
        // "Gor" could still become "Gorm:"; wait for more text.
        if (text.isNotEmpty() && speakerLabels(speaker).any { it.length > text.length && it.lowercase().startsWith(lowered) }) return null
        var result = stripSpeakerLabel(text, speaker)
        val first = result.firstOrNull()
        if (first != null && first in openingQuotes && first != '\'') result = result.substring(1).trimStart()
        return result
    }

    fun stripSpeakerLabel(text: String, speaker: String): String {
        if (speaker.isEmpty()) return text
        val lowered = text.lowercase()
        for (label in speakerLabels(speaker)) {
            if (lowered.startsWith(label.lowercase())) return text.substring(label.length).trim()
        }
        return text
    }

    private fun speakerLabels(speaker: String): List<String> =
        if (speaker.isEmpty()) emptyList() else listOf("$speaker says:", "**$speaker**:", "**$speaker:**", "*$speaker*:", "$speaker:")

    /** The first non-empty line of [text], cleaned as a spoken line. */
    fun singleLine(text: String, speaker: String): String {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return spokenLine(first, speaker)
    }

    /** Trimmed text, or `null` when blank. */
    fun trimmedOrNull(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }

    /** Ensures [text] ends with terminal punctuation. */
    fun sentence(text: String): String {
        val last = text.lastOrNull() ?: return text
        return if (last in ".!?\"'") text else "$text."
    }

    /** Joins list items into one sentence (`"a; b; c."`), or `null` when there are none. */
    fun list(items: List<String>): String? {
        val cleaned = items.mapNotNull(::trimmedOrNull)
        if (cleaned.isEmpty()) return null
        return sentence(cleaned.joinToString("; ") { it.removeSuffix(".") })
    }
}
