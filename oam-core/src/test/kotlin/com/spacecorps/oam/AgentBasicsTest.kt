package com.spacecorps.oam

import com.spacecorps.oam.testing.ScriptedLanguageModel.Step
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentBasicsTest {
    @Test
    fun plainTurnWithoutToolsStreamsText() = runTest {
        val model = scripted(Step.Text("Welcome to the Prancing Pony!", chunks = 4))
        val agent = Agent(model, instructions = "You are Mira.", scope = backgroundScope)
        val events = agent.run("Hello").collectAll()

        val texts = events.filterIsInstance<AgentEvent.Text>()
        assertTrue(texts.size >= 2)
        assertEquals("Welcome to the Prancing Pony!", texts.last().text)
        assertEquals("Welcome to the Prancing Pony!", texts.joinToString("") { it.delta })
        val completed = assertIs<AgentEvent.Completed>(events.last())
        assertEquals("Welcome to the Prancing Pony!", completed.response.text)
        assertEquals(1, completed.response.steps.size)
        assertEquals(StepKind.RESPOND, completed.response.steps[0].kind)
        assertEquals(ToolCallingMode.DISALLOWED, completed.response.steps[0].toolCallingMode)

        // First plain turn: the raw prompt, instructions as system instruction.
        val request = model.requests.single()
        assertEquals("Hello", request.prompt)
        assertEquals("You are Mira.", request.systemInstruction)
        assertEquals(GenerationKind.RESPOND, request.kind)

        assertEquals(
            listOf(TranscriptEntry.Prompt("Hello"), TranscriptEntry.Response("Welcome to the Prancing Pony!")),
            agent.history,
        )
    }

    @Test
    fun toolRoundThenReply() = runTest {
        val model = scripted(Step.call("check_menu"), Step.respond(), Step.Text("Ale is 3 gold."))
        val agent = Agent(model, instructions = "You are Mira.", tools = listOf(Tavern.menu()), scope = backgroundScope)
        val response = agent.respond("What's on tap?")

        assertEquals("Ale is 3 gold.", response.text)
        assertEquals(listOf("check_menu"), response.toolCalls.map { it.call.name })
        assertEquals("""{"ale":3,"stew":5,"bread":1}""", response.toolCalls[0].output.modelText)
        assertEquals(listOf(StepKind.DECIDE, StepKind.DECIDE, StepKind.RESPOND), response.steps.map { it.kind })
        assertEquals(listOf(0, 1, 1), response.steps.map { it.completedToolRounds })

        val kinds = model.requests.map { it.kind }
        assertEquals(listOf(GenerationKind.DECIDE, GenerationKind.DECIDE, GenerationKind.RESPOND), kinds)
        // The tool catalog is the stable prompt prefix of decide steps.
        assertTrue(model.requests[0].promptPrefix!!.contains("- check_menu: Look up today's menu and prices."))
        // The reply step sees the tool result but no tool catalog.
        assertTrue(model.requests[2].prompt.contains("[check_menu → {\"ale\":3,\"stew\":5,\"bread\":1}]"))
        assertNull(model.requests[2].promptPrefix)

        val history = agent.history
        assertEquals(3, history.size)
        assertIs<TranscriptEntry.ToolUse>(history[1])
    }

    @Test
    fun secondTurnRendersLabelledHistory() = runTest {
        val model = scripted(Step.Text("Hi!"), Step.Text("Ale."))
        val agent = Agent(
            model,
            configuration = AgentConfiguration(userLabel = "Player", assistantLabel = "Mira"),
            scope = backgroundScope,
        )
        agent.respond("Hello")
        agent.respond("Drink?")
        val prompt = model.requests[1].prompt
        assertTrue(prompt.startsWith("Conversation:\nPlayer: Hello\nMira: Hi!\nPlayer: Drink?\n"), prompt)
        assertTrue(prompt.endsWith("Write Mira's reply to Player's last message. Answer with the reply text only."), prompt)
    }
}
