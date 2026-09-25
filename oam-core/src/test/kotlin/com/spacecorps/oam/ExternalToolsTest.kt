package com.spacecorps.oam

import com.spacecorps.oam.testing.ScriptedLanguageModel.Step
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ExternalToolsTest {
    private val gate = AgentTool.external(
        "open_gate",
        "Ask the game engine to open a named gate.",
        JsonSchema.obj("gate" to JsonSchema.string()),
    )

    @Test
    fun hostAnswersFromTheEvent() = runTest {
        val model = scripted(Step.call("open_gate", "gate" to "north"), Step.respond(), Step.Text("It is jammed."))
        val agent = Agent(model, tools = listOf(gate), scope = backgroundScope)
        val run = agent.run("Open the north gate")
        val events = mutableListOf<AgentEvent>()
        run.events.collect { event ->
            events += event
            if (event is AgentEvent.ToolCallRequested) {
                assertEquals(listOf(event.call), run.pendingToolCalls)
                assertEquals(jsonObjectOf("gate" to "north"), event.call.arguments)
                assertTrue(run.submit(ToolOutput.of(mapOf("opened" to false, "reason" to "jammed")), event.call.id))
                assertFalse(run.submit(ToolOutput.Text("again"), event.call.id), "a call is answered once")
            }
        }
        val completed = assertIs<AgentEvent.Completed>(events.last())
        assertEquals("""{"opened":false,"reason":"jammed"}""", completed.response.toolCalls.single().output.modelText)
        assertTrue(events.none { it is AgentEvent.ToolCallStarted }, "external calls are requested, not started")
        assertEquals(1, events.count { it is AgentEvent.ToolCallCompleted })
        assertTrue(run.isFinished)
        assertFalse(run.submit(ToolOutput.Text("x"), "unknown"))
    }

    @Test
    fun responseRunsTheExternalHandler() = runTest {
        val model = scripted(Step.call("open_gate", "gate" to "west"), Step.respond(), Step.Text("Opened."))
        val agent = Agent(model, tools = listOf(gate), scope = backgroundScope)
        val response = agent.respond("Open west") { call -> ToolOutput.Text("opened ${call.string("gate")}") }
        assertEquals(ToolOutput.Text("opened west"), response.toolCalls.single().output)
    }

    @Test
    fun handlerFailuresBecomeErrorOutputs() = runTest {
        val model = scripted(Step.call("open_gate", "gate" to "west"), Step.respond(), Step.Text("Hmm."))
        val agent = Agent(model, tools = listOf(gate), scope = backgroundScope)
        val response = agent.respond("Open west") { throw IllegalStateException("engine offline") }
        assertEquals(ToolOutput.Error("engine offline"), response.toolCalls.single().output)
    }

    @Test
    fun withoutAHandlerCallsGetAnError() = runTest {
        val model = scripted(Step.call("open_gate", "gate" to "west"), Step.respond(), Step.Text("Hmm."))
        val agent = Agent(model, tools = listOf(gate), scope = backgroundScope)
        val response = agent.respond("Open west")
        assertEquals(ToolOutput.Error("No handler is registered for external tool 'open_gate'."), response.toolCalls.single().output)
    }

    @Test
    fun externalCallsTimeOutAndLateAnswersAreIgnored() = runTest {
        val timed = AgentTool.external("open_gate", "Open.", JsonSchema.obj("gate" to JsonSchema.string()), timeout = 2.seconds)
        val model = scripted(Step.call("open_gate", "gate" to "east"), Step.respond(), Step.Text("No answer."))
        val agent = Agent(model, tools = listOf(timed), scope = backgroundScope)
        val run = agent.run("Open east")
        var requested: ToolCall? = null
        var completed: AgentResponse? = null
        run.events.collect { event ->
            when (event) {
                is AgentEvent.ToolCallRequested -> requested = event.call
                is AgentEvent.Completed -> completed = event.response
                else -> Unit
            }
        }
        assertEquals(ToolOutput.Error("Tool 'open_gate' timed out after 2s."), completed!!.toolCalls.single().output)
        assertFalse(run.submit(ToolOutput.Text("late"), requested!!.id))
        assertEquals(emptyList(), run.pendingToolCalls)
    }

    @Test
    fun externalCallsWithoutTimeoutWaitForTheHost() = runTest {
        val model = scripted(Step.call("open_gate", "gate" to "south"), Step.respond(), Step.Text("Done."))
        val agent = Agent(model, tools = listOf(gate), configuration = AgentConfiguration(toolTimeout = 1.seconds), scope = backgroundScope)
        val response = agent.respond("Open south") { call ->
            delay(30.seconds) // longer than the local-tool default: external tools only use their own timeout
            ToolOutput.Text("opened ${call.string("gate")}")
        }
        assertEquals(ToolOutput.Text("opened south"), response.toolCalls.single().output)
    }

    @Test
    fun externalToolsCanBeBuiltFromDefinitions() {
        val definition = gate.definition
        val restored = AgentTool.external(definition, timeout = 5.seconds)
        assertTrue(restored.isExternal)
        assertEquals(definition, restored.definition)
        assertFalse(restored.withExecution(ToolExecution.Local { ToolOutput.Text("x") }).isExternal)
    }
}
