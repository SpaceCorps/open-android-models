package com.spacecorps.oam

import com.spacecorps.oam.testing.ScriptedLanguageModel
import com.spacecorps.oam.testing.ScriptedLanguageModel.Step
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AgentLifecycleTest {
    @Test
    fun cancellingATurnRollsItBack() = runTest {
        val model = scripted(Step.Text("First."), Step.call("check_menu"), Step.Delay(10.seconds, Step.Text("never")))
        val agent = Agent(model, tools = listOf(Tavern.menu()), scope = backgroundScope)
        agent.respond("One", ToolPolicy(choice = ToolChoice.None))
        val run = agent.run("Two")
        val events = mutableListOf<AgentEvent>()
        val error = assertFailsWith<AgentError> {
            run.events.collect { event ->
                events += event
                if (event is AgentEvent.ToolCallCompleted) run.cancel()
            }
        }
        assertEquals(AgentErrorCode.CANCELLED, error.code)
        agent.waitUntilIdle()
        assertEquals(listOf(TranscriptEntry.Prompt("One"), TranscriptEntry.Response("First.")), agent.history)
        assertFalse(agent.isResponding)
        assertTrue(run.isFinished)
    }

    @Test
    fun cancellingTheAwaitingCoroutineCancelsTheTurn() = runTest {
        val model = scripted(Step.Delay(10.seconds, Step.Text("never")))
        val agent = Agent(model, scope = backgroundScope)
        val run = agent.run("Hi")
        val waiter = async { runCatching { run.response() } }
        delay(100)
        waiter.cancel()
        agent.waitUntilIdle()
        assertTrue(run.isFinished)
        assertEquals(emptyList(), agent.history)
    }

    @Test
    fun stoppingEarlyDoesNotCancel() = runTest {
        val model = scripted(Step.Text("Complete reply.", chunks = 3))
        val agent = Agent(model, scope = backgroundScope)
        val first = agent.run("Hi").events.first()
        assertIs<AgentEvent.ModelStep>(first)
        agent.waitUntilIdle()
        assertEquals(TranscriptEntry.Response("Complete reply."), agent.history.last())
    }

    @Test
    fun cancellingAQueuedTurnEndsItAtOnce() = runTest {
        val model = scripted(Step.Delay(1.seconds, Step.Text("first")), Step.Text("second"), Step.Text("third"))
        val agent = Agent(model, scope = backgroundScope)
        val first = agent.run("1")
        val second = agent.run("2")
        val third = agent.run("3")
        second.cancel()
        assertTrue(second.isFinished)
        assertEquals(AgentErrorCode.CANCELLED, assertFailsWith<AgentError> { second.response() }.code)
        assertEquals("first", first.response().text)
        assertEquals("second", third.response().text, "the cancelled turn used no model step")
        assertEquals(listOf("1", "first", "3", "second"), agent.history.map { (it as? TranscriptEntry.Prompt)?.text ?: (it as TranscriptEntry.Response).text })
    }

    @Test
    fun turnsRunOneAtATimeInOrder() = runTest {
        val order = mutableListOf<String>()
        val model = ScriptedLanguageModel(
            listOf(
                Step.Delay(500.milliseconds, Step.Dynamic { order += "a"; Step.Text("A") }),
                Step.Dynamic { order += "b"; Step.Text("B") },
            ),
        )
        val agent = Agent(model, scope = backgroundScope)
        val runs = listOf(agent.run("a"), agent.run("b"))
        assertEquals(listOf("A", "B"), runs.map { it.response().text })
        assertEquals(listOf("a", "b"), order)
        // The second turn saw the first in its history.
        assertTrue(model.requests[1].prompt.contains("User: a\nAssistant: A\nUser: b"))
    }

    @Test
    fun failedTurnsLeaveNoTraceAndReportTheCode() = runTest {
        val model = scripted(Step.Fail(AgentError(AgentErrorCode.GUARDRAIL_VIOLATION, "blocked")))
        val agent = Agent(model, scope = backgroundScope)
        val error = assertFailsWith<AgentError> { agent.respond("bad words") }
        assertEquals(AgentErrorCode.GUARDRAIL_VIOLATION, error.code)
        assertEquals("guardrail_violation: blocked", error.toString())
        assertEquals(emptyList(), agent.history)
        assertEquals(1, model.requests.size, "guardrail violations are not retried by default")
    }

    @Test
    fun transientFailuresAreRetriedPerStepWithBackoff() = runTest {
        val model = scripted(
            Step.call("check_menu"),
            Step.Fail(AgentError(AgentErrorCode.BUSY, "quota")),
            Step.Fail(IllegalStateException("glitch")),
            Step.respond(),
            Step.Text("Here."),
        )
        Tavern.menuCalls.set(0)
        val agent = Agent(model, tools = listOf(Tavern.menu()), scope = backgroundScope)
        val response = agent.respond("Menu")
        assertEquals("Here.", response.text)
        assertEquals(1, Tavern.menuCalls.get(), "a retry never re-runs a tool")
        assertEquals(5, model.requests.size)
        assertEquals(3, response.steps.size, "retries are not separate steps")
    }

    @Test
    fun retriesGiveUpAfterMaxAttempts() = runTest {
        val model = scripted(Step.Fail(AgentError(AgentErrorCode.BUSY, "1")), Step.Fail(AgentError(AgentErrorCode.BUSY, "2")))
        val agent = Agent(model, configuration = AgentConfiguration(retry = RetryPolicy(maxAttempts = 2)), scope = backgroundScope)
        assertEquals("2", assertFailsWith<AgentError> { agent.respond("x") }.message)
    }

    @Test
    fun retryPolicyDecisions() {
        val policy = RetryPolicy.Default
        assertTrue(policy.shouldRetry(AgentError(AgentErrorCode.GENERATION_FAILED, "")))
        assertTrue(policy.shouldRetry(AgentError(AgentErrorCode.RATE_LIMITED, "", retryAfter = 1.seconds)))
        assertFalse(policy.shouldRetry(AgentError(AgentErrorCode.RATE_LIMITED, "", retryAfter = 60.seconds)))
        assertFalse(policy.shouldRetry(AgentError(AgentErrorCode.GUARDRAIL_VIOLATION, "")))
        assertTrue(policy.copy(retriesGuardrailViolations = true).shouldRetry(AgentError(AgentErrorCode.GUARDRAIL_VIOLATION, "")))
        assertFalse(policy.shouldRetry(AgentError(AgentErrorCode.CONTEXT_SIZE_EXCEEDED, "")))
        assertFalse(RetryPolicy.None.shouldRetry(AgentError(AgentErrorCode.CANCELLED, "")))
        assertEquals(AgentErrorCode.CONTEXT_SIZE_EXCEEDED, AgentErrorCode.fromWireName("context_size_exceeded"))
        assertEquals(AgentErrorCode.CANCELLED, AgentError.from(kotlinx.coroutines.CancellationException("x")).code)
        assertEquals(AgentErrorCode.INVALID_SCHEMA, AgentError.from(InvalidSchemaException("#", "bad")).code)
        assertEquals(AgentErrorCode.GENERATION_FAILED, AgentError.from(RuntimeException("x")).code)
    }

    @Test
    fun retriedReplyStartsWithAResetEvent() = runTest {
        var calls = 0
        val flaky = object : LanguageModel {
            override val capabilities = ModelCapabilities()

            override suspend fun availability(): ModelAvailability = ModelAvailability.Available

            override suspend fun countTokens(text: String): Int? = null

            override fun generate(request: GenerationRequest) = flow {
                calls++
                if (calls == 1) {
                    emit(GenerationChunk("Partial ", "Partial "))
                    emit(GenerationChunk("ans", "Partial ans"))
                    throw AgentError(AgentErrorCode.GENERATION_FAILED, "dropped")
                }
                emit(GenerationChunk("Fresh answer.", "Fresh answer.", isFinal = true))
            }
        }
        val events = Agent(flaky, scope = backgroundScope).run("Hi").events.toList()
        val texts = events.filterIsInstance<AgentEvent.Text>()
        assertEquals(listOf("Partial ", "ans"), texts.take(2).map { it.delta })
        assertEquals(AgentEvent.Text("Fresh answer.", "Fresh answer.", isReset = true), texts[2])
        assertEquals(3, texts.size)
        assertEquals("Fresh answer.", (events.last() as AgentEvent.Completed).response.text)
    }

    @Test
    fun transcriptRoundTripsAndResumes() = runTest {
        val model = scripted(Step.call("check_menu"), Step.respond(), Step.Text("Ale is 3."))
        val agent = Agent(model, instructions = "You are Mira.", tools = listOf(Tavern.menu()), scope = backgroundScope)
        agent.respond("Price?")
        agent.contextNote = "The guest is a dwarf."
        val saved = agent.transcript
        assertEquals(1, saved.turnCount)
        assertEquals(listOf("check_menu"), saved.tools.map { it.name })
        val text = saved.toJsonString()
        assertTrue(text.startsWith("""{"type":"open-android-models.Transcript","version":"1.0","transcript":{"instructions":"You are Mira.""""), text)
        val restored = Transcript.fromJsonString(text)
        assertEquals(saved, restored)
        assertEquals(saved, Transcript.fromJson(OamJson.encodeToJsonElement(Transcript.serializer(), saved)))
        assertFailsWith<AgentError> { Transcript.fromJsonString("""{"entries": 3}""") }

        val next = scripted(Step.Text("Welcome back."))
        val resumed = Agent(next, instructions = "You are Mira.", history = restored, scope = backgroundScope)
        assertEquals("The guest is a dwarf.", resumed.contextNote)
        resumed.respond("Hello again")
        val prompt = next.requests.single()
        assertTrue(prompt.prompt.startsWith("Conversation:\nUser: Price?\n[check_menu → {\"ale\":3,\"stew\":5,\"bread\":1}]\nAssistant: Ale is 3.\nUser: Hello again"), prompt.prompt)
        assertEquals("You are Mira.\n\nThe guest is a dwarf.", prompt.systemInstruction)
    }

    @Test
    fun transcriptShowsTheRunningTurn() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slow = AgentTool.local("check_menu", "Menu.") {
            gate.await()
            ToolOutput.Text("menu")
        }
        val model = scripted(Step.call("check_menu"), Step.respond(), Step.Text("ok"))
        val agent = Agent(model, tools = listOf(slow), scope = backgroundScope)
        val run = agent.run("Menu?")
        val collector = launch { run.response() }
        delay(10)
        assertTrue(agent.isResponding)
        assertEquals(listOf(TranscriptEntry.Prompt("Menu?")), agent.transcript.entries)
        assertEquals(emptyList(), agent.history)
        gate.complete(Unit)
        collector.join()
        assertEquals(3, agent.history.size)
    }

    @Test
    fun resetAndReplaceHistory() = runTest {
        val model = scripted(Step.Text("a"), Step.Text("b"))
        val agent = Agent(model, scope = backgroundScope)
        agent.respond("1")
        agent.contextNote = "note"
        agent.reset()
        assertEquals(emptyList(), agent.history)
        assertNull(agent.contextNote)
        agent.replaceHistory(listOf(TranscriptEntry.Prompt("x"), TranscriptEntry.Response("y")))
        assertEquals(2, agent.history.size)
        agent.respond("2")
        assertTrue(model.requests.last().prompt.startsWith("Conversation:\nUser: x\nAssistant: y\nUser: 2"))
    }

    @Test
    fun compactionSummarizesOlderTurns() = runTest {
        val model = scripted(Step.Text("r1"), Step.Text("r2"), Step.Text("r3"), Step.Text("The guest ordered ale and is named Bob."))
        val agent = Agent(model, configuration = AgentConfiguration(userLabel = "Guest", assistantLabel = "Mira"), scope = backgroundScope)
        agent.respond("I'm Bob")
        agent.respond("Ale please")
        agent.respond("Thanks")
        agent.contextNote = "Earlier: nothing."
        val summary = agent.compactHistory(keepRecentTurns = 1)
        assertEquals("The guest ordered ale and is named Bob.", summary)
        assertEquals(summary, agent.contextNote)
        assertEquals(listOf(TranscriptEntry.Prompt("Thanks"), TranscriptEntry.Response("r3")), agent.history)
        val request = model.requests.last()
        assertEquals(GenerationKind.SUMMARY, request.kind)
        assertEquals(Agent.SUMMARY_INSTRUCTIONS, request.systemInstruction)
        assertEquals("Summary so far:\nEarlier: nothing.\n\nConversation to fold into the summary:\nGuest: I'm Bob\nMira: r1\nGuest: Ale please\nMira: r2", request.prompt)
        assertNull(agent.compactHistory(keepRecentTurns = 1), "nothing left to compact")
    }

    @Test
    fun failedCompactionChangesNothing() = runTest {
        val model = scripted(Step.Text("r1"), Step.Text("r2"), Step.Fail(AgentError(AgentErrorCode.RATE_LIMITED, "quota")))
        val agent = Agent(model, scope = backgroundScope)
        agent.respond("1")
        agent.respond("2")
        assertEquals(AgentErrorCode.RATE_LIMITED, assertFailsWith<AgentError> { agent.compactHistory(keepRecentTurns = 1) }.code)
        assertEquals(4, agent.history.size)
        assertNull(agent.contextNote)
    }

    @Test
    fun usageAccumulatesAndEventsAreCollectedOnce() = runTest {
        val model = scripted(Step.Text("Hello there"), Step.Text("Again"))
        val agent = Agent(model, scope = backgroundScope)
        val run = agent.run("Hi")
        run.events.toList()
        assertFailsWith<IllegalStateException> { run.events.toList() }
        agent.respond("Again")
        assertTrue(agent.totalUsage.outputTokens > 0)
        assertEquals(agent.totalUsage.inputTokens + agent.totalUsage.outputTokens, agent.totalUsage.totalTokens)
    }

    @Test
    fun invalidSchemaFailsTheRunImmediately() = runTest {
        val agent = Agent(scripted(), scope = backgroundScope)
        val run = agent.run("x", JsonSchema.parse("""{"type":"strng"}"""))
        assertTrue(run.isFinished)
        assertEquals(AgentErrorCode.INVALID_SCHEMA, assertFailsWith<AgentError> { run.response() }.code)
    }

    @Test
    fun closingAnOwnedAgentCancelsItsTurns() = runBlocking {
        val agent = Agent(scripted(Step.Delay(30.seconds, Step.Text("never"))))
        val run = agent.run("Hi")
        delay(50)
        agent.close()
        val error = withTimeout(5.seconds) { assertFailsWith<AgentError> { run.response() } }
        assertEquals(AgentErrorCode.CANCELLED, error.code)
        val after = agent.run("again")
        assertEquals(AgentErrorCode.CANCELLED, withTimeout(5.seconds) { assertFailsWith<AgentError> { after.response() } }.code)
    }

    @Test
    fun runsOnTheDefaultDispatcherToo() = runBlocking {
        val agent = Agent(scripted(Step.call("check_menu"), Step.respond(), Step.Text("Real threads.")), tools = listOf(Tavern.menu()))
        assertEquals("Real threads.", withTimeout(5.seconds) { agent.respond("Hi") }.text)
        agent.close()
    }
}
