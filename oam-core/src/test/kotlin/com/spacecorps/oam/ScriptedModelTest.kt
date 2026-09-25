package com.spacecorps.oam

import com.spacecorps.oam.testing.ScriptedLanguageModel
import com.spacecorps.oam.testing.ScriptedLanguageModel.ScriptStyle
import com.spacecorps.oam.testing.ScriptedLanguageModel.Step
import com.spacecorps.oam.testing.ScriptedLanguageModel.Step.ScriptedCall
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ScriptedModelTest {
    private fun native(vararg steps: Step) = ScriptedLanguageModel(steps.toList(), style = ScriptStyle.NATIVE)

    @Test
    fun envelopeScriptsPlayStepsVerbatim() = runTest {
        val model = ScriptedLanguageModel(Step.Text("abcdef", chunks = 3), Step.Json(jsonObjectOf("a" to 1)), Step.call("t", "x" to 1))
        val chunks = model.generate(GenerationRequest("p")).toList()
        assertEquals(listOf("ab", "cd", "ef"), chunks.map { it.delta })
        assertTrue(chunks.last().isFinal && chunks.last().usage != null)
        assertEquals("""{"a":1}""", model.generate(GenerationRequest("p")).toList().last().text)
        assertEquals("""{"action":"t","arguments":{"x":1}}""", model.generate(GenerationRequest("p")).toList().last().text)
        assertEquals("(script exhausted)", model.generate(GenerationRequest("p")).toList().last().text)
        assertEquals(4, model.requests.size)
        assertEquals(0, model.remainingSteps)
        model.append(Step.Text("more"))
        assertEquals(1, model.remainingSteps)
        assertEquals(null, model.countTokens("x"))
        assertEquals(ModelAvailability.Available, model.availability())
        model.currentAvailability = ModelAvailability.Downloadable
        assertIs<ModelAvailability.Downloadable>(model.availability())
    }

    @Test
    fun nativeScriptsTakeOneStepPerToolRoundAndOneForTheAnswer() = runTest {
        val model = native(Step.ToolCalls(ScriptedCall("check_menu")), Step.Text("Ale is 3 gold."))
        val agent = Agent(model, tools = listOf(Tavern.menu()), scope = backgroundScope)
        val response = agent.respond("Ale?")
        assertEquals("Ale is 3 gold.", response.text)
        assertEquals(listOf("check_menu"), response.toolCalls.map { it.call.name })
        assertEquals(listOf(GenerationKind.DECIDE, GenerationKind.DECIDE, GenerationKind.RESPOND), model.requests.map { it.kind })
        assertEquals(0, model.remainingSteps)
    }

    @Test
    fun nativeScriptsAnswerWithoutTools() = runTest {
        val model = native(Step.Text("Hmph."), Step.Json(jsonObjectOf("choice" to "refuse")))
        val agent = Agent(model, tools = listOf(Tavern.menu()), scope = backgroundScope)
        assertEquals("Hmph.", agent.respond("Hi").text)
        val structured = agent.respond("Decide", JsonSchema.obj("choice" to JsonSchema.string(enum = listOf("refuse", "accept"))))
        assertEquals(jsonObjectOf("choice" to "refuse"), structured.structured)
    }

    @Test
    fun nativeRoundsWithSeveralCallsRunThemInTurn() = runTest {
        val orders = mutableListOf<ToolCall>()
        val model = native(
            Step.ToolCalls(ScriptedCall("check_menu"), ScriptedCall("take_order", jsonObjectOf("item" to "ale", "quantity" to 2))),
            Step.Template("Done: {toolOutput} for '{prompt}'"),
        )
        val agent = Agent(model, tools = listOf(Tavern.menu(), Tavern.order(orders)), scope = backgroundScope)
        val response = agent.respond("Two ales")
        assertEquals(listOf("check_menu", "take_order"), response.toolCalls.map { it.call.name })
        assertEquals(1, orders.size)
        assertEquals("Done: {\"ok\":true,\"item\":\"ale\",\"quantity\":2} for 'Two ales'", response.text)
    }

    @Test
    fun nativeScriptsWorkWithNamedTools() = runTest {
        val orders = mutableListOf<ToolCall>()
        val model = native(Step.ToolCalls(ScriptedCall("take_order", jsonObjectOf("item" to "stew", "quantity" to 1))), Step.Text("Stew!"))
        val agent = Agent(model, tools = listOf(Tavern.order(orders)), scope = backgroundScope)
        val response = agent.respond("Stew", ToolPolicy(choice = ToolChoice.Tool("take_order")))
        assertEquals(jsonObjectOf("item" to "stew", "quantity" to 1), orders.single().arguments)
        assertEquals("Stew!", response.text)
        assertEquals(StepKind.TOOL_ARGUMENTS, response.steps.first().kind)
    }

    @Test
    fun nativeFailuresAndDelays() = runTest {
        val failing = native(Step.Fail(AgentError(AgentErrorCode.REFUSAL, "no")))
        val agent = Agent(failing, tools = listOf(Tavern.menu()), configuration = AgentConfiguration(retry = RetryPolicy.None), scope = backgroundScope)
        assertEquals(AgentErrorCode.REFUSAL, assertFailsWith<AgentError> { agent.respond("x") }.code)

        val slow = native(Step.Delay(2.seconds, Step.Text("late")))
        val run = Agent(slow, tools = listOf(Tavern.menu()), scope = backgroundScope).run("x")
        assertEquals("late", run.response().text)
    }

    @Test
    fun dynamicStepsSeeTheTurn() = runTest {
        val model = ScriptedLanguageModel(
            Step.call("check_menu"),
            Step.respond(),
            Step.Dynamic { request -> Step.Text("tools so far: ${request.turn?.toolUses?.size}, prompt: ${request.turn?.prompt}") },
        )
        val response = Agent(model, tools = listOf(Tavern.menu()), scope = backgroundScope).respond("Menu?")
        assertEquals("tools so far: 1, prompt: Menu?", response.text)
    }

    @Test
    fun dedupeCanBeTurnedOffForRepeatableTools() = runTest {
        var rolls = 0
        val dice = AgentTool.local("roll_die", "Roll a die.") { ToolOutput.of(++rolls) }
        val model = ScriptedLanguageModel(Step.call("roll_die"), Step.call("roll_die"), Step.respond(), Step.Text("Two rolls."))
        val agent = Agent(model, tools = listOf(dice), configuration = AgentConfiguration(dedupesToolCalls = false), scope = backgroundScope)
        assertEquals(2, agent.respond("Roll twice").toolCalls.size)
    }
}
