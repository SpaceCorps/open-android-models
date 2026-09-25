package com.spacecorps.oam.game

import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentErrorCode
import com.spacecorps.oam.TokenUsage
import com.spacecorps.oam.ToolCall
import com.spacecorps.oam.ToolChoice
import com.spacecorps.oam.ToolOutput
import com.spacecorps.oam.ToolRecord
import com.spacecorps.oam.Transcript
import com.spacecorps.oam.TranscriptEntry
import com.spacecorps.oam.arrayValue
import com.spacecorps.oam.jsonObjectOf
import com.spacecorps.oam.objectValue
import com.spacecorps.oam.stringValue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GameTypesTest {
    // MARK: Persona

    @Test
    fun instructionsCoverCharacterAndRules() {
        val text = Fixtures.gorm.instructions()
        assertTrue(text.startsWith("You play Gorm, the village blacksmith, a character in a video game."), text)
        assertTrue(text.contains("Personality: Gruff and proud, but fair."))
        assertTrue(text.contains("Speaking style: Short, blunt sentences. Calls people 'lad'."))
        assertTrue(text.contains("Background: Forged weapons for the king's army for twenty years."))
        assertTrue(text.contains("Goals: Sell his weapons at a fair price."))
        assertTrue(text.contains("You know: The mine to the north is haunted."))
        assertTrue(text.contains("Your secret: He forged the blade that killed the old king."))
        assertTrue(text.contains("Never say you are an AI"))
        assertTrue(text.contains("at most 2 short sentences"))
        assertTrue(text.contains("Use your tools to check facts"))
        assertTrue(text.contains("- When a tool gives you facts, say the specific ones the player asked about (names, prices, numbers)."))
        assertTrue(text.contains("Keep your secret unless the player has truly earned your trust."))
    }

    @Test
    fun instructionsStayCompact() {
        // About four characters per token: a full persona stays well under 250 tokens.
        val text = Fixtures.gorm.instructions(extra = "The town is under siege.")
        assertTrue(text.length < 1100, "${text.length} characters")
        val minimal = Persona(name = "Guard").instructions()
        assertTrue(minimal.length < 450, "${minimal.length} characters")
        assertFalse(minimal.contains("Personality:"))
        assertFalse(minimal.contains("secret"))
    }

    @Test
    fun instructionVariants() {
        val persona = Fixtures.gorm.copy(maxSentences = 1)
        val noTools = persona.instructions(usesTools = false)
        assertTrue(noTools.contains("at most 1 short sentence."))
        assertTrue(noTools.contains("say so in character. Never invent it."))
        assertFalse(noTools.contains("Use your tools"))
        assertFalse(noTools.contains("When a tool gives you facts"))

        val hidden = persona.instructions(secretVisibility = Persona.SecretVisibility.HIDDEN)
        assertFalse(hidden.contains("killed the old king"))
        assertFalse(hidden.contains("secret"))
        val shareable = persona.instructions(secretVisibility = Persona.SecretVisibility.SHAREABLE)
        assertTrue(shareable.contains("killed the old king"))
        assertTrue(shareable.contains("You may share your secret if asked."))

        assertTrue(persona.instructions(extra = "Setting: a frozen port.").endsWith("Setting: a frozen port."))
        assertTrue(Persona(name = "X", maxSentences = 0).instructions().contains("at most 1 short sentence."))
    }

    @Test
    fun personaDecodesWithDefaults() {
        val persona = GameJson.decodeFromString(
            Persona.serializer(),
            """{"name":"Mira","role":null,"secrets":["Owes the guild"],"defaultEmotion":"happy","unknown":1}""",
        )
        assertEquals("Mira", persona.name)
        assertEquals("", persona.role)
        assertEquals(2, persona.maxSentences)
        assertEquals(listOf("Owes the guild"), persona.secrets)
        assertEquals(Emotion.HAPPY, persona.defaultEmotion)
        val text = GameJson.encodeToString(Persona.serializer(), Fixtures.gorm)
        assertTrue(text.contains("\"speakingStyle\""), text)
        assertEquals(Fixtures.gorm, GameJson.decodeFromString(Persona.serializer(), text))
    }

    // MARK: Emotion

    @Test
    fun emotionParsing() {
        assertEquals(Emotion.ANGRY, Emotion.matching("Angry"))
        assertEquals(Emotion.AFRAID, Emotion.matching(" scared! "))
        assertEquals(Emotion.ANNOYED, Emotion.matching("grumpy"))
        assertEquals(Emotion.HAPPY, Emotion.matching("[happy]"))
        assertNull(Emotion.matching("flabbergasted"))
        assertEquals(Emotion.PROUD, Emotion.fromWireName("proud"))
        assertNull(Emotion.fromWireName("Proud"))
        val decoded = GameJson.decodeFromString(ListSerializer(Emotion.serializer()), """["happy","MAD","???"]""")
        assertEquals(listOf(Emotion.HAPPY, Emotion.ANGRY, Emotion.NEUTRAL), decoded)
        assertEquals("\"proud\"", GameJson.encodeToString(Emotion.serializer(), Emotion.PROUD))
        assertEquals(16, Emotion.entries.size)
    }

    // MARK: Memory

    @Test
    fun memoryClampsRelationship() {
        val memory = NPCMemory(relationship = 250)
        assertEquals(100, memory.relationship)
        val lowered = memory.adjustingRelationship(-500)
        assertEquals(-100, lowered.relationship)
        assertEquals("hostile", lowered.attitude)
        assertEquals("friendly", memory.copy(relationship = 30).attitude)
        assertEquals(100, lowered.adjustingRelationship(Int.MAX_VALUE).relationship)
        assertEquals(-100, memory.adjustingRelationship(Int.MIN_VALUE).relationship)
        val decoded = GameJson.decodeFromString(NPCMemory.serializer(), """{"relationship":999}""")
        assertEquals(100, decoded.relationship)
        assertTrue(decoded.facts.isEmpty())
        assertNull(decoded.summary)
        assertEquals(listOf("hostile", "unfriendly", "neutral", "neutral", "friendly", "trusting"), listOf(-61, -21, -20, 20, 60, 61).map(NPCMemory::attitude))
    }

    @Test
    fun memoryDeduplicatesAndLimitsFacts() {
        val memory = NPCMemory().remembering("The player's name is Aria.")
        assertEquals(listOf("The player's name is Aria."), memory.facts)
        assertSame(memory, memory.remembering("the players name is aria"))
        assertSame(memory, memory.remembering("   "))
        assertTrue(memory.knows("THE PLAYER'S NAME IS ARIA"))
        var limited = NPCMemory()
        for (index in 0 until 5) limited = limited.remembering("Fact $index", limit = 3)
        assertEquals(listOf("Fact 2", "Fact 3", "Fact 4"), limited.facts)
    }

    @Test
    fun memorySerializationAndEquality() {
        val memory = NPCMemory(listOf("Aria owes 10 gold"), 35, "They haggled.")
        val text = GameJson.encodeToString(NPCMemory.serializer(), memory)
        assertEquals("""{"facts":["Aria owes 10 gold"],"relationship":35,"summary":"They haggled."}""", text)
        assertEquals(memory, GameJson.decodeFromString(NPCMemory.serializer(), text))
        assertEquals(memory.hashCode(), NPCMemory(listOf("Aria owes 10 gold"), 35, "They haggled.").hashCode())
        assertTrue(memory.toString().contains("relationship=35"))
    }

    @Test
    fun memoryNoteRendering() {
        assertNull(NPCMemory().note(includeRelationship = false))
        assertEquals(
            "Memory:\n- You feel neutral toward the player (0 on a scale from -100 to 100).",
            NPCMemory().note(includeRelationship = true),
        )
        val note = NPCMemory(listOf("Aria owes 10 gold"), 35, "They haggled over a sword.").note(includeRelationship = false)
        assertEquals(
            """
            Memory:
            - You feel friendly toward the player (35 on a scale from -100 to 100).
            - You remember: Aria owes 10 gold.
            - Earlier conversation: They haggled over a sword.
            """.trimIndent(),
            note,
        )
    }

    // MARK: Options

    @Test
    fun optionsDecodeWithDefaults() {
        val options = GameJson.decodeFromString(
            NPCOptions.serializer(),
            """{"groundingTool":"check_inventory","playerOptionCount":0,"memoryTools":3,"maxFacts":null}""",
        )
        assertEquals("check_inventory", options.groundingTool)
        assertEquals(0, options.playerOptionCount)
        assertEquals(NPCMemoryTool.ALL, options.memoryTools)
        assertEquals(NPCOptions().compactAfterTurns, options.compactAfterTurns)
        assertEquals(12, options.maxFacts)
        assertTrue(options.fallbackOnGuardrail)
        assertEquals(50, options.secretsUnlockAtRelationship)
        assertEquals(ToolChoice.Explicit, options.toolChoice)
        assertEquals(NPCReplyFormat.AUTOMATIC, options.replyFormat)

        val guarded = GameJson.decodeFromString(NPCOptions.serializer(), """{"secretsUnlockAtRelationship":null}""")
        assertNull(guarded.secretsUnlockAtRelationship)
        assertEquals(guarded, GameJson.decodeFromString(NPCOptions.serializer(), GameJson.encodeToString(NPCOptions.serializer(), guarded)))
        assertEquals(options, GameJson.decodeFromString(NPCOptions.serializer(), GameJson.encodeToString(NPCOptions.serializer(), options)))

        val wire = GameJson.encodeToJsonElement(
            NPCOptions.serializer(),
            NPCOptions(toolChoice = ToolChoice.Tool("check_inventory"), memoryTools = setOf(NPCMemoryTool.CHANGE_RELATIONSHIP), replyFormat = NPCReplyFormat.TEXT, maxResponseTokens = 90),
        ).objectValue!!
        assertEquals(jsonObjectOf("tool" to "check_inventory"), wire["toolChoice"])
        assertEquals(JsonArray(listOf(JsonPrimitive("changeRelationship"))), wire["memoryTools"])
        assertEquals(JsonPrimitive("text"), wire["replyFormat"])
        assertEquals(JsonPrimitive(90), wire["maximumResponseTokens"])
        assertEquals(JsonPrimitive(48), wire["barkMaximumTokens"])
        assertEquals(JsonNull, wire["groundingTool"])
        assertEquals("neutral", wire["emotions"]?.arrayValue?.first()?.stringValue)
    }

    @Test
    fun memoryToolsAndToolChoiceDecodeLeniently() {
        fun tools(json: String) = GameJson.decodeFromString(NPCOptions.serializer(), """{"memoryTools":$json}""").memoryTools
        assertEquals(NPCMemoryTool.ALL, tools("\"all\""))
        assertEquals(emptySet(), tools("\"none\""))
        assertEquals(emptySet(), tools("null"))
        assertEquals(setOf(NPCMemoryTool.REMEMBER_FACT), tools("1"))
        assertEquals(setOf(NPCMemoryTool.CHANGE_RELATIONSHIP), tools("\"change_relationship\""))
        assertEquals(NPCMemoryTool.ALL, tools("""["rememberFact", "CHANGE_RELATIONSHIP"]"""))
        assertFailsWith<Exception> { tools("""["teleport"]""") }
        assertFailsWith<Exception> { tools("{}") }

        fun choice(json: String) = GameJson.decodeFromString(NPCOptions.serializer(), """{"toolChoice":$json}""").toolChoice
        assertEquals(ToolChoice.Auto, choice("\"auto\""))
        assertEquals(ToolChoice.Required, choice("\"required\""))
        assertEquals(ToolChoice.Tool("x"), choice("""{"type":"function","function":{"name":"x"}}"""))
        assertFailsWith<Exception> { choice("\"sometimes\"") }
    }

    // MARK: Turns and saves

    @Test
    fun dialogueTurnSerialization() {
        val turn = DialogueTurn(
            line = "Aye.",
            emotion = Emotion.AMUSED,
            playerOptions = listOf("Bye."),
            endsConversation = true,
            toolCalls = listOf(ToolRecord(ToolCall("c1", "t"), ToolOutput.Text("ok"), 0.1)),
            relationship = 5,
            isFallback = false,
            usage = TokenUsage(inputTokens = 10, outputTokens = 3),
        )
        val json = GameJson.encodeToJsonElement(DialogueTurn.serializer(), turn).objectValue!!
        assertEquals(
            listOf("line", "emotion", "playerOptions", "endsConversation", "toolCalls", "relationship", "isFallback", "usage"),
            json.keys.toList(),
        )
        assertEquals(JsonPrimitive("amused"), json["emotion"])
        assertEquals(turn, GameJson.decodeFromJsonElement(DialogueTurn.serializer(), json))
    }

    @Test
    fun saveStateRoundTripAndErrors() {
        val state = NPCSaveState(
            persona = Fixtures.gorm,
            memory = NPCMemory(listOf("x"), 20),
            transcript = Transcript(entries = listOf(TranscriptEntry.Prompt("hi"), TranscriptEntry.Response("hello"))),
        )
        val restored = NPCSaveState.fromJsonString(state.toJsonString())
        assertEquals(state, restored)
        assertEquals(1, restored.version)
        val partial = NPCSaveState.fromJsonString("""{"persona":{"name":"Mira"}}""")
        assertEquals(NPCMemory(), partial.memory)
        assertTrue(partial.transcript.entries.isEmpty())
        for (bad in listOf("[]", "{}", """{"persona":{"name":" "}}""", "{nope")) {
            val error = assertFailsWith<AgentError> { NPCSaveState.fromJsonString(bad) }
            assertEquals(AgentErrorCode.INVALID_REQUEST, error.code, bad)
        }
    }

    // MARK: NPC prompting

    @Test
    fun replySchemaOrderAndShape() {
        val schema = NPCPrompting.replySchema(Fixtures.gorm, NPCOptions(emotions = listOf(Emotion.HAPPY, Emotion.ANGRY, Emotion.HAPPY), playerOptionCount = 2))
        assertEquals(listOf("emotion", "line", "player_options", "ends_conversation"), schema.propertyNames)
        val properties = schema.json["properties"]!!.objectValue!!
        assertEquals(JsonArray(listOf(JsonPrimitive("happy"), JsonPrimitive("angry"))), properties["emotion"]!!.objectValue!!["enum"])
        assertTrue(properties["player_options"]!!.objectValue!!["description"]!!.stringValue!!.startsWith("2 short, different replies"))
        // Counts are described, not enforced: two suggestions instead of three still validate.
        assertTrue(schema.accepts(jsonObjectOf("emotion" to "happy", "line" to "Hi.", "player_options" to listOf("a"), "ends_conversation" to false)))
        val fields = schema.renderFields()
        assertTrue(fields.contains("- \"emotion\": one of \"happy\", \"angry\""), fields)
        val minimal = NPCPrompting.replySchema(Fixtures.gorm, NPCOptions(playerOptionCount = 0, canEndConversation = false))
        assertEquals(listOf("emotion", "line"), minimal.propertyNames)
        val clamped = NPCPrompting.replySchema(Fixtures.gorm, NPCOptions(playerOptionCount = 9))
        assertTrue(clamped.json["properties"]!!.objectValue!!["player_options"]!!.objectValue!!["description"]!!.stringValue!!.startsWith("4 short"))
    }

    @Test
    fun replyParsingIsDefensive() {
        val reply = NPCPrompting.parseReply(
            jsonObjectOf(
                "emotion" to "MAD",
                "line" to "Gorm: \"Get out, lad.\"",
                "player_options" to listOf("1. Sorry!", "- Sorry!", "\"Make me.\"", 7, "", "Player: Fine."),
                "ends_conversation" to true,
            ),
            Fixtures.gorm,
            NPCOptions(canEndConversation = false),
        )
        assertEquals(Emotion.ANGRY, reply.emotion)
        assertEquals("Get out, lad.", reply.line)
        assertEquals(listOf("Sorry!", "Make me.", "Fine."), reply.playerOptions)
        assertFalse(reply.endsConversation)
        assertEquals(NPCPrompting.Reply("", Emotion.NEUTRAL, emptyList(), false), NPCPrompting.parseReply(null, Fixtures.gorm, NPCOptions()))
        val ends = NPCPrompting.parseReply(jsonObjectOf("line" to "Farewell.", "ends_conversation" to true), Fixtures.gorm, NPCOptions(playerOptionCount = 1))
        assertTrue(ends.endsConversation)
    }

    @Test
    fun promptsAndContextNote() {
        assertEquals("Hello", NPCPrompting.prompt(" Hello "))
        assertEquals("(The player says nothing.)", NPCPrompting.prompt("  "))
        assertEquals("Situation: It is night.\nPlayer: (The player says nothing.)", NPCPrompting.barkPrompt("It is night.", null))
        assertEquals(
            "Game state:\nplayer.gold: 3\nSituation: An ordinary moment.\nPlayer: (The player says nothing.)",
            NPCPrompting.barkPrompt(" ", "player.gold: 3"),
        )
        assertNull(NPCPrompting.contextNote(NPCMemory(), NPCOptions(), null, null))
        val record = ToolRecord(ToolCall(name = "check_inventory", arguments = jsonObjectOf("item" to "sword")), ToolOutput.of(mapOf("stock" to 3)), 0.0)
        val failed = ToolRecord(ToolCall(name = "open_gate"), ToolOutput.Error("stuck"), 0.0)
        assertEquals(
            """
            Memory:
            - You feel neutral toward the player (0 on a scale from -100 to 100).
            Game state:
            player.gold: 3
            Situation: The forge is hot.
            Facts you just looked up:
            - check_inventory {"item":"sword"} gave: {"stock":3}
            """.trimIndent(),
            NPCPrompting.contextNote(NPCMemory(), NPCOptions(memoryTools = NPCMemoryTool.ALL), "player.gold: 3", "The forge is hot.", listOf(record, failed)),
        )
    }

    @Test
    fun emotionTagParsing() {
        assertEquals(NPCPrompting.TagSplit("angry", "Get out!", false), NPCPrompting.splitEmotionTag("[angry] Get out!"))
        assertEquals(NPCPrompting.TagSplit(null, "", true), NPCPrompting.splitEmotionTag("  [gru"))
        assertEquals(NPCPrompting.TagSplit(null, "No tag here.", false), NPCPrompting.splitEmotionTag("No tag here."))
        val long = "[this is a very long bracketed aside without end"
        assertEquals(NPCPrompting.TagSplit(null, long, false), NPCPrompting.splitEmotionTag(long))
        assertEquals(NPCPrompting.Reply("Aye, lad.", Emotion.ANNOYED, emptyList(), false), NPCPrompting.parseTextReply("[gruff] Gorm: \"Aye, lad.\"", Fixtures.gorm))
        assertEquals(Fixtures.gorm.defaultEmotion, NPCPrompting.parseTextReply("[sighs] Fine.", Fixtures.gorm).emotion)
        assertNull(NPCPrompting.partialTextReply("[hap"))
        assertEquals(jsonObjectOf("emotion" to "happy", "line" to "Hel"), NPCPrompting.partialTextReply("[happy] Hel"))
        assertEquals(jsonObjectOf("line" to "Plain"), NPCPrompting.partialTextReply("Plain"))
    }

    @Test
    fun streamingLineTracker() {
        val tracker = LineTracker("Gorm")
        val events = ArrayList<String>()
        val record: (DialogueEvent) -> Unit = { event ->
            events += when (event) {
                is DialogueEvent.Emotion -> "emotion:${event.emotion.wireName}"
                is DialogueEvent.LineDelta -> "+${event.text}"
                is DialogueEvent.LineReset -> "=${event.text}"
                else -> "?"
            }
        }
        tracker.consume(jsonObjectOf("emotion" to "hap"), record)
        tracker.consume(jsonObjectOf("emotion" to "happy", "line" to "Go"), record) // could become "Gorm:"
        tracker.consume(jsonObjectOf("emotion" to "happy", "line" to "Gorm: Aye"), record)
        tracker.consume(jsonObjectOf("emotion" to "happy", "line" to "Gorm: Aye, lad"), record)
        tracker.finish("Aye, lad.", Emotion.HAPPY, record)
        assertEquals(listOf("emotion:happy", "+Aye", "+, lad", "+."), events)
        tracker.finish("Something else.", Emotion.SAD, record)
        assertEquals(listOf("emotion:sad", "=Something else."), events.takeLast(2))
        tracker.consume(JsonArray(emptyList()), record)
        assertEquals(6, events.size)
    }

    @Test
    fun textCleanup() {
        assertEquals("Hello.", TextCleanup.spokenLine("  **Gorm**: “Hello.” ", "Gorm"))
        assertEquals("He said \"no\" twice", TextCleanup.spokenLine("\"He said \"no\" twice\"", "Gorm").removeSurrounding("\""))
        assertEquals("'Tis fine", TextCleanup.spokenLine("'Tis fine", "Gorm"))
        assertEquals("Fine steel!", TextCleanup.singleLine("\n\nGorm says: Fine steel!\nMore.", "Gorm"))
        assertNull(TextCleanup.streamingLine("Gor", "Gorm"))
        assertEquals("Hi", TextCleanup.streamingLine("\"Hi", "Gorm"))
        assertEquals("a; b.", TextCleanup.list(listOf(" a. ", "", "b")))
        assertNull(TextCleanup.list(listOf(" ")))
    }

    @Test
    fun completeTurnsDropsAnUnansweredTurn() {
        val entries = listOf(TranscriptEntry.Prompt("hi"), TranscriptEntry.Response("hello"), TranscriptEntry.Prompt("again"))
        assertEquals(2, NPCPrompting.completeTurns(Transcript(entries = entries)).entries.size)
        val complete = Transcript(entries = entries.take(2))
        assertSame(complete, NPCPrompting.completeTurns(complete))
        assertEquals(0, NPCPrompting.completeTurns(Transcript(entries = listOf(TranscriptEntry.Prompt("x")))).entries.size)
    }
}
