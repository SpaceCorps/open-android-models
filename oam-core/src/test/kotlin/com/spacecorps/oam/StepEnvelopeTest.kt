package com.spacecorps.oam

import com.spacecorps.oam.StepEnvelope.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StepEnvelopeTest {
    private val tools = listOf("check_menu", "take_order")

    private fun parse(text: String, allowRespond: Boolean = true) = StepEnvelope.parse(text, tools, allowRespond)

    private fun call(text: String): Result.Call = assertIs<Result.Call>(parse(text), text)

    @Test
    fun canonicalEnvelopes() {
        assertEquals(Result.Respond, parse("""{"action": "respond", "arguments": {}}"""))
        val order = call("""{"action": "take_order", "arguments": {"item": "ale", "quantity": 2}}""")
        assertEquals("take_order", order.tool)
        assertEquals(jsonObjectOf("item" to "ale", "quantity" to 2), order.candidates.first())
    }

    @Test
    fun fencesProseAndTrailingText() {
        assertEquals("check_menu", call("Sure! ```json\n{\"action\": \"check_menu\"}\n``` Let me check.").tool)
        assertEquals("check_menu", call("I think the best step is {'action': 'check_menu', 'arguments': {},} because the guest asked.").tool)
        assertEquals(Result.Respond, parse("""{"action": "respond", "arguments": {}}{"action": "check_menu"}"""))
    }

    @Test
    fun keySynonymsAndOpenAIShapes() {
        assertEquals("check_menu", call("""{"tool": "check_menu", "args": {}}""").tool)
        assertEquals("check_menu", call("""{"tool_name": "check_menu", "parameters": {}}""").tool)
        assertEquals("take_order", call("""{"name": "take_order", "input": {"item": "ale"}}""").tool)
        val openAI = call("""{"type": "function", "function": {"name": "take_order", "arguments": "{\"item\": \"stew\", \"quantity\": 1}"}}""")
        assertEquals(jsonObjectOf("item" to "stew", "quantity" to 1), openAI.candidates.first())
        val wrapped = call("""{"tool_call": {"name": "take_order", "arguments": {"item": "bread"}}}""")
        assertEquals(jsonObjectOf("item" to "bread"), wrapped.candidates.first())
        val array = call("""{"tool_calls": [{"function": {"name": "check_menu", "arguments": "{}"}}]}""")
        assertEquals("check_menu", array.tool)
        val nested = call("""{"action": {"name": "take_order", "arguments": {"item": "ale"}}}""")
        assertEquals(jsonObjectOf("item" to "ale"), nested.candidates.first())
    }

    @Test
    fun respondSynonyms() {
        for (word in listOf("reply", "answer", "respond_directly", "Respond", "none", "final_answer", "talk", "respondDirectly")) {
            assertEquals(Result.Respond, parse("""{"action": "$word"}"""), word)
        }
        assertEquals(Result.Respond, parse("""{"action": "respond", "arguments": {"text": "Hello there!"}}"""))
        assertEquals(Result.Respond, parse("""{"respond": "Welcome!"}"""))
    }

    @Test
    fun toolNameNormalization() {
        assertEquals("check_menu", call("""{"action": "check_menu()"}""").tool)
        assertEquals("check_menu", call("""{"action": "Check Menu"}""").tool)
        assertEquals("check_menu", call("""{"action": "functions.check_menu"}""").tool)
        assertEquals("take_order", call("""{"action": "takeOrder"}""").tool)
        assertEquals("take_order", call("""{"action": "TAKE-ORDER"}""").tool)
        // A user tool wins over a respond synonym.
        assertEquals("reply", assertIs<Result.Call>(StepEnvelope.parse("""{"action": "reply"}""", listOf("reply"), true)).tool)
    }

    @Test
    fun argumentsNextToTheActionOrAsTheOnlyKey() {
        val flat = call("""{"action": "take_order", "item": "ale", "quantity": 2, "reasoning": "asked for ale"}""")
        assertEquals(jsonObjectOf("item" to "ale", "quantity" to 2), flat.candidates.last())
        val keyed = call("""{"take_order": {"item": "stew", "quantity": 1}}""")
        assertEquals(jsonObjectOf("item" to "stew", "quantity" to 1), keyed.candidates.first())
        // With both, the explicit arguments come first; the caller takes the first that validates.
        val both = call("""{"action": "take_order", "arguments": {}, "item": "bread", "quantity": 3}""")
        assertEquals(listOf(EmptyJsonObject, jsonObjectOf("item" to "bread", "quantity" to 3)), both.candidates)
        // Arguments written as a list are not an object; the call still parses with empty arguments.
        assertEquals(listOf(EmptyJsonObject), call("""{"action": "check_menu", "arguments": ["stew"]}""").candidates)
    }

    @Test
    fun bareWordsWithoutJson() {
        assertEquals("check_menu", call("check_menu").tool)
        assertEquals("check_menu", call("`check_menu()`").tool)
        assertEquals(Result.Respond, parse("respond."))
        assertIs<Result.Invalid>(parse("Welcome to the inn, traveler! What can I get you?"))
    }

    @Test
    fun copiedUnionTemplatesTakeTheFirstAlternative() {
        assertEquals("check_menu", call("""{"action": "check_menu" | "take_order" | "respond", "arguments": {}}""").tool)
    }

    @Test
    fun invalidOutputsExplainTheProblem() {
        assertEquals(
            "\"order_food\" is not an available action; use one of: check_menu, take_order, respond",
            assertIs<Result.Invalid>(parse("""{"action": "order_food", "arguments": {}}""")).problem,
        )
        assertEquals(
            "\"check_menu | take_order | respond\" is not an available action; use one of: check_menu, take_order, respond",
            assertIs<Result.Invalid>(parse("""{"action": "check_menu | take_order | respond"}""")).problem,
        )
        assertEquals("the JSON object has no \"action\"", assertIs<Result.Invalid>(parse("""{"item": "ale", "quantity": 2}""")).problem)
        assertEquals("the answer was not a JSON object", assertIs<Result.Invalid>(parse("")).problem)
        val required = assertIs<Result.Invalid>(parse("""{"action": "respond"}""", allowRespond = false))
        assertTrue(required.problem.startsWith("\"respond\" is not allowed now; you must call one of the tools: check_menu, take_order"))
    }

    @Test
    fun truncatedEnvelopesStillParse() {
        val truncated = call("""{"action": "take_order", "arguments": {"item": "ale", "quantity": 2""")
        assertEquals(jsonObjectOf("item" to "ale", "quantity" to 2), truncated.candidates.first())
    }

    @Test
    fun argumentCandidatesForANamedTool() {
        val plain = jsonObjectOf("item" to "ale", "quantity" to 1)
        assertEquals(listOf(plain), StepEnvelope.argumentCandidates(plain, "take_order"))
        val enveloped = jsonObjectOf("action" to "take_order", "arguments" to plain)
        assertEquals(plain, StepEnvelope.argumentCandidates(enveloped, "take_order")[1])
        val wrapped = jsonObjectOf("arguments" to plain)
        assertTrue(plain in StepEnvelope.argumentCandidates(wrapped, "take_order"))
    }
}
