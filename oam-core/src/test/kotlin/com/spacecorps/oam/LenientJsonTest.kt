package com.spacecorps.oam

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LenientJsonTest {
    @Test
    fun parsesStandardJsonKeepingKeyOrder() {
        val value = LenientJson.parse("""{"z": 1, "a": [true, null, "x"], "m": {"k": 2.5}}""") as JsonObject
        assertEquals(listOf("z", "a", "m"), value.keys.toList())
        assertEquals(1L, value["z"]!!.longValue)
        assertEquals(JsonArray(listOf(JsonPrimitive(true), JsonNull, JsonPrimitive("x"))), value["a"])
        assertEquals(2.5, value["m"]!!.objectValue!!["k"]!!.doubleValue)
    }

    @Test
    fun acceptsSingleQuotesUnquotedKeysTrailingCommasCommentsAndPythonLiterals() {
        val value = LenientJson.parse(
            """
            {
              // a comment
              action: 'take_order', /* inline */
              'arguments': {'item': "ale", qty: 2,},
              ok: True, missing: None, no: False,
            }
            """.trimIndent(),
        ) as JsonObject
        assertEquals("take_order", value["action"]!!.stringValue)
        assertEquals("ale", value["arguments"]!!.objectValue!!["item"]!!.stringValue)
        assertEquals(2, value["arguments"]!!.objectValue!!["qty"]!!.intValue)
        assertEquals(true, value["ok"]!!.boolValue)
        assertEquals(JsonNull, value["missing"])
        assertEquals(false, value["no"]!!.boolValue)
    }

    @Test
    fun readsBareWordsAsStrings() {
        val value = LenientJson.parse("{action: respond, arguments: {}}") as JsonObject
        assertEquals("respond", value["action"]!!.stringValue)
    }

    @Test
    fun handlesEscapes() {
        val value = LenientJson.parse("""{"s": "a\"b\\c\né", 'q': 'it\'s'}""") as JsonObject
        assertEquals("a\"b\\c\né", value["s"]!!.stringValue)
        assertEquals("it's", value["q"]!!.stringValue)
    }

    @Test
    fun numbers() {
        assertEquals(JsonPrimitive(-12L), LenientJson.parse("-12"))
        assertEquals(1.5e3, LenientJson.parse("1.5e3").doubleValue)
        assertEquals(3L, LenientJson.parse("3.0").longValue)
        assertNull(LenientJson.parse("3.5").longValue)
        assertEquals(12L, LenientJson.parse("+12").longValue)
    }

    @Test
    fun rejectsGarbageWithOffsets() {
        val error = assertFailsWith<JsonParseException> { LenientJson.parse("""{"a": 1} extra""") }
        assertEquals(9, error.offset)
        assertFailsWith<JsonParseException> { LenientJson.parse("""{"a": }""") }
        assertFailsWith<JsonParseException> { LenientJson.parse("""{"a": 1""") }
        assertFailsWith<JsonParseException> { LenientJson.parse("") }
        assertNull(LenientJson.parseOrNull("[1, 2"))
    }

    @Test
    fun partialModeClosesOpenContainersAndDropsIncompleteKeys() {
        assertEquals(
            jsonObjectOf("emotion" to "happy", "line" to "Welcome, trav"),
            LenientJson.parse("""{"emotion": "happy", "line": "Welcome, trav""", partial = true),
        )
        assertEquals(jsonObjectOf("a" to 1), LenientJson.parse("""{"a": 1, "li""", partial = true))
        assertEquals(jsonObjectOf("a" to 1), LenientJson.parse("""{"a": 1, "b":""", partial = true))
        assertEquals(jsonObjectOf("a" to listOf(1, 2)), LenientJson.parse("""{"a": [1, 2""", partial = true))
        assertEquals(jsonObjectOf("a" to 1), LenientJson.parse("""{"a": 1, "b": tr""", partial = true))
    }

    @Test
    fun extractsFirstObjectFromFencesAndProse() {
        val fenced = "Sure! Here you go:\n```json\n{\"action\": \"respond\", \"arguments\": {}}\n```\nAnything else?"
        assertEquals("respond", JsonExtraction.firstObject(fenced)!!["action"]!!.stringValue)

        val prose = "I will check the menu {not json} then: {\"action\": \"check_menu\"} and more {\"x\": 1}"
        assertEquals("check_menu", JsonExtraction.firstObject(prose)!!["action"]!!.stringValue)

        val braceInString = """{"action": "say", "arguments": {"text": "use } and { freely"}}"""
        assertEquals("use } and { freely", JsonExtraction.firstObject(braceInString)!!["arguments"]!!.objectValue!!["text"]!!.stringValue)

        val apostrophe = "Here's what I'd do: {\"action\": \"respond\"}"
        assertEquals("respond", JsonExtraction.firstObject(apostrophe)!!["action"]!!.stringValue)
    }

    @Test
    fun extractsTruncatedObjectAsLastResort() {
        val truncated = """{"action": "take_order", "arguments": {"item": "ale""""
        val found = JsonExtraction.firstObject(truncated)!!
        assertEquals("ale", found["arguments"]!!.objectValue!!["item"]!!.stringValue)
        assertNull(JsonExtraction.firstObject(truncated, allowTruncated = false))
        assertNull(JsonExtraction.firstObject("no json here"))
    }

    @Test
    fun detectsCompleteValues() {
        assertFalse(JsonExtraction.hasCompleteValue("""{"action": "respo"""))
        assertTrue(JsonExtraction.hasCompleteValue("""{"action": "respond"} and then prose"""))
        assertFalse(JsonExtraction.hasCompleteValue("[1, 2]"))
        assertTrue(JsonExtraction.hasCompleteValue("[1, 2]", arrays = true))
    }

    @Test
    fun jsonOfConvertsKotlinValues() {
        val value = jsonOf(mapOf("b" to listOf(1, 2.5, "x"), "a" to null, "e" to AgentErrorCode.BUSY, "c" to 'c'))
        assertEquals("""{"b":[1,2.5,"x"],"a":null,"e":"BUSY","c":"c"}""", value.toJsonString())
        assertFailsWith<IllegalArgumentException> { jsonOf(Any()) }
    }
}
