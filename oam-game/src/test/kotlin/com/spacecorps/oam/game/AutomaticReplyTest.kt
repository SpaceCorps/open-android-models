package com.spacecorps.oam.game

import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentErrorCode
import com.spacecorps.oam.GenerationKind
import com.spacecorps.oam.RetryPolicy
import com.spacecorps.oam.ToolCall
import com.spacecorps.oam.jsonObjectOf
import com.spacecorps.oam.testing.ScriptedLanguageModel.Step
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [NPCReplyFormat.AUTOMATIC]: a structured reply first, one plain-text retry
 * when it stays invalid or is blocked, and canned lines only as a last resort.
 */
class AutomaticReplyTest {
    @Test
    fun defaultIsAutomatic() {
        assertEquals(NPCReplyFormat.AUTOMATIC, NPCOptions().replyFormat)
    }

    @Test
    fun blockedStructuredReplyIsRetriedAsText() = runTest {
        val model = scripted(Fixtures.guardrail, Step.Text("[angry] Get out of my forge, lad!"))
        val npc = NPC(Fixtures.gorm, model, scope = backgroundScope)
        val turn = npc.talk("I challenge you to a duel!")
        assertFalse(turn.isFallback)
        assertEquals("Get out of my forge, lad!", turn.line)
        assertEquals(Emotion.ANGRY, turn.emotion)
        assertTrue(turn.playerOptions.isEmpty())
        assertEquals(listOf(GenerationKind.STRUCTURED, GenerationKind.RESPOND), model.requests.map { it.kind })
        val retry = model.requests[1]
        assertTrue(retry.system.contains("Begin every reply with your current emotion in square brackets"), retry.system)
        assertFalse(model.requests[0].system.contains("square brackets"))
        assertEquals(1, npc.turnCount)
    }

    @Test
    fun invalidStructuredOutputIsRepairedThenRetriedAsText() = runTest {
        // Not an emotion, and no line: invalid twice (the second attempt is the core's repair).
        val invalid = Step.Json(jsonObjectOf("emotion" to "flabbergasted"))
        val model = scripted(invalid, invalid, Step.Text("[confused] Speak plainly, lad."))
        val npc = NPC(Fixtures.gorm, model, scope = backgroundScope)
        val turn = npc.talk("Hrm?")
        assertFalse(turn.isFallback)
        assertEquals("Speak plainly, lad.", turn.line)
        assertEquals(Emotion.CONFUSED, turn.emotion)
        assertEquals(listOf(GenerationKind.STRUCTURED, GenerationKind.STRUCTURED, GenerationKind.RESPOND), model.requests.map { it.kind })
        assertTrue(model.requests[1].prompt.contains("Your previous answer was invalid"), model.requests[1].prompt)
        assertEquals(1, npc.turnCount)
    }

    @Test
    fun aRepairedStructuredReplyNeedsNoRetry() = runTest {
        val model = scripted(Step.Text("Aye, that I do."), Fixtures.reply("Aye, that I do.", emotion = "proud"))
        val npc = NPC(Fixtures.gorm, model, scope = backgroundScope)
        val turn = npc.talk("Do you forge swords?")
        assertEquals(Emotion.PROUD, turn.emotion)
        assertEquals(3, turn.playerOptions.size)
        assertEquals(2, model.requests.size)
    }

    @Test
    fun textRetryReusesToolResultsWithoutRerunningTools() = runTest {
        val calls = Collections.synchronizedList(ArrayList<ToolCall>())
        val model = scripted(
            Fixtures.call("check_inventory", "item" to "iron sword"),
            Fixtures.guardrail,
            Step.Dynamic { request ->
                val system = request.systemInstruction.orEmpty()
                if (system.contains("Facts you just looked up:") && system.contains("price_gold")) {
                    Step.Text("[neutral] Three swords, 45 gold.")
                } else {
                    Step.Text("[neutral] (tool results missing)")
                }
            },
        )
        val npc = NPC(
            Fixtures.gorm, model, tools = listOf(Fixtures.inventory(calls)),
            options = NPCOptions(groundingTool = "check_inventory"), scope = backgroundScope,
        )
        val turn = npc.talk("Swords?")
        assertEquals("Three swords, 45 gold.", turn.line)
        assertEquals(1, calls.size)
        assertEquals(1, turn.toolCalls.size)
        // The retry offered no tools; the history keeps the exchange but not the rolled-back tool use.
        assertEquals(GenerationKind.RESPOND, model.requests.last().kind)
        assertEquals(1, npc.turnCount)
        assertEquals(2, npc.transcript.entries.size)
    }

