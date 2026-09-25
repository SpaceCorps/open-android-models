package com.spacecorps.oam

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/** Thrown when text cannot be parsed as JSON. [offset] is the character index of the problem. */
public class JsonParseException(message: String, public val offset: Int) : IllegalArgumentException(message)

/**
 * A forgiving JSON parser for model output.
 *
 * Small on-device models write *almost* JSON. Besides standard JSON this
 * accepts single-quoted strings, unquoted keys, trailing commas, `//` and
 * `/* */` comments, and Python literals (`True`, `False`, `None`). Object key
 * order is preserved.
 *
 * With `partial = true` it also accepts truncated input (a response that is
 * still streaming, or cut off by a token limit): open strings, arrays and
 * objects are closed, and a trailing key without a value is dropped.
 */
public object LenientJson {
    /**
     * Parses [text] as one JSON value. Trailing non-whitespace is an error.
     *
     * @throws JsonParseException if the text is not (lenient) JSON.
     */
    public fun parse(text: String, partial: Boolean = false): JsonElement {
        val parser = Parser(text, partial)
        parser.skipWhitespace()
        val value = parser.parseValue() ?: throw JsonParseException("Expected a JSON value.", parser.index)
        parser.skipWhitespace()
        if (parser.index < text.length) {
            throw JsonParseException("Unexpected '${text[parser.index]}' after the JSON value.", parser.index)
        }
        return value
    }

    /** Like [parse], but returns `null` instead of throwing. */
    public fun parseOrNull(text: String, partial: Boolean = false): JsonElement? =
        try {
            parse(text, partial)
        } catch (_: JsonParseException) {
            null
        }

    private class Parser(val text: String, val partial: Boolean) {
        var index = 0

        private fun fail(message: String): Nothing = throw JsonParseException(message, index)

        private val atEnd get() = index >= text.length

        fun skipWhitespace() {
            while (index < text.length) {
                val char = text[index]
                when {
                    char.isWhitespace() || char == '﻿' -> index++
                    char == '/' && index + 1 < text.length && text[index + 1] == '/' -> {
                        while (index < text.length && text[index] != '\n') index++
                    }
                    char == '/' && index + 1 < text.length && text[index + 1] == '*' -> {
                        val end = text.indexOf("*/", index + 2)
                        index = if (end < 0) text.length else end + 2
                    }
                    else -> return
                }
            }
        }

        /**
         * Skips `| "b" | "c"` after a value: small models sometimes copy a
         * union from the prompt instead of choosing. The first value wins.
         */
        fun skipAlternatives() {
            while (true) {
                val save = index
                skipWhitespace()
                if (index >= text.length || text[index] != '|') {
                    index = save
                    return
                }
                index++
                skipWhitespace()
                if (atEnd) return
                try {
                    parseValue()
                } catch (_: JsonParseException) {
                    index = save
                    return
                }
            }
        }

        /** Returns null only in partial mode, when the input ends before a value. */
        fun parseValue(): JsonElement? {
            skipWhitespace()
            if (atEnd) {
                if (partial) return null
                fail("Unexpected end of input.")
            }
            return when (val char = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"', '\'' -> JsonPrimitive(parseString(char))
                else -> if (char == '-' || char == '+' || char == '.' || char.isDigit()) parseNumber() else parseWord()
            }
        }

        private fun parseObject(): JsonObject {
            index++ // {
            val members = LinkedHashMap<String, JsonElement>()
            while (true) {
                skipWhitespace()
                if (atEnd) {
                    if (partial) return JsonObject(members)
                    fail("Unterminated object.")
                }
                when (text[index]) {
                    '}' -> {
                        index++
                        return JsonObject(members)
                    }
                    ',' -> {
                        index++
                        continue
                    }
                }
                val key = parseKey() ?: return JsonObject(members) // partial: input ended inside the key
                skipWhitespace()
                if (atEnd) {
                    if (partial) return JsonObject(members)
                    fail("Expected ':' after key \"$key\".")
                }
                if (text[index] != ':' && text[index] != '=') fail("Expected ':' after key \"$key\".")
                index++
                val value = parseValue() ?: return JsonObject(members)
                members[key] = value
                skipAlternatives()
                skipWhitespace()
                if (atEnd) {
                    if (partial) return JsonObject(members)
                    fail("Unterminated object.")
                }
                when (text[index]) {
                    ',' -> index++
                    '}' -> Unit
                    else -> fail("Expected ',' or '}' in object.")
                }
            }
        }

        private fun parseKey(): String? {
            val char = text[index]
            if (char == '"' || char == '\'') {
                val key = parseString(char)
                // A partial key (input ended inside it) is dropped.
                return if (lastStringClosed) key else null
            }
            val start = index
            while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '_' || text[index] == '$' || text[index] == '-')) index++
            if (index == start) fail("Expected a property name.")
            if (partial && atEnd) return null
            return text.substring(start, index)
        }

        private fun parseArray(): JsonArray {
            index++ // [
            val items = ArrayList<JsonElement>()
            while (true) {
                skipWhitespace()
                if (atEnd) {
                    if (partial) return JsonArray(items)
                    fail("Unterminated array.")
                }
                when (text[index]) {
                    ']' -> {
                        index++
                        return JsonArray(items)
                    }
                    ',' -> {
                        index++
                        continue
                    }
                }
                val value = parseValue() ?: return JsonArray(items)
                items += value
                skipAlternatives()
                skipWhitespace()
                if (atEnd) {
                    if (partial) return JsonArray(items)
                    fail("Unterminated array.")
                }
                when (text[index]) {
                    ',' -> index++
                    ']' -> Unit
                    else -> fail("Expected ',' or ']' in array.")
                }
            }
        }

        /** Whether the last string parsed ended with its closing quote (false only in partial mode). */
        private var lastStringClosed = true

        /** Parses a string quoted with [quote]. In partial mode an unterminated string is returned as is. */
        private fun parseString(quote: Char): String {
            index++ // opening quote
            val builder = StringBuilder()
            lastStringClosed = false
            while (true) {
                if (atEnd) {
                    if (partial) return builder.toString()
                    fail("Unterminated string.")
                }
                val char = text[index++]
                when {
                    char == quote -> {
                        lastStringClosed = true
                        return builder.toString()
                    }
                    char == '\\' -> {
                        if (atEnd) {
                            if (partial) return builder.toString()
                            fail("Unterminated escape sequence.")
                        }
                        when (val escaped = text[index++]) {
                            '"', '\\', '/', '\'' -> builder.append(escaped)
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) {
                                    if (partial) {
                                        index = text.length
                                        return builder.toString()
                                    }
                                    fail("Truncated \\u escape.")
                                }
                                val code = text.substring(index, index + 4).toIntOrNull(16) ?: fail("Invalid \\u escape.")
                                builder.append(code.toChar())
                                index += 4
                            }
                            else -> builder.append(escaped)
                        }
                    }
                    // Models sometimes emit raw newlines inside strings; keep them.
                    else -> builder.append(char)
                }
            }
        }

        private fun parseNumber(): JsonElement? {
            val start = index
            if (text[index] == '-' || text[index] == '+') index++
            while (index < text.length && (text[index].isDigit() || text[index] in ".eE+-")) index++
            var literal = text.substring(start, index).removePrefix("+")
            if (partial && atEnd) literal = literal.trimEnd('.', 'e', 'E', '+', '-')
            if (literal.isEmpty() || literal == "-") {
                if (partial && atEnd) return null
                fail("Invalid number.")
            }
            literal.toLongOrNull()?.let { return JsonPrimitive(it) }
            val decimal = try {
                BigDecimal(literal)
            } catch (_: NumberFormatException) {
                index = start
                fail("Invalid number '$literal'.")
            }
            val asDouble = decimal.toDouble()
            return if (asDouble.isInfinite()) JsonPrimitive(decimal) else JsonPrimitive(asDouble)
        }

        /** Literals, plus bare words (`{action: respond}`), which are read as strings. */
        private fun parseWord(): JsonElement? {
            val start = index
            while (index < text.length && (text[index].isLetterOrDigit() || text[index] in "_-.")) index++
            val word = text.substring(start, index)
            when (word) {
                "true", "True" -> return JsonPrimitive(true)
                "false", "False" -> return JsonPrimitive(false)
                "null", "None", "nil" -> return JsonNull
            }
            if (partial && atEnd) return null
            if (word.isEmpty()) fail("Unexpected '${text[index]}'.")
            return JsonPrimitive(word)
        }
    }
}

/**
 * Finds JSON inside free-form model output: strips Markdown code fences and
 * surrounding prose, and returns the first balanced JSON object (or value)
 * that parses.
 */
public object JsonExtraction {
    private val fence = Regex("```[a-zA-Z0-9_-]*[ \\t]*\\r?\\n?(.*?)(?:```|$)", RegexOption.DOT_MATCHES_ALL)

    /**
     * Candidate texts to search, most likely first: the contents of each code
     * fence, then the whole text.
     */
    internal fun candidates(text: String): List<String> {
        if (!text.contains("```")) return listOf(text)
        val fenced = fence.findAll(text).map { it.groupValues[1] }.filter { it.isNotBlank() }.toList()
        return fenced + text.replace("```", " ")
    }

    /**
     * The first JSON object in [text], or `null`.
     *
     * @param allowTruncated Also accept an object cut off at the end of the
     *   text (closing it), when no complete object is found.
     */
    public fun firstObject(text: String, allowTruncated: Boolean = true): JsonObject? =
        firstValue(text, allowTruncated) { it == '{' } as? JsonObject

    /** The first JSON object or array in [text], or `null`. */
    public fun firstValue(text: String, allowTruncated: Boolean = true): JsonElement? =
        firstValue(text, allowTruncated) { it == '{' || it == '[' }

    private fun firstValue(text: String, allowTruncated: Boolean, opens: (Char) -> Boolean): JsonElement? {
        val candidates = candidates(text)
        for (candidate in candidates) {
            var start = candidate.indexOfFirst(opens)
            while (start >= 0) {
                val end = balancedEnd(candidate, start)
                if (end != null) {
                    LenientJson.parseOrNull(candidate.substring(start, end + 1))?.let { return it }
                }
                start = nextIndex(candidate, start + 1, opens)
            }
        }
        if (!allowTruncated) return null
        for (candidate in candidates) {
            val start = candidate.indexOfFirst(opens)
            if (start >= 0 && balancedEnd(candidate, start) == null) {
                LenientJson.parseOrNull(candidate.substring(start).trimEnd(), partial = true)?.let { return it }
            }
        }
        return null
    }

    /**
     * True once [text] holds a complete JSON object (or, with [arrays], array)
     * that parses. Used to stop reading a streamed answer early.
     */
    public fun hasCompleteValue(text: String, arrays: Boolean = false): Boolean {
        val opens: (Char) -> Boolean = if (arrays) ({ it == '{' || it == '[' }) else ({ it == '{' })
        var start = text.indexOfFirst(opens)
        while (start >= 0) {
            val end = balancedEnd(text, start) ?: return false
            if (LenientJson.parseOrNull(text.substring(start, end + 1)) != null) return true
            start = nextIndex(text, start + 1, opens)
        }
        return false
    }

    private fun nextIndex(text: String, from: Int, opens: (Char) -> Boolean): Int {
        for (i in from until text.length) if (opens(text[i])) return i
        return -1
    }

    /**
     * The index of the bracket closing the one at [start], skipping brackets
     * inside strings, or `null` if the text ends first.
     */
    internal fun balancedEnd(text: String, start: Int): Int? {
        var depth = 0
        var quote: Char? = null
        var i = start
        while (i < text.length) {
            val char = text[i]
            if (quote != null) {
                if (char == '\\') {
                    i += 2
                    continue
                }
                if (char == quote) quote = null
            } else {
                when (char) {
                    '"' -> quote = char
                    // A single quote opens a string only where a value or key can start.
                    '\'' -> if (previousSignificant(text, i) in "{[,:") quote = char
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return null
    }

    private fun previousSignificant(text: String, index: Int): Char {
        var i = index - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        return if (i >= 0) text[i] else ' '
    }
}
