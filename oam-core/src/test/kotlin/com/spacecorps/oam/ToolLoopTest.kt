package com.spacecorps.oam

import com.spacecorps.oam.testing.ScriptedLanguageModel.Step
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ToolLoopTest {
    @Test
    fun roundBudgetForcesTheReply() = runTest {
        val model = scripted(Step.call("check_menu"), Step.call("take_order", "item" to "ale", "quantity" to 1), Step.Text("Forced reply."))
        val orders = mutableListOf<ToolCall>()
        val agent = Agent(model, tools = listOf(Tavern.menu(), Tavern.order(orders)), scope = backgroundScope)
        val response = agent.respond("Ale", ToolPolicy(maxToolRounds = 2))
        assertEquals(listOf("check_menu", "take_order"), response.toolCalls.map { it.call.name })
        assertEquals(listOf(StepKind.DECIDE, StepKind.DECIDE, StepKind.RESPOND), response.steps.map { it.kind })
        assertEquals(ToolCallingMode.DISALLOWED, response.steps.last().toolCallingMode)
        assertEquals(2, response.steps.last().completedToolRounds)
        assertEquals("Forced reply.", response.text)
    }

    @Test
    fun callBudgetForcesTheReply() = runTest {
        val model = scripted(Step.call("check_menu"), Step.Text("One call only."))
        val agent = Agent(model, tools = listOf(Tavern.menu()), scope = backgroundScope)
        val response = agent.respond("Menu", ToolPolicy(maxToolRounds = 5, maxToolCalls = 1))
        assertEquals(1, response.toolCalls.size)
        assertEquals(listOf(StepKind.DECIDE, StepKind.RESPOND), response.steps.map { it.kind })
    }

    @Test
    fun zeroRoundsMeansNoTools() = runTest {
        val model = scripted(Step.Text("Direct."))
        val agent = Agent(model, tools = listOf(Tavern.menu()), scope = backgroundScope)
        assertEquals(listOf(StepKind.RESPOND), agent.respond("Menu", ToolPolicy(maxToolRounds = 0)).steps.map { it.kind })
    }

    @Test
    fun invalidEnvelopeIsRepairedOnce() = runTest {
        val model = scripted(Step.Text("Let me think about the menu for you."), Step.call("check_menu"), Step.respond(), Step.Text("Here."))
        val agent = Agent(model, tools = listOf(Tavern.menu()), scope = backgroundScope)
        val response = agent.respond("Menu?")
        assertEquals(listOf("check_menu"), response.toolCalls.map { it.call.name })
        assertEquals(listOf(false, true, false, false), response.steps.map { it.isRepair })
        val repair = model.requests[1].prompt
        assertTrue(repair.contains("Your previous answer was invalid: the answer was not a JSON object.\nPrevious answer: Let me think about the menu for you.\nAnswer again, correctly."), repair)
    }

    @Test
    fun invalidArgumentsAreCoercedOrRepaired() = runTest {
        val orders = mutableListOf<ToolCall>()
        // "Ale" and "2" are coerced without a repair; quantity 12 needs one.
        val model = scripted(
            Step.call("take_order", "item" to "Ale", "quantity" to "2"),
            Step.call("take_order", "item" to "stew", "quantity" to 12),
            Step.call("take_order", "item" to "stew", "quantity" to 10),
            Step.respond(),
            Step.Text("Done."),
        )
        val agent = Agent(model, tools = listOf(Tavern.order(orders)), scope = backgroundScope)
        val response = agent.respond("Order")
        assertEquals(listOf(jsonObjectOf("item" to "ale", "quantity" to 2), jsonObjectOf("item" to "stew", "quantity" to 10)), orders.map { it.arguments })
        assertEquals(1, response.steps.count { it.isRepair })
        assertTrue(model.requests[2].prompt.contains("Your previous answer was invalid: the arguments for take_order are invalid: $.quantity: must be at most 10; got 12."))
    }

    @Test
    fun argumentsStillInvalidAfterRepairAreRejectedWithoutRunning() = runTest {
        val orders = mutableListOf<ToolCall>()
        val model = scripted(
            Step.call("take_order", "item" to "wine", "quantity" to 1),
            Step.call("take_order", "item" to "mead", "quantity" to 1),
            Step.Text("Sorry, no wine."),
        )
        val agent = Agent(model, tools = listOf(Tavern.order(orders)), scope = backgroundScope)
        val events = agent.run("Wine!").collectAll()
        val response = (events.last() as AgentEvent.Completed).response
        assertEquals(emptyList(), orders)
        val record = response.toolCalls.single()
        assertTrue(record.output.isError)
        assertEquals(
            "Error: The call was not made: the arguments for take_order are invalid: $.item: must be one of \"ale\", \"stew\", \"bread\"; got \"mead\".",
            record.output.modelText,
        )
        assertTrue(events.none { it is AgentEvent.ToolCallStarted })
        // The reply step sees the rejection.
        assertTrue(model.requests.last().prompt.contains("[take_order {\"item\":\"mead\",\"quantity\":1} → Error: The call was not made"))
        assertEquals("Sorry, no wine.", response.text)
    }

    @Test
    fun unknownToolTwiceFallsBackToTheReply() = runTest {
        val model = scripted(Step.call("fly"), Step.call("teleport"), Step.Text("I can't."))
        val agent = Agent(model, tools = listOf(Tavern.menu()), scope = backgroundScope)
        val response = agent.respond("Fly me")
        assertEquals(emptyList(), response.toolCalls)
        assertEquals("I can't.", response.text)
        assertTrue(model.requests[1].prompt.contains("\"fly\" is not an available action; use one of: check_menu, respond"))
    }

    @Test
    fun repairsCanBeTurnedOff() = runTest {
        val model = scripted(Step.Text("gibberish"), Step.Text("Reply."))
        val agent = Agent(model, tools = listOf(Tavern.menu()), configuration = AgentConfiguration(repairsInvalidOutput = false), scope = backgroundScope)
        val response = agent.respond("Hi")
        assertEquals(listOf(StepKind.DECIDE, StepKind.RESPOND), response.steps.map { it.kind })
    }

    @Test
    fun repeatedIdenticalCallMeansDone() = runTest {
        Tavern.menuCalls.set(0)
        val model = scripted(Step.call("check_menu"), Step.call("check_menu"), Step.Text("Ale is 3."))
        val agent = Agent(model, tools = listOf(Tavern.menu()), scope = backgroundScope)
        val response = agent.respond("Price of ale?")
        assertEquals(1, response.toolCalls.size)
        assertEquals(1, Tavern.menuCalls.get())
        assertEquals(listOf(StepKind.DECIDE, StepKind.DECIDE, StepKind.RESPOND), response.steps.map { it.kind })
    }

    @Test
    fun toolErrorsAreShownToTheModelAndTheTurnContinues() = runTest {
        val broken = AgentTool.local("check_menu", "Menu.") { throw IllegalStateException("The kitchen is closed.") }
        val model = scripted(Step.call("check_menu"), Step.respond(), Step.Text("Kitchen's closed."))
        val agent = Agent(model, tools = listOf(broken), scope = backgroundScope)
        val response = agent.respond("Food?")
        assertEquals(ToolOutput.Error("The kitchen is closed."), response.toolCalls.single().output)
        assertTrue(model.requests[1].prompt.contains("[check_menu → Error: The kitchen is closed.]"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun localToolsTimeOutEvenWhenIgnoringCancellation() = runTest {
        val stubborn = AgentTool.local("check_menu", "Menu.", timeout = 1.seconds) {
            withContext(NonCancellable) { delay(60.seconds) }
            ToolOutput.Text("too late")
        }
        val model = scripted(Step.call("check_menu"), Step.respond(), Step.Text("Slow kitchen."))
        val agent = Agent(model, tools = listOf(stubborn), scope = backgroundScope)
        val started = testScheduler.currentTime
        val response = agent.respond("Food?")
        assertEquals(ToolOutput.Error("Tool 'check_menu' timed out after 1s."), response.toolCalls.single().output)
        assertTrue(testScheduler.currentTime - started < 5_000, "should not wait for the handler")
    }

    @Test
    fun defaultToolTimeoutAppliesToLocalTools() = runTest {
        val slow = AgentTool.local("check_menu", "Menu.") {
            delay(10.seconds)
            ToolOutput.Text("late")
        }
        val model = scripted(Step.call("check_menu"), Step.respond(), Step.Text("…"))
        val agent = Agent(model, tools = listOf(slow), configuration = AgentConfiguration(toolTimeout = 500.milliseconds), scope = backgroundScope)
        assertEquals(ToolOutput.Error("Tool 'check_menu' timed out after 500ms."), agent.respond("x").toolCalls.single().output)
    }

    @Serializable
    data class Order(val item: String, val quantity: Int)

    @Test
    fun typedToolsDecodeTheirArguments() = runTest {
        val received = mutableListOf<Order>()
        val tool = AgentTool.typed<Order>("take_order", "Order.", Tavern.orderSchema) { order ->
            received += order
            ToolOutput.encoding(order)
        }
        val model = scripted(Step.call("take_order", "item" to "ale", "quantity" to 3), Step.respond(), Step.Text("ok"))
        val agent = Agent(model, tools = listOf(tool), scope = backgroundScope)
        val response = agent.respond("3 ales")
        assertEquals(listOf(Order("ale", 3)), received)
        assertEquals("""{"item":"ale","quantity":3}""", response.toolCalls.single().output.modelText)
    }

    @Test
    fun toolCallAccessors() {
        val call = ToolCall(name = "t", arguments = jsonObjectOf("s" to "x", "i" to 2, "d" to 2.5, "b" to true, "n" to null))
        assertEquals("x", call.string("s"))
        assertEquals(2, call.int("i"))
        assertEquals(2L, call.long("i"))
        assertEquals(2.5, call.double("d"))
        assertEquals(true, call.bool("b"))
        assertEquals(null, call.stringOrNull("n"))
        val error = kotlin.runCatching { call.int("s") }.exceptionOrNull()
        assertEquals("Argument 's' of tool 't' must be an integer; got \"x\".", error?.message)
        assertTrue(ToolCall.newId().matches(Regex("call_[A-Za-z0-9]{24}")))
    }

    @Test
    fun toolOutputs() {
        assertEquals(ToolOutput.Text("hi"), ToolOutput.of("hi"))
        assertEquals("""{"a":1}""", ToolOutput.of(mapOf("a" to 1)).modelText)
        assertEquals("Error: nope", ToolOutput.Error("nope").modelText)
        val encoded = OamJson.encodeToString(ToolOutput.serializer(), ToolOutput.Json(jsonOf(listOf(1))))
        assertEquals("""{"type":"json","value":[1]}""", encoded)
        assertEquals(ToolOutput.Json(jsonOf(listOf(1))), OamJson.decodeFromString(ToolOutput.serializer(), encoded))
    }

    @Test
    fun duplicateToolNamesAreRejected() {
        val error = kotlin.runCatching { Agent(scripted(), tools = listOf(Tavern.menu(), Tavern.menu())) }.exceptionOrNull() as AgentError
        assertEquals(AgentErrorCode.INVALID_REQUEST, error.code)
    }
}