    @Test
    fun fallsBackWhenTheTextRetryIsBlockedToo() = runTest {
        val model = scripted(Fixtures.guardrail, Fixtures.refusal)
        val npc = NPC(Fixtures.gorm, model, scope = backgroundScope)
        val turn = npc.talk("Die!")
        assertTrue(turn.isFallback)
        assertEquals(NPCOptions.DEFAULT_FALLBACK_LINES[0], turn.line)
        assertEquals(0, npc.turnCount)
        assertEquals(2, model.requests.size)
    }

    @Test
    fun textRetryFailuresThatAreNotBlocksAreThrown() = runTest {
        val model = scripted(Fixtures.guardrail, Step.Fail(AgentError(AgentErrorCode.MODEL_UNAVAILABLE, "gone")))
        val npc = NPC(Fixtures.gorm, model, scope = backgroundScope)
        assertEquals(AgentErrorCode.MODEL_UNAVAILABLE, assertFailsWith<AgentError> { npc.talk("x") }.code)
        assertEquals(0, npc.turnCount)
    }

    @Test
    fun persistentGenerationFailuresEndInAFallback() = runTest {
        // Transient failures are retried per step by the core (three attempts), then the turn is retried as text.
        val failure = Step.Fail(AgentError(AgentErrorCode.GENERATION_FAILED, "inference error"))
        val model = scripted(fallback = failure)
        val npc = NPC(Fixtures.gorm, model, scope = backgroundScope)
        val turn = npc.talk("Hello")
        assertTrue(turn.isFallback)
        assertEquals(2 * RetryPolicy.Default.maxAttempts, model.requests.size)
    }

    @Test
    fun structuredOnlyFallsBackWithoutRetry() = runTest {
        val model = scripted(Fixtures.guardrail, Step.Text("[happy] should not be used"))
        val npc = NPC(Fixtures.gorm, model, options = NPCOptions(replyFormat = NPCReplyFormat.STRUCTURED), scope = backgroundScope)
        val turn = npc.talk("Die!")
        assertTrue(turn.isFallback)
        assertEquals(1, model.requests.size)
    }

    @Test
    fun streamingResetsLineOnRetry() = runTest {
        // The structured attempt streams part of a line, then its output is invalid (twice).
        val broken = Step.Text("""{"emotion": "amused", "line": "Ha! Bold wo""", chunks = 4)
        val model = scripted(broken, broken, Step.Text("[amused] Ha! Bold words.", chunks = 3))
        val npc = NPC(Fixtures.gorm, model, scope = backgroundScope)
        var shown = ""
        var resets = 0
        var completed: DialogueTurn? = null
        npc.talkStream("Fight me!").events.collect { event ->
            when (event) {
                is DialogueEvent.LineDelta -> shown += event.text
                is DialogueEvent.LineReset -> {
                    shown = event.text
                    resets++
                }
                is DialogueEvent.Completed -> completed = event.turn
                else -> Unit
            }
        }
        val turn = assertNotNull(completed)
        assertEquals(turn.line, shown)
        assertEquals("Ha! Bold words.", turn.line)
        assertFalse(turn.isFallback)
        assertTrue(resets >= 1, "the retry replaces the partial line")
    }

    @Test
    fun memoryStagedBeforeTheBlockSurvivesTheTextRetry() = runTest {
        val model = scripted(
            Fixtures.call("change_relationship", "reason" to "kind words", "delta" to 5),
            Fixtures.guardrail,
            Step.Text("[happy] Kind of you, lad."),
        )
        val npc = NPC(Fixtures.gorm, model, options = NPCOptions(memoryTools = setOf(NPCMemoryTool.CHANGE_RELATIONSHIP)), scope = backgroundScope)
        val turn = npc.talk("You're the finest smith alive!")
        assertFalse(turn.isFallback)
        assertEquals(5, turn.relationship)
        assertEquals(5, npc.memory.relationship)
        assertEquals(Emotion.HAPPY, turn.emotion)
    }

    @Test
    fun cancellingDuringTheRetryCancelsTheTurn() = runTest {
        val model = scripted(Fixtures.guardrail, Step.Delay(5.seconds, Step.Text("[happy] too late")))
        val npc = NPC(Fixtures.gorm, model, scope = backgroundScope)
        val stream = npc.talkStream("x")
        launch {
            delay(100.milliseconds)
            stream.cancel()
        }
        assertEquals(AgentErrorCode.CANCELLED, assertFailsWith<AgentError> { stream.turn() }.code)
        npc.waitUntilIdle()
        assertEquals(0, npc.turnCount)
        assertEquals(2, model.requests.size)
    }
}
