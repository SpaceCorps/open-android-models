package com.spacecorps.oam

import com.spacecorps.oam.testing.ScriptedLanguageModel
import com.spacecorps.oam.testing.ScriptedLanguageModel.Step
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptRenderingTest {
    private fun history(turns: Int, size: Int = 400): List<TranscriptEntry> = (1..turns).flatMap { index ->
        listOf(TranscriptEntry.Prompt("question $index " + "x".repeat(size)), TranscriptEntry.Response("answer $index " + "y".repeat(size)))
    }

    @Test
    fun oldestTurnsAreTrimmedToFitTheInputLimit() = runTest {
        val capabilities = ModelCapabilities(maxInputTokens = 1000)
        val model = scripted(Step.Text("ok"), capabilities = capabilities)
        val agent = Agent(
            model,
            history = Transcript(entries = history(10)),
            configuration = AgentConfiguration(context = ContextPolicy(reservedTokens = 100)),
            scope = backgroundScope,
        )
        val response = agent.respond("latest")
        val step = response.steps.single()
        assertTrue(step.trimmedEntries in 12..20, "trimmed ${step.trimmedEntries}")
        assertEquals(0, step.trimmedEntries % 2, "whole turns are dropped")
        val prompt = model.requests.single().prompt
        assertTrue(!prompt.contains("question 1 "), "oldest turn is gone")
        assertTrue(prompt.contains("answer 10 "), "newest turn is kept")
        assertTrue(estimateTokens(prompt) <= 900, "fits: ${estimateTokens(prompt)}")
        assertEquals(22, agent.history.size, "the transcript keeps everything")
    }

    @Test
    fun trimmingUsesTheModelsTokenCounterNearTheLimit() = runTest {
        val counted = mutableListOf<String>()
        val base = ScriptedLanguageModel(listOf(Step.Text("ok")), capabilities = ModelCapabilities(maxInputTokens = 1000))
        val model = object : LanguageModel by base {
            // A tokenizer that sees twice as many tokens as the estimate.
            override suspend fun countTokens(text: String): Int {
                counted += text
                return estimateTokens(text) * 2
            }
        }
        val agent = Agent(model, history = Transcript(entries = history(4)), scope = backgroundScope)
        val response = agent.respond("latest")
        assertTrue(counted.isNotEmpty())
        assertTrue(response.steps.single().trimmedEntries >= 6, "trimmed ${response.steps.single().trimmedEntries}")
    }

    @Test
    fun smallConversationsAreNotCountedOrTrimmed() = runTest {
        var counts = 0
        val base = scripted(Step.Text("ok"))
        val model = object : LanguageModel by base {
            override suspend fun countTokens(text: String): Int {
                counts++
                return 1
            }
        }
        val response = Agent(model, history = Transcript(entries = history(2, size = 10)), scope = backgroundScope).respond("hi")
        assertEquals(0, counts)
        assertEquals(0, response.steps.single().trimmedEntries)
    }

    @Test
    fun trimmingCanBeDisabledAndRecentTurnsPinned() = runTest {
        val capabilities = ModelCapabilities(maxInputTokens = 1000)
        val off = scripted(Step.Text("ok"), capabilities = capabilities)
        Agent(off, history = Transcript(entries = history(10)), configuration = AgentConfiguration(context = ContextPolicy(trimsHistory = false)), scope = backgroundScope)
            .respond("latest")
        assertTrue(off.requests.single().prompt.contains("question 1 "))

        val pinned = scripted(Step.Text("ok"), capabilities = capabilities)
        val response = Agent(pinned, history = Transcript(entries = history(10)), configuration = AgentConfiguration(context = ContextPolicy(minimumRecentTurns = 9)), scope = backgroundScope)
            .respond("latest")
        assertEquals(2, response.steps.single().trimmedEntries)
    }

    @Test
    fun longToolOutputsAreShortened() = runTest {
        val big = AgentTool.local("read_book", "Read.") { ToolOutput.Text("z".repeat(5000)) }
        val model = scripted(Step.call("read_book"), Step.respond(), Step.Text("ok"))
        Agent(model, tools = listOf(big), configuration = AgentConfiguration(context = ContextPolicy(maxToolOutputChars = 100)), scope = backgroundScope)
            .respond("read")
        assertTrue(model.requests.last().prompt.contains("[read_book → " + "z".repeat(100) + "… (truncated)]"))
    }

    @Test
    fun withoutSystemInstructionSupportTheInstructionLeadsThePrompt() = runTest {
        val model = scripted(Step.respond(), Step.Text("ok"), capabilities = ModelCapabilities(systemInstructions = false))
        Agent(model, instructions = "You are Mira.", tools = listOf(Tavern.menu()), scope = backgroundScope).respond("hi")
        model.requests.forEach { request ->
            assertNull(request.systemInstruction)
            assertEquals("You are Mira.\n\n", request.promptPrefix)
            assertTrue(request.fullPrompt.startsWith("You are Mira.\n\n"))
        }
    }

    @Test
    fun systemInstructionIsStableAcrossStepsAndTurns() = runTest {
        val model = scripted(Step.call("check_menu"), Step.respond(), Step.Text("a"), Step.respond(), Step.Text("b"))
        val agent = Agent(model, instructions = "You are Mira.", tools = listOf(Tavern.menu()), scope = backgroundScope)
        agent.respond("one")
        agent.respond("two")
        assertEquals(setOf("You are Mira."), model.requests.map { it.systemInstruction }.toSet())
    }

    @Test
    fun speakerLabelsAndEchoedToolRecordsAreStrippedFromReplies() = runTest {
        val model = scripted(Step.Text("Mira: Welcome, friend! [check_menu → {\"ale\":3}]", chunks = 5))
        val agent = Agent(model, configuration = AgentConfiguration(assistantLabel = "Mira"), scope = backgroundScope)
        val events = agent.run("Hi").events.toList()
        val texts = events.filterIsInstance<AgentEvent.Text>()
        assertTrue(texts.none { it.text.startsWith("Mira") }, "$texts")
        assertEquals("Welcome, friend!", (events.last() as AgentEvent.Completed).response.text)
        assertEquals("Welcome, friend!", texts.last().text)
    }

    @Test
    fun replyCleaner() {
        val cleaner = ReplyCleaner("Mira")
        assertEquals("", cleaner.visible("Mi"))
        assertEquals("", cleaner.visible("Mira:"))
        assertEquals("Hello", cleaner.visible("Mira: Hello"))
        assertEquals("Hello", cleaner.visible("**Mira:** Hello"))
        assertEquals("Mirabelle is my cousin.", cleaner.visible("Mirabelle is my cousin."))
        assertEquals("Mi", cleaner.final("Mi"))
        assertEquals("Hi there", cleaner.final("\"Hi there\""))
        assertEquals("He said \"hi\" twice", cleaner.final("He said \"hi\" twice"))
        assertEquals("Assistant-free text", cleaner.final("Assistant: Assistant-free text"))
    }

    @Test
    fun renderIsExposedForLogs() {
        val entries = listOf(
            TranscriptEntry.Prompt("Price?"),
            TranscriptEntry.ToolUse(ToolCall(name = "check_menu"), ToolOutput.of(mapOf("ale" to 3))),
            TranscriptEntry.Response("{}", jsonObjectOf("line" to "Three.")),
        )
        assertEquals("Guest: Price?\n[check_menu → {\"ale\":3}]\nMira: line: Three.", Agent.render(entries, "Guest", "Mira"))
    }
}
