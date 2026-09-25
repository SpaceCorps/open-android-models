package com.spacecorps.oam.game

import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentErrorCode
import com.spacecorps.oam.AgentTool
import com.spacecorps.oam.GenerationKind
import com.spacecorps.oam.JsonSchema
import com.spacecorps.oam.ModelCapabilities
import com.spacecorps.oam.ToolCall
import com.spacecorps.oam.ToolChoice
import com.spacecorps.oam.ToolOutput
import com.spacecorps.oam.TranscriptEntry
import com.spacecorps.oam.jsonObjectOf
import com.spacecorps.oam.stringValue
import com.spacecorps.oam.testing.ScriptedLanguageModel
import com.spacecorps.oam.testing.ScriptedLanguageModel.Step
import com.spacecorps.oam.toJsonString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class NPCTest {
    // MARK: Talking

    @Test
    fun groundedTurnWithToolRoundAndStructuredLine() = runTest {
        val calls = Collections.synchronizedList(ArrayList<ToolCall>())
        val model = scripted(
            Fixtures.call("check_inventory", "item" to "iron sword"),
            Fixtures.reply("Gorm: \"Three iron swords, lad. 45 gold each.\"", emotion = "annoyed", options = listOf("I'll take one.", "Too pricey.", "Goodbye.")),
        )
        val npc = NPC(
            Fixtures.gorm, model, tools = listOf(Fixtures.inventory(calls)),
            options = NPCOptions(groundingTool = "check_inventory"), scope = backgroundScope,
        )
        val turn = npc.talk("Got any iron swords?")

        assertEquals("Three iron swords, lad. 45 gold each.", turn.line)
        assertEquals(Emotion.ANNOYED, turn.emotion)
        assertEquals(listOf("I'll take one.", "Too pricey.", "Goodbye."), turn.playerOptions)
        assertFalse(turn.endsConversation)
        assertFalse(turn.isFallback)
        assertEquals(listOf("check_inventory"), turn.toolCalls.map { it.call.name })
        assertEquals("iron sword", calls.single().string("item"))
        assertTrue(turn.usage.totalTokens > 0)

        // Grounding forced the tool's arguments first; afterwards the model decided freely, then replied.
        assertEquals(listOf(GenerationKind.TOOL_ARGUMENTS, GenerationKind.DECIDE, GenerationKind.STRUCTURED), model.requests.map { it.kind })
        assertEquals(listOf("check_inventory"), model.requests[1].offeredTools)
        val structured = model.requests[2]
        assertTrue(structured.prompt.contains("[check_inventory {\"item\":\"iron sword\"} →"), structured.prompt)
        for (key in listOf("\"emotion\": one of \"neutral\", \"happy\"", "\"line\"", "\"player_options\"", "\"ends_conversation\"")) {
            assertTrue(structured.prompt.contains(key), key)
        }
        assertTrue(structured.prompt.indexOf("\"emotion\"") < structured.prompt.indexOf("\"line\""))
        assertTrue(structured.system.startsWith("You play Gorm, the village blacksmith"), structured.system)
        // The conversation frames the player's words; the history shows only the spoken line.
        assertTrue(structured.prompt.contains("Player: Got any iron swords?"), structured.prompt)
        assertEquals(1, npc.turnCount)
        val response = npc.transcript.entries.last() as TranscriptEntry.Response
        assertEquals("annoyed", response.structured?.let { (it as kotlinx.serialization.json.JsonObject)["emotion"]?.stringValue })
    }

    @Test
    fun historyShowsOnlyTheSpokenLine() = runTest {
        val model = scripted(Fixtures.reply("Welcome, lad."), Fixtures.reply("Still here?"))
        val npc = NPC(Fixtures.gorm, model, scope = backgroundScope)
        npc.talk("Hello")
        npc.talk("Hello again")
        val prompt = model.requests[1].prompt
        assertTrue(prompt.contains("Gorm: Welcome, lad.\n"), prompt)
        assertFalse(prompt.contains("player_options: "), prompt)
    }

    @Test
    fun explicitChoiceAndPerTurnOverride() = runTest {
        val model = scripted(Fixtures.reply("Hmph."), Fixtures.reply("Nothing to say."), Fixtures.call("check_inventory", "item" to "axe"), Fixtures.reply("Bye."))
        val npc = NPC(Fixtures.gorm, model, tools = listOf(Fixtures.inventory()), scope = backgroundScope)
        npc.talk("Hello")
        npc.talk("Bye", toolChoice = ToolChoice.None)
        npc.talk("Anything?", toolChoice = ToolChoice.Required)
        // Explicit (the default) decides first; None replies at once; Required with one tool asks for its arguments.
        assertEquals(
            listOf(GenerationKind.DECIDE, GenerationKind.STRUCTURED, GenerationKind.STRUCTURED, GenerationKind.TOOL_ARGUMENTS, GenerationKind.DECIDE, GenerationKind.STRUCTURED),
            model.requests.map { it.kind },
        )
        assertTrue(model.requests[0].prompt.contains("Player's last message: \"Hello\""), model.requests[0].prompt)
        assertTrue(model.requests[0].prompt.contains("can also respond"), model.requests[0].prompt)
    }

    @Test
    fun npcWithoutToolsDisallowsToolCalls() = runTest {
        val model = scripted(Fixtures.reply("Hello there."))
        val npc = NPC(Fixtures.gorm, model, scope = backgroundScope)
        val turn = npc.talk("Hi")
        assertEquals("Hello there.", turn.line)
        assertEquals(listOf(GenerationKind.STRUCTURED), model.requests.map { it.kind })
        assertTrue(model.requests[0].system.contains("say so in character"))
    }

    @Test
    fun worldToolsAndContextInjection() = runTest {
        val world = WorldState.of("player" to mapOf("name" to "Aria", "gold" to 12), "time" to "night")
        val model = scripted(
            Fixtures.call("read_world_state", "path" to "player.gold"),
            Step.Dynamic { request ->
                val output = request.turn?.toolUses?.lastOrNull()?.output?.modelText.orEmpty()
                Fixtures.reply(if (output.contains("12")) "You have 12 gold, Aria." else "Hm?")
            },
            Fixtures.call("update_world_state", "path" to "npcs.gorm.mood", "value" to "pleased"),
            Fixtures.reply("Pleasure doing business."),
        )
        val npc = NPC(
            Fixtures.gorm, model, world = world,
            options = NPCOptions(worldWritable = listOf("npcs.gorm"), worldContextPaths = listOf("player.name", "time")),
            scope = backgroundScope,
        )
        val first = npc.talk("How much gold do I have?", context = "The forge is hot.")
        assertEquals("You have 12 gold, Aria.", first.line)
        val decide = model.requests[0]
        assertEquals(listOf("read_world_state", "update_world_state"), decide.offeredTools)
        assertTrue(decide.system.endsWith("Game state:\nplayer.name: Aria\ntime: night\nSituation: The forge is hot."), decide.system)
        assertTrue(decide.prompt.contains("Player's last message: \"How much gold do I have?\""), decide.prompt)

        val second = npc.talk("Thanks!")
        assertEquals(false, second.toolCalls.firstOrNull()?.output?.isError)
        assertEquals(JsonPrimitive("pleased"), world["npcs.gorm.mood"])
        // The situation was for one turn only.
        assertFalse(model.requests.last().system.contains("Situation:"))
    }

    // MARK: Streaming

    @Test
    fun streamingEmitsEmotionThenLineDeltas() = runTest {
        val json = jsonObjectOf(
            "emotion" to "happy",
            "line" to "Welcome to my forge, lad. Mind the sparks.",
            "player_options" to listOf("Thanks.", "Nice forge.", "Bye."),
            "ends_conversation" to false,
        )
        val npc = NPC(Fixtures.gorm, scripted(Step.Text(json.toJsonString(), chunks = 12)), scope = backgroundScope)
        val kinds = ArrayList<String>()
        var displayed = ""
        var final: DialogueTurn? = null
        npc.talkStream("Hello").events.collect { event ->
            when (event) {
                is DialogueEvent.Emotion -> {
                    kinds += "emotion"
                    assertEquals(Emotion.HAPPY, event.emotion)
                }
                is DialogueEvent.LineDelta -> {
                    kinds += "delta"
                    displayed += event.text
                }
                is DialogueEvent.LineReset -> {
                    kinds += "reset"
                    displayed = event.text
                }
                is DialogueEvent.Completed -> {
                    kinds += "completed"
                    final = event.turn
                }
                else -> kinds += "other"
            }
        }
        val turn = assertNotNull(final)
        assertEquals(turn.line, displayed)
        assertEquals("Welcome to my forge, lad. Mind the sparks.", turn.line)
        assertEquals("emotion", kinds.first())
        assertEquals("completed", kinds.last())
        assertEquals(1, kinds.count { it == "emotion" })
        assertFalse("reset" in kinds)
        assertTrue(kinds.count { it == "delta" } > 2, kinds.toString())
    }

    @Test
    fun plainTextRepliesStreamWithEmotionTags() = runTest {
        val model = scripted(
            Fixtures.call("check_inventory", "item" to "iron sword"),
            Step.Text("[grumpy] Three swords, lad. Forty-five gold each.", chunks = 8),
        )
        val npc = NPC(
            Fixtures.gorm, model, tools = listOf(Fixtures.inventory()),
            options = NPCOptions(groundingTool = "check_inventory", replyFormat = NPCReplyFormat.TEXT), scope = backgroundScope,
        )
        val emotions = ArrayList<Emotion>()
        var displayed = ""
        var final: DialogueTurn? = null
        npc.talkStream("Swords?").events.collect { event ->
            when (event) {
                is DialogueEvent.Emotion -> emotions += event.emotion
                is DialogueEvent.LineDelta -> displayed += event.text
                is DialogueEvent.LineReset -> displayed = event.text
                is DialogueEvent.Completed -> final = event.turn
                else -> Unit
            }
        }
        val turn = assertNotNull(final)
        assertEquals("Three swords, lad. Forty-five gold each.", turn.line)
        assertEquals(Emotion.ANNOYED, turn.emotion)
        assertEquals(listOf(Emotion.ANNOYED), emotions)
        assertEquals(turn.line, displayed)
        assertFalse(displayed.contains("["))
        assertTrue(turn.playerOptions.isEmpty() && !turn.endsConversation)
        assertEquals(1, turn.toolCalls.size)
        // No schema was requested; the tag rule is in the instructions.
        assertEquals(GenerationKind.RESPOND, model.requests.last().kind)
        assertTrue(model.requests[0].system.contains("Begin every reply with your current emotion in square brackets"))
    }

    @Test
    fun streamingSurfacesToolActivityAndExternalCalls() = runTest {
        val gate = AgentTool.external("open_gate", "Open a gate.", JsonSchema.obj("gate" to JsonSchema.string()))
        val model = scripted(
            Fixtures.call("open_gate", "gate" to "north"),
            Step.Dynamic { request -> Fixtures.reply(if (request.turn?.toolUses.isNullOrEmpty()) "?" else "The gate is open.") },
        )
        val npc = NPC(Fixtures.gorm, model, tools = listOf(gate, Fixtures.inventory()), scope = backgroundScope)
        val stream = npc.talkStream("Open the north gate", toolChoice = ToolChoice.Tool("open_gate"))
        val requested = ArrayList<ToolCall>()
        val results = ArrayList<ToolOutput>()
        var final: DialogueTurn? = null
        stream.events.collect { event ->
            when (event) {
                is DialogueEvent.ExternalToolCall -> {
                    requested += event.call
                    assertEquals(listOf(event.call.id), stream.pendingToolCalls.map { it.id })
                    assertTrue(stream.submit(ToolOutput.of(mapOf("opened" to true)), event.call.id))
                    assertFalse(stream.submit(ToolOutput.Text("again"), event.call.id))
                }
                is DialogueEvent.ToolResult -> results += event.record.output
                is DialogueEvent.ToolCall -> error("open_gate is external")
                is DialogueEvent.Completed -> final = event.turn
                else -> Unit
            }
        }
        assertEquals(listOf("open_gate"), requested.map { it.name })
        assertEquals(ToolOutput.of(mapOf("opened" to true)), results.single())
        assertEquals("The gate is open.", final?.line)
        assertEquals(1, final?.toolCalls?.size)
        assertTrue(stream.isFinished)
    }

    @Test
    fun localToolsAreAnnouncedWhenStreaming() = runTest {
        val model = scripted(Fixtures.call("check_inventory", "item" to "axe"), Fixtures.reply("Two axes."))
        val npc = NPC(Fixtures.gorm, model, tools = listOf(Fixtures.inventory()), scope = backgroundScope)
        val started = ArrayList<String>()
        npc.talkStream("Axes?").events.collect { if (it is DialogueEvent.ToolCall) started += it.call.name }
        assertEquals(listOf("check_inventory"), started)
    }

    @Test
    fun talkRunsExternalToolsWithHandler() = runTest {
        val gate = AgentTool.external("open_gate", "Open a gate.", JsonSchema.obj("gate" to JsonSchema.string()))
        val model = scripted(Fixtures.call("open_gate", "gate" to "north"), Fixtures.reply("Done."), Fixtures.call("open_gate", "gate" to "south"), Fixtures.reply("Hm."))
        val npc = NPC(Fixtures.gorm, model, tools = listOf(gate), scope = backgroundScope)
        val turn = npc.talk("Open it") { call -> ToolOutput.Text("opened ${call.string("gate")}") }
        assertEquals(ToolOutput.Text("opened north"), turn.toolCalls.single().output)
        // Without a handler, external calls get an error output and the turn still completes.
        val unhandled = npc.talk("And the south one")
        assertTrue(unhandled.toolCalls.single().output.isError)
        // A throwing handler becomes an error output too.
        model.append(Fixtures.call("open_gate", "gate" to "east"), Fixtures.reply("Stuck."))
        val failing = npc.talk("East?") { error("jammed") }
        assertEquals(ToolOutput.Error("jammed"), failing.toolCalls.single().output)
    }

    @Test
    fun cancellationLeavesHistoryClean() = runTest {
        val model = scripted(Step.Delay(5.seconds, Fixtures.reply("too late")))
        val npc = NPC(Fixtures.gorm, model, scope = backgroundScope)
        val stream = npc.talkStream("Wait")
        launch {
            delay(100.milliseconds)
            stream.cancel()
        }
        val error = assertFailsWith<AgentError> { stream.turn() }
        assertEquals(AgentErrorCode.CANCELLED, error.code)
        npc.waitUntilIdle()
        assertEquals(0, npc.turnCount)
        // A stream cancelled before it starts never reaches the model.
        val early = npc.talkStream("Never mind")
        early.cancel()
        assertTrue(early.isCancelled)
        assertFailsWith<AgentError> { early.turn() }
        npc.waitUntilIdle()
        assertEquals(1, model.requests.size)
    }

    @Test
    fun cancellingTheCallerOfTalkCancelsTheTurn() = runTest {
        val model = scripted(Step.Delay(5.seconds, Fixtures.reply("too late")), Fixtures.reply("next"))
        val npc = NPC(Fixtures.gorm, model, scope = backgroundScope)
        val job = launch { npc.talk("Hello?") }
        delay(100.milliseconds)
        job.cancel()
        job.join()
        npc.waitUntilIdle()
        assertEquals(0, npc.turnCount)
        assertEquals("next", npc.talk("Again").line)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellingAQueuedTalkEndsItImmediately() = runTest {
        val model = scripted(Step.Delay(2.seconds, Fixtures.reply("first")), Fixtures.reply("never"))
        val npc = NPC(Fixtures.gorm, model, scope = backgroundScope)
        val first = npc.talkStream("one")
        val second = npc.talkStream("two")
        second.cancel()
        val error = assertFailsWith<AgentError> { second.turn() }
        assertEquals(AgentErrorCode.CANCELLED, error.code)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals("first", first.turn().line)
        npc.waitUntilIdle()
        assertEquals(1, model.requests.size)
    }

    @Test
    fun eventsCanBeCollectedOnlyOnce() = runTest {
        val npc = NPC(Fixtures.gorm, scripted(Fixtures.reply("Hi.")), scope = backgroundScope)
        val stream = npc.talkStream("Hello")
        assertEquals("Hi.", stream.turn().line)
        assertFailsWith<IllegalStateException> { stream.events.collect { } }
    }

    // MARK: Memory

    @Test
    fun memoryToolsStageAndCommitOnSuccess() = runTest {
        val model = scripted(
            Step.ToolCalls(
                Step.ScriptedCall("remember_fact", jsonObjectOf("fact" to "The player's name is Aria.")),
                Step.ScriptedCall("change_relationship", jsonObjectOf("reason" to "She complimented my work.", "delta" to 8)),
            ),
            Fixtures.reply("Aria, eh? Kind words.", emotion = "proud"),
            Fixtures.reply("Back again, Aria?"),
        )
        val npc = NPC(Fixtures.gorm, model, options = NPCOptions(memoryTools = NPCMemoryTool.ALL, maxToolRounds = 3), scope = backgroundScope)
        val first = npc.talk("I'm Aria. Your blades are the finest in the land!")
        assertEquals(8, first.relationship)
        assertEquals(listOf("The player's name is Aria."), npc.memory.facts)
        assertEquals(8, npc.memory.relationship)
        assertEquals(setOf("remember_fact", "change_relationship"), first.toolCalls.map { it.call.name }.toSet())
        assertTrue(model.requests[0].system.contains("call remember_fact"))
        assertTrue(model.requests[0].system.contains("call change_relationship"))
        // Memory tools are not fact tools: the persona admits ignorance instead of promising lookups.
        assertTrue(model.requests[0].system.contains("say so in character"))

        npc.talk("Hello again")
        val system = model.requests.last().system
        assertTrue(system.contains("You remember: The player's name is Aria."), system)
        assertTrue(system.contains("You feel neutral toward the player (8 on a scale from -100 to 100)."), system)
    }

    @Test
    fun duplicateFactsAreNotStagedTwice() = runTest {
        val model = scripted(
            Fixtures.call("remember_fact", "fact" to "Aria likes axes."),
            Fixtures.call("remember_fact", "fact" to "aria likes axes"),
            Fixtures.reply("Noted."),
        )
        val npc = NPC(Fixtures.gorm, model, options = NPCOptions(memoryTools = setOf(NPCMemoryTool.REMEMBER_FACT)), scope = backgroundScope)
        val turn = npc.talk("I like axes.")
        assertEquals(listOf("Remembered.", "You already know that."), turn.toolCalls.map { it.output.modelText })
        assertEquals(listOf("Aria likes axes."), npc.memory.facts)
    }

    @Test
    fun relationshipChangesAreClampedPerCallAndOverall() = runTest {
        val model = scripted(
            Fixtures.call("change_relationship", "reason" to "Insulted my mother.", "delta" to -50),
            Fixtures.reply("Get out!", emotion = "angry"),
        )
        val npc = NPC(
            Fixtures.gorm, model,
            options = NPCOptions(memoryTools = setOf(NPCMemoryTool.CHANGE_RELATIONSHIP), maxRelationshipChange = 10),
            memory = NPCMemory(relationship = -95), scope = backgroundScope,
        )
        val turn = npc.talk("Your mother was a goblin.")
        assertEquals(-100, turn.relationship)
        assertTrue(turn.toolCalls.single().output.modelText.contains("hostile"))
        assertEquals(-100, npc.memory.relationship)
        // The range is described to the model, not enforced, so the call ran without a repair.
        assertTrue(model.requests[0].prompt.contains("From -10 (the player upset you) to 10"), model.requests[0].prompt)
    }

    @Test
    fun secretsAreGuardedWhenThresholdDisabled() = runTest {
        val model = scripted(Fixtures.reply("No."))
        val npc = NPC(Fixtures.gorm, model, options = NPCOptions(secretsUnlockAtRelationship = null), scope = backgroundScope)
        npc.talk("Any secrets?")
        assertTrue(model.requests[0].system.contains("Your secret: He forged the blade that killed the old king."))
        assertTrue(model.requests[0].system.contains("Keep your secret unless"))
    }

    @Test
    fun secretsUnlockWithRelationship() = runTest {
        val model = scripted(Fixtures.reply("No."), Fixtures.reply("Fine. I'll tell you."))
        val npc = NPC(Fixtures.gorm, model, options = NPCOptions(secretsUnlockAtRelationship = 60), memory = NPCMemory(relationship = 10), scope = backgroundScope)
        npc.talk("Any secrets?")
        assertFalse(model.requests[0].system.contains("killed the old king"))
        npc.memory = npc.memory.copy(relationship = 75)
        npc.talk("Any secrets now?")
        assertTrue(model.requests[1].system.contains("killed the old king"))
        assertTrue(model.requests[1].system.contains("You may share your secret"))
        assertEquals(80, npc.updateMemory { it.adjustingRelationship(5) }.relationship)
    }

    // MARK: Context management

    @Test
    fun compactionSummarizesOlderTurnsIntoMemory() = runTest {
        val model = scripted(
            Fixtures.reply("One."), Fixtures.reply("Two."), Fixtures.reply("Three."),
            Step.Text("Aria asked about swords three times; Gorm stayed gruff."),
            Fixtures.reply("Four."),
        )
        val npc = NPC(Fixtures.gorm, model, options = NPCOptions(compactAfterTurns = 3, keepRecentTurns = 1), scope = backgroundScope)
        for (line in listOf("a", "b")) npc.talk(line)
        npc.waitUntilIdle()
        assertNull(npc.memory.summary)
        assertEquals(2, npc.turnCount)

        npc.talk("c")
        val settled = npc.settledState()
        val summary = "Aria asked about swords three times; Gorm stayed gruff."
        assertEquals(summary, settled.memory.summary)
        assertEquals(1, settled.transcript.turnCount)
        assertEquals(summary, npc.memory.summary)
        assertEquals(1, npc.turnCount)
        val summarizer = model.requests[3]
        assertEquals(GenerationKind.SUMMARY, summarizer.kind)
        assertTrue(summarizer.system.contains("Gorm's memory"), summarizer.system)
        assertTrue(summarizer.prompt.contains("Player: a"), summarizer.prompt)
        assertTrue(summarizer.prompt.contains("Gorm: "), summarizer.prompt)

        npc.talk("d")
        val next = model.requests[4]
        assertTrue(next.system.contains("Earlier conversation: Aria asked about swords three times"), next.system)
        // The kept turn plus the new prompt.
        assertEquals(2, next.playerLines)
    }

    @Test
    fun manualCompactionWithTooLittleHistory() = runTest {
        val npc = NPC(Fixtures.gorm, scripted(Fixtures.reply("Hi.")), options = NPCOptions(compactAfterTurns = 0), scope = backgroundScope)
        npc.talk("hello")
        assertNull(npc.compact())
        assertEquals(1, npc.turnCount)
    }

    @Test
    fun manualCompactionErrorsAreThrownAndChangeNothing() = runTest {
        val model = scripted(Fixtures.reply("Hi."), Fixtures.reply("Hello."), Step.Fail(AgentError(AgentErrorCode.MODEL_UNAVAILABLE, "gone")))
        val npc = NPC(Fixtures.gorm, model, options = NPCOptions(compactAfterTurns = 0, keepRecentTurns = 1), scope = backgroundScope)
        npc.talk("a")
        npc.talk("b")
        val error = assertFailsWith<AgentError> { npc.compact() }
        assertEquals(AgentErrorCode.MODEL_UNAVAILABLE, error.code)
        assertEquals(2, npc.turnCount)
        assertNull(npc.memory.summary)
    }

    // MARK: Fallbacks

    @Test
    fun guardrailFallbackKeepsHistoryAndMemoryClean() = runTest {
        val model = scripted(
            Fixtures.reply("Welcome."),
            Fixtures.call("change_relationship", "reason" to "x", "delta" to 5),
            Fixtures.guardrail,
            Fixtures.reply("Anything else?"),
        )
        val npc = NPC(
            Fixtures.gorm, model,
            options = NPCOptions(memoryTools = setOf(NPCMemoryTool.CHANGE_RELATIONSHIP), replyFormat = NPCReplyFormat.STRUCTURED, fallbackLines = listOf("Watch your tongue.")),
            scope = backgroundScope,
        )
        npc.talk("Hello")
        val historyBefore = npc.transcript.entries.size

        val blocked = npc.talk("<something the filters block>")
        assertTrue(blocked.isFallback)
        assertEquals("Watch your tongue.", blocked.line)
        assertEquals(Fixtures.gorm.defaultEmotion, blocked.emotion)
        assertTrue(blocked.playerOptions.isEmpty())
        assertEquals(0, blocked.relationship)
        assertEquals(listOf("change_relationship"), blocked.toolCalls.map { it.call.name })
        // Neither the failed exchange nor its staged memory change survive.
        assertEquals(historyBefore, npc.transcript.entries.size)
        assertEquals(1, npc.turnCount)
        assertEquals(0, npc.memory.relationship)

        val next = npc.talk("Sorry.")
        assertEquals("Anything else?", next.line)
        assertEquals(2, model.requests.last().playerLines)
    }

    @Test
    fun guardrailFallbackInStreamingReplacesPartialText() = runTest {
        val npc = NPC(Fixtures.gorm, scripted(Fixtures.guardrail), options = NPCOptions(replyFormat = NPCReplyFormat.STRUCTURED), scope = backgroundScope)
        var displayed = ""
        var final: DialogueTurn? = null
        npc.talkStream("<blocked>").events.collect { event ->
            when (event) {
                is DialogueEvent.LineDelta -> displayed += event.text
                is DialogueEvent.LineReset -> displayed = event.text
                is DialogueEvent.Completed -> final = event.turn
                else -> Unit
            }
        }
        assertEquals(true, final?.isFallback)
        assertTrue(displayed in NPCOptions.DEFAULT_FALLBACK_LINES)
        assertEquals(final?.line, displayed)
    }

    @Test
    fun fallbackLinesRotate() = runTest {
        val npc = NPC(Fixtures.gorm, scripted(fallback = Fixtures.guardrail), options = NPCOptions(replyFormat = NPCReplyFormat.TEXT), scope = backgroundScope)
        val lines = (0 until 4).map { npc.talk("x").line }
        assertEquals(NPCOptions.DEFAULT_FALLBACK_LINES + NPCOptions.DEFAULT_FALLBACK_LINES[0], lines)
    }

    @Test
    fun guardrailThrowsWhenFallbackDisabled() = runTest {
        val npc = NPC(
            Fixtures.gorm, scripted(Fixtures.refusal),
            options = NPCOptions(replyFormat = NPCReplyFormat.STRUCTURED, fallbackOnGuardrail = false), scope = backgroundScope,
        )
        val error = assertFailsWith<AgentError> { npc.talk("x") }
        assertEquals(AgentErrorCode.REFUSAL, error.code)
        assertEquals(0, npc.turnCount)
    }

    @Test
    fun otherErrorsAreNotMaskedByFallback() = runTest {
        for (code in listOf(AgentErrorCode.CONTEXT_SIZE_EXCEEDED, AgentErrorCode.MODEL_UNAVAILABLE, AgentErrorCode.UNSUPPORTED_LANGUAGE)) {
            val model = scripted(Step.Fail(AgentError(code, "no")))
            val npc = NPC(Fixtures.gorm, model, scope = backgroundScope)
            val error = assertFailsWith<AgentError> { npc.talk("x") }
            assertEquals(code, error.code)
            // No text retry either.
            assertEquals(1, model.requests.size)
        }
    }

    @Test
    fun emptyLinesBecomeFallbacks() = runTest {
        val npc = NPC(Fixtures.gorm, scripted(Fixtures.reply("  ")), scope = backgroundScope)
        val turn = npc.talk("...")
        assertTrue(turn.isFallback)
        assertEquals(NPCOptions.DEFAULT_FALLBACK_LINES[0], turn.line)
    }

    // MARK: Barks

    @Test
    fun barkIsASingleCleanLineWithoutHistory() = runTest {
        val world = WorldState.of("weather" to "rain")
        val model = scripted(Fixtures.reply("Hello."), Step.Text("Gorm: \"Fine steel, fresh from the forge!\"\nAnd more text."))
        val npc = NPC(
            Fixtures.gorm, model, tools = listOf(Fixtures.inventory()), world = world,
            options = NPCOptions(toolChoice = ToolChoice.None, worldContextPaths = listOf("weather"), barkMaxTokens = 30),
            scope = backgroundScope,
        )
        npc.talk("Hi")
        val bark = npc.bark("A customer walks past the stall.")
        assertEquals("Fine steel, fresh from the forge!", bark)
        val request = model.requests.last()
        assertEquals(GenerationKind.RESPOND, request.kind)
        assertEquals(30, request.maxOutputTokens)
        assertEquals("Game state:\nweather: rain\nSituation: A customer walks past the stall.\nPlayer: (The player says nothing.)", request.prompt)
        assertTrue(request.system.contains("at most 1 short sentence"))
        assertFalse(request.system.contains("killed the old king"))
        assertFalse(request.system.contains("Use your tools"))
        assertEquals(1, npc.turnCount)
    }

    @Test
    fun barkErrorsAreThrown() = runTest {
        val npc = NPC(Fixtures.gorm, scripted(Fixtures.guardrail, Step.Text("   ")), scope = backgroundScope)
        assertEquals(AgentErrorCode.GUARDRAIL_VIOLATION, assertFailsWith<AgentError> { npc.bark("x") }.code)
        assertEquals(AgentErrorCode.GENERATION_FAILED, assertFailsWith<AgentError> { npc.bark("x") }.code)
    }

    @Test
    fun barkRunsWhileATurnIsInProgress() = runTest {
        val model = scripted(Step.Delay(1.seconds, Fixtures.reply("Slow reply.")), Step.Text("Quick bark!"))
        val npc = NPC(Fixtures.gorm, model, scope = backgroundScope)
        val turn = async { npc.talk("Tell me a long story.") }
        delay(10.milliseconds)
        assertTrue(npc.isTalking)
        assertEquals("Quick bark!", npc.bark("Someone waves."))
        assertEquals("Slow reply.", turn.await().line)
    }

    // MARK: Persistence

    @Test
    fun saveAndRestore() = runTest {
        val model = scripted(
            Fixtures.call("remember_fact", "fact" to "Aria wants a shield."),
            Fixtures.reply("A shield, then."),
            Step.Dynamic { request -> Fixtures.reply("turns=${Regex("^Player: ", RegexOption.MULTILINE).findAll(request.prompt).count()}") },
        )
        val options = NPCOptions(memoryTools = setOf(NPCMemoryTool.REMEMBER_FACT))
        val npc = NPC(Fixtures.gorm, model, options = options, scope = backgroundScope)
        npc.talk("I need a shield.")
        val text = npc.saveState().toJsonString()

        val saved = NPCSaveState.fromJsonString(text)
        assertEquals(1, saved.version)
        assertEquals(Fixtures.gorm, saved.persona)
        assertEquals(listOf("Aria wants a shield."), saved.memory.facts)
        val restored = NPC.restore(saved, model, options = options, scope = backgroundScope)
        assertEquals(npc.memory, restored.memory)
        assertEquals(1, restored.turnCount)
        val turn = restored.talk("Remember me?")
        assertEquals("turns=2", turn.line)
        assertTrue(model.requests.last().system.contains("Aria wants a shield."))
    }

    @Test
    fun saveStateDropsATurnInProgress() = runTest {
        val model = scripted(Fixtures.reply("One."), Step.Delay(1.seconds, Fixtures.reply("Two.")))
        val npc = NPC(Fixtures.gorm, model, scope = backgroundScope)
        npc.talk("a")
        val running = async { npc.talk("b") }
        delay(10.milliseconds)
        assertEquals(1, npc.saveState().transcript.turnCount)
        running.await()
        assertEquals(2, npc.saveState().transcript.turnCount)
    }

    @Test
    fun resetConversation() = runTest {
        val model = scripted(Fixtures.reply("One."), Fixtures.reply("Fresh start."))
        val npc = NPC(Fixtures.gorm, model, memory = NPCMemory(listOf("x"), 20), scope = backgroundScope)
        npc.talk("a")
        npc.resetConversation()
        assertEquals(0, npc.turnCount)
        assertEquals(20, npc.memory.relationship)
        npc.resetConversation(clearingMemory = true)
        assertEquals(NPCMemory(), npc.memory)
        npc.talk("b")
        assertFalse(model.requests[1].system.contains("Memory:"))
        // No history left: the first turn is a plain request.
        assertFalse(model.requests[1].prompt.contains("Conversation:"), model.requests[1].prompt)
    }

    // MARK: Configuration

    @Test
    fun invalidConfigurationIsRejected() = runTest {
        val model = scripted()
        fun code(block: () -> Unit) = assertFailsWith<AgentError> { block() }.code
        assertEquals(AgentErrorCode.INVALID_REQUEST, code { NPC(Persona(name = " "), model, scope = backgroundScope) })
        assertEquals(AgentErrorCode.INVALID_REQUEST, code { NPC(Fixtures.gorm, model, tools = listOf(Fixtures.inventory()), options = NPCOptions(groundingTool = "missing_tool"), scope = backgroundScope) })
        val clash = AgentTool.local("read_world_state", "Mine.") { ToolOutput.Text("x") }
        assertEquals(AgentErrorCode.INVALID_REQUEST, code { NPC(Fixtures.gorm, model, tools = listOf(clash), world = WorldState(), scope = backgroundScope) })
        assertEquals(AgentErrorCode.INVALID_REQUEST, code { NPC(Fixtures.gorm, model, options = NPCOptions(toolChoice = ToolChoice.Tool("nope")), scope = backgroundScope) })
        assertEquals(AgentErrorCode.INVALID_REQUEST, code { NPC(Fixtures.gorm, model, options = NPCOptions(maxToolRounds = -1), scope = backgroundScope) })
        assertEquals(AgentErrorCode.INVALID_REQUEST, code { NPC(Fixtures.gorm, model, options = NPCOptions(temperature = -1.0), scope = backgroundScope) })
        assertEquals(AgentErrorCode.INVALID_REQUEST, code { NPC(Fixtures.gorm, model, options = NPCOptions(maxResponseTokens = 0), scope = backgroundScope) })

        val npc = NPC(Fixtures.gorm, model, tools = listOf(Fixtures.inventory()), scope = backgroundScope)
        assertEquals(AgentErrorCode.INVALID_REQUEST, code { npc.setOptions(NPCOptions(groundingTool = "nope")) })
        npc.setOptions(NPCOptions(groundingTool = "check_inventory"))
        assertEquals("check_inventory", npc.options.groundingTool)
        // Removing the grounding tool is rejected while it is in use.
        assertEquals(AgentErrorCode.INVALID_REQUEST, code { npc.setTools(emptyList()) })
        assertEquals(AgentErrorCode.INVALID_REQUEST, code { npc.persona = Persona(name = "") })
        assertEquals("Gorm", npc.persona.name)
        // A per-turn tool the NPC lacks fails that turn only.
        val error = assertFailsWith<AgentError> { npc.talk("x", toolChoice = ToolChoice.Tool("teleport")) }
        assertEquals(AgentErrorCode.INVALID_REQUEST, error.code)
        assertTrue(model.requests.isEmpty())
    }

    @Test
    fun toolPersonaAndOptionChangesApplyNextTurn() = runTest {
        val model = scripted(Fixtures.reply("a"), Fixtures.reply("b"))
        val npc = NPC(Fixtures.gorm, model, tools = listOf(Fixtures.inventory()), scope = backgroundScope)
        npc.talk("1")
        npc.setTools(emptyList())
        npc.setOptions(NPCOptions(memoryTools = setOf(NPCMemoryTool.REMEMBER_FACT), playerOptionCount = 0))
        npc.persona = Fixtures.gorm.copy(name = "Gormund")
        npc.talk("2")
        assertEquals(listOf("check_inventory"), model.requests[0].offeredTools)
        assertEquals(listOf("remember_fact"), model.requests[2].offeredTools)
        assertTrue(npc.tools.isEmpty())
        val structured = model.requests[3]
        assertFalse(structured.prompt.contains("player_options"), structured.prompt)
        assertTrue(structured.system.startsWith("You play Gormund"), structured.system)
        // The history is relabelled with the new name.
        assertTrue(structured.prompt.contains("Gormund: a"), structured.prompt)
    }

    @Test
    fun differentNPCsTalkConcurrently() = runTest {
        val model = ScriptedLanguageModel(
            fallback = Step.Dynamic { request -> Fixtures.reply("echo: ${request.turn?.prompt}") },
            style = ScriptedLanguageModel.ScriptStyle.NATIVE,
        )
        val a = NPC(Persona(name = "A"), model, scope = backgroundScope)
        val b = NPC(Persona(name = "B"), model, scope = backgroundScope)
        val (x, y) = listOf(async { a.talk("one") }, async { b.talk("two") }).awaitAll()
        assertEquals("echo: one", x.line)
        assertEquals("echo: two", y.line)
    }

    @Test
    fun turnsOnOneNPCRunInOrder() = runTest {
        val model = ScriptedLanguageModel(
            fallback = Step.Dynamic { request -> Step.Delay(10.milliseconds, Fixtures.reply("re: ${request.turn?.prompt}")) },
            style = ScriptedLanguageModel.ScriptStyle.NATIVE,
        )
        val npc = NPC(Fixtures.gorm, model, scope = backgroundScope)
        val streams = (1..4).map { npc.talkStream("line $it") }
        assertEquals((1..4).map { "re: line $it" }, streams.map { it.turn().line })
        assertEquals(4, npc.turnCount)
    }

    @Test
    fun instructionsFallIntoThePromptWithoutSystemInstructionSupport() = runTest {
        val model = ScriptedLanguageModel(
            listOf(Fixtures.reply("Hi.")),
            capabilities = ModelCapabilities(systemInstructions = false),
            style = ScriptedLanguageModel.ScriptStyle.NATIVE,
        )
        val npc = NPC(Fixtures.gorm, model, scope = backgroundScope)
        npc.talk("Hello")
        val request = model.requests.single()
        assertNull(request.systemInstruction)
        assertTrue(request.fullPrompt.startsWith("You play Gorm"), request.fullPrompt)
    }

    @Test
    fun closeCancelsQueuedTurns() = runTest {
        val model = scripted(Step.Delay(5.seconds, Fixtures.reply("never")))
        val npc = NPC(Fixtures.gorm, model, scope = backgroundScope)
        val running = npc.talkStream("one")
        val queued = npc.talkStream("two")
        delay(10.milliseconds)
        npc.close()
        assertEquals(AgentErrorCode.CANCELLED, assertFailsWith<AgentError> { running.turn() }.code)
        assertEquals(AgentErrorCode.CANCELLED, assertFailsWith<AgentError> { queued.turn() }.code)
        assertEquals(AgentErrorCode.CANCELLED, assertFailsWith<AgentError> { npc.talk("three") }.code)
    }
}
