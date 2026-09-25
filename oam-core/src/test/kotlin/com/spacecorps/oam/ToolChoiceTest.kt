package com.spacecorps.oam

import com.spacecorps.oam.testing.ScriptedLanguageModel.Step
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolChoiceTest {
    private fun tools(orders: MutableList<ToolCall> = mutableListOf()) = listOf(Tavern.menu(), Tavern.order(orders))

    @Test
    fun autoAndExplicitOfferEveryToolPlusRespond() = runTest {
        for (choice in listOf(ToolChoice.Auto, ToolChoice.Explicit)) {
            val model = scripted(Step.respond(), Step.Text("Hello!"))
            val agent = Agent(model, tools = tools(), scope = backgroundScope)
            val response = agent.respond("Hi", ToolPolicy(choice = choice))
            assertEquals("Hello!", response.text)
            val decide = response.steps.first()
            assertEquals(ToolCallingMode.ALLOWED, decide.toolCallingMode)
            assertEquals(listOf("check_menu", "take_order"), decide.enabledTools)
            val prompt = model.requests.first().prompt
            assertTrue(prompt.contains("- take_order: Place an order for the guest. Arguments: {\"item\": one of \"ale\", \"stew\", \"bread\" (menu item), \"quantity\": integer 1-10}"), prompt)
            assertTrue(prompt.contains("Assistant can also respond: reply to User without a tool."), prompt)
            assertTrue(prompt.contains("""or {"action": "respond", "arguments": {}}."""), prompt)
        }
    }

    @Test
    fun noneSkipsTheDecision() = runTest {
        val model = scripted(Step.Text("Just a reply."))
        val agent = Agent(model, tools = tools(), scope = backgroundScope)
        val response = agent.respond("What's on the menu?", ToolPolicy(choice = ToolChoice.None))
        assertEquals(listOf(StepKind.RESPOND), response.steps.map { it.kind })
        assertEquals(emptyList(), response.toolCalls)
        assertTrue(model.requests.single().prompt.let { !it.contains("can use these tools") })
    }

    @Test
    fun noToolsMeansNoDecision() = runTest {
        val model = scripted(Step.Text("Hi."))
        val agent = Agent(model, scope = backgroundScope)
        assertEquals(listOf(StepKind.RESPOND), agent.respond("Hi", ToolPolicy(choice = ToolChoice.Required)).steps.map { it.kind })
    }

    @Test
    fun requiredOffersOnlyToolsFirstThenDecidesFreely() = runTest {
        val orders = mutableListOf<ToolCall>()
        val model = scripted(Step.call("check_menu"), Step.call("take_order", "item" to "ale", "quantity" to 1), Step.respond(), Step.Text("Done."))
        val agent = Agent(model, tools = tools(orders), scope = backgroundScope)
        val response = agent.respond("An ale please", ToolPolicy(choice = ToolChoice.Required))
        assertEquals(
            listOf(ToolCallingMode.REQUIRED, ToolCallingMode.ALLOWED, ToolCallingMode.ALLOWED, ToolCallingMode.DISALLOWED),
            response.steps.map { it.toolCallingMode },
        )
        assertEquals(listOf("check_menu", "take_order"), response.toolCalls.map { it.call.name })
        assertEquals(1, orders.size)
        val first = model.requests.first().prompt
        assertTrue(first.contains("Assistant must use one of the tools now."), first)
        assertTrue(!first.contains("can also respond"), first)
    }

    @Test
    fun requiredRejectsRespondAndRepairs() = runTest {
        val model = scripted(Step.respond(), Step.call("check_menu"), Step.respond(), Step.Text("Menu!"))
        val agent = Agent(model, tools = tools(), scope = backgroundScope)
        val response = agent.respond("Menu?", ToolPolicy(choice = ToolChoice.Required))
        assertEquals(listOf(false, true, false, false), response.steps.map { it.isRepair })
        assertEquals(listOf("check_menu"), response.toolCalls.map { it.call.name })
        assertTrue(model.requests[1].prompt.contains("Your previous answer was invalid: \"respond\" is not allowed now; you must call one of the tools"))
    }

    @Test
    fun requiredWithOneEnabledToolAsksForItsArguments() = runTest {
        val orders = mutableListOf<ToolCall>()
        val model = scripted(Step.Json(jsonObjectOf("item" to "stew", "quantity" to 2)), Step.respond(), Step.Text("Two stews."))
        val agent = Agent(model, tools = tools(orders), scope = backgroundScope)
        val policy = ToolPolicy(choice = ToolChoice.Required, enabledTools = setOf("take_order"))
        val response = agent.respond("Two stews", policy)
        assertEquals(StepKind.TOOL_ARGUMENTS, response.steps.first().kind)
        assertEquals(listOf("take_order"), response.steps.first().enabledTools)
        assertEquals(jsonObjectOf("item" to "stew", "quantity" to 2), orders.single().arguments)
    }

    @Test
    fun namedToolWithoutParametersNeedsNoModelStep() = runTest {
        val model = scripted(Step.respond(), Step.Text("Ale is 3."))
        val agent = Agent(model, tools = tools(), scope = backgroundScope)
        val response = agent.respond("Prices?", ToolPolicy(choice = ToolChoice.Tool("check_menu")))
        assertEquals(listOf("check_menu"), response.toolCalls.map { it.call.name })
        assertEquals(listOf(StepKind.DECIDE, StepKind.RESPOND), response.steps.map { it.kind })
        assertEquals(1, response.steps.first().completedToolRounds)
    }

    @Test
    fun namedToolWithParametersAsksOnlyForArguments() = runTest {
        val orders = mutableListOf<ToolCall>()
        val model = scripted(
            Step.Text("""Here are the arguments: {"action": "take_order", "arguments": {"item": "bread", "quantity": 4}}"""),
            Step.respond(),
            Step.Text("Four loaves."),
        )
        val agent = Agent(model, tools = tools(orders), scope = backgroundScope)
        val response = agent.respond("Four breads", ToolPolicy(choice = ToolChoice.Tool("take_order"), maxToolRounds = 2))
        assertEquals(jsonObjectOf("item" to "bread", "quantity" to 4), orders.single().arguments)
        val step = response.steps.first()
        assertEquals(StepKind.TOOL_ARGUMENTS, step.kind)
        assertEquals(ToolCallingMode.REQUIRED, step.toolCallingMode)
        val prompt = model.requests.first().prompt
        assertTrue(prompt.contains("Assistant calls the tool take_order (Place an order for the guest).\nWrite its arguments as one JSON object with these keys:\n- \"item\": one of"), prompt)
    }

    @Test
    fun namedToolMustExist() = runTest {
        val agent = Agent(scripted(), tools = tools(), scope = backgroundScope)
        val error = assertFailsWith<AgentError> { agent.respond("x", ToolPolicy(choice = ToolChoice.Tool("fly"))) }
        assertEquals(AgentErrorCode.INVALID_REQUEST, error.code)
        assertEquals(emptyList(), agent.history)
    }

    @Test
    fun enabledToolsNarrowTheOffer() = runTest {
        val model = scripted(Step.respond(), Step.Text("Hi"))
        val agent = Agent(model, tools = tools(), scope = backgroundScope)
        val response = agent.respond("Hi", ToolPolicy(enabledTools = setOf("check_menu")))
        assertEquals(listOf("check_menu"), response.steps.first().enabledTools)
        assertTrue(!model.requests.first().prompt.contains("take_order"))
        // A tool that is not offered cannot be called.
        val sneaky = scripted(Step.call("take_order", "item" to "ale", "quantity" to 1), Step.call("take_order", "item" to "ale", "quantity" to 1), Step.Text("No."))
        val other = Agent(sneaky, tools = tools(), scope = backgroundScope)
        assertEquals(emptyList(), other.respond("Ale", ToolPolicy(enabledTools = setOf("check_menu"))).toolCalls)
    }

    @Test
    fun toolChoiceWireForm() {
        val all = listOf(ToolChoice.Auto, ToolChoice.None, ToolChoice.Required, ToolChoice.Explicit, ToolChoice.Tool("check_menu"))
        assertEquals(listOf("\"auto\"", "\"none\"", "\"required\"", "\"explicit\"", """{"tool":"check_menu"}"""), all.map { it.toJson().toJsonString() })
        all.forEach { assertEquals(it, ToolChoice.fromJson(it.toJson())) }
        assertEquals(ToolChoice.Required, ToolChoice.fromJson(jsonOf("any")))
        assertEquals(ToolChoice.Tool("x"), ToolChoice.fromJson(LenientJson.parse("""{"type":"function","function":{"name":"x"}}""")))
        assertEquals(ToolChoice.Tool("y"), ToolChoice.fromJson(LenientJson.parse("""{"name":"y"}""")))
        assertFailsWith<IllegalArgumentException> { ToolChoice.fromJson(jsonOf("sometimes")) }
        assertFailsWith<IllegalArgumentException> { ToolChoice.fromJson(jsonOf(3)) }
        assertFailsWith<IllegalArgumentException> { ToolPolicy(maxToolRounds = -1) }
    }
}
