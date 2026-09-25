package com.spacecorps.oam.readme

import com.spacecorps.oam.*
import com.spacecorps.oam.game.*
import com.spacecorps.oam.testing.ScriptedLanguageModel
import com.spacecorps.oam.testing.ScriptedLanguageModel.Step
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The README's Kotlin examples, unchanged, run against scripted models.
 *
 * Each test first sets up what its example assumes (`model`, and `inventory` or
 * `gorm` from the first example), then runs the example as the README shows it,
 * then checks what it did. [readmeExamplesAreTheTestedCopies] fails when the
 * README and these copies differ, so change both together. The Android examples
 * are compiled, not run, in oam-mlkit's `ReadmeAndroidExamples.kt`.
 */
class ReadmeExamplesTest {
    /** Stands in for the game in "External tools: let your game act". */
    private class Game {
        val openedGates = ArrayList<String>()

        fun openGate(gate: String): Boolean {
            openedGates += gate
            return gate == "north"
        }
    }

    /** Stands in for a dialogue UI in the NPC example. */
    private class DialogueBox {
        var text = ""
            private set

        fun append(delta: String) {
            text += delta
        }

        fun replace(line: String) {
            text = line
        }
    }

    /** The first example's tool, for the examples that reuse it. */
    private fun inventoryTool(): AgentTool = AgentTool.local(
        name = "check_inventory",
        description = "Look up how many of an item the blacksmith has and its price in gold.",
        parameters = JsonSchema.obj("item" to JsonSchema.string(description = "Item name")),
    ) { call -> ToolOutput.of(mapOf("item" to call.string("item"), "stock" to 3, "price_gold" to 45)) }

    /** A native-style script: a tool round is one step, whether the agent decides or writes arguments. */
    private fun script(vararg steps: Step) = ScriptedLanguageModel(steps.toList(), style = ScriptedLanguageModel.ScriptStyle.NATIVE)

    private fun lookup(item: String) = Step.ToolCalls(Step.ScriptedCall("check_inventory", jsonObjectOf("item" to item)))

    @Test
    fun anAgentWithTools() = runBlocking<Unit> {
        val model = script(lookup("iron sword"), Step.Text("Three, lad. 45 gold each."))

        val inventory = AgentTool.local(
            name = "check_inventory",
            description = "Look up how many of an item the blacksmith has and its price in gold.",
            parameters = JsonSchema.obj("item" to JsonSchema.string(description = "Item name")),
        ) { call ->
            val item = call.string("item")
            ToolOutput.of(mapOf("item" to item, "stock" to 3, "price_gold" to 45))
        }

        val gorm = Agent(
            model = model,
            instructions = "You are Gorm, a grumpy blacksmith in a fantasy game. Reply in at most two sentences.",
            tools = listOf(inventory),
        )

        // Ground the answer: the first step must call a tool, then the model replies.
        val reply = gorm.respond("Got any iron swords? How much?", ToolPolicy(choice = ToolChoice.Required))
        println(reply.text)
        println(reply.toolCalls.map { it.call.name })  // [check_inventory]

        assertEquals("Three, lad. 45 gold each.", reply.text)
        assertEquals(listOf("check_inventory"), reply.toolCalls.map { it.call.name })
        assertEquals("""{"item":"iron sword","stock":3,"price_gold":45}""", reply.toolCalls.single().output.modelText)
        // Required with a single tool: its arguments, a decision to reply, then the reply.
        assertEquals(listOf(StepKind.TOOL_ARGUMENTS, StepKind.DECIDE, StepKind.RESPOND), reply.steps.map { it.kind })
        gorm.close()
    }

    @Test
    fun externalToolsLetYourGameAct() = runBlocking<Unit> {
        val model = ScriptedLanguageModel(Step.call("open_gate", "gate" to "north"), Step.respond(), Step.Text("The north gate is open."))
        val game = Game()

        val openGate = AgentTool.external(
            name = "open_gate",
            description = "Ask the game to open a named gate. Returns whether it opened.",
            parameters = JsonSchema.obj("gate" to JsonSchema.string()),
        )
        val guard = Agent(model, instructions = "You are a castle guard. Use tools to act.", tools = listOf(openGate))

        val run = guard.run("Please open the north gate.")
        run.events.collect { event ->
            when (event) {
                is AgentEvent.ToolCallRequested -> {
                    val opened = game.openGate(event.call.string("gate"))  // your game code
                    run.submit(ToolOutput.of(mapOf("opened" to opened)), event.call.id)
                }
                is AgentEvent.Text -> print(event.delta)  // stream the reply
                is AgentEvent.Completed -> println("\n${event.response.toolCalls.size} tool call(s)")
                else -> Unit
            }
        }

        assertEquals(listOf("north"), game.openedGates)
        assertTrue(run.isFinished)
        val history = guard.history
        assertEquals("""{"opened":true}""", history.filterIsInstance<TranscriptEntry.ToolUse>().single().output.modelText)
        assertEquals("The north gate is open.", history.filterIsInstance<TranscriptEntry.Response>().single().text)
        guard.close()
    }

    @Test
    fun structuredOutput() = runBlocking<Unit> {
        val model = script(lookup("iron sword"), Step.Json(jsonObjectOf("choice" to "haggle", "reasoning" to "Thirty is too low for good iron.")))
        val gorm = Agent(model = model, instructions = "You are Gorm, a grumpy blacksmith.", tools = listOf(inventoryTool()))

        val decision = gorm.respond(
            "A customer offers 30 gold for an iron sword. Check stock, then decide.",
            schema = JsonSchema.obj(
                "reasoning" to JsonSchema.string(description = "One short sentence"),
                "choice" to JsonSchema.string(enum = listOf("sell", "refuse", "haggle")),
            ),
            policy = ToolPolicy(choice = ToolChoice.Tool("check_inventory")),
        )
        println(decision.structured)  // {"reasoning":"…","choice":"haggle"}: validated, keys in schema order

        val structured = decision.structured!!.jsonObject
        assertEquals(listOf("reasoning", "choice"), structured.keys.toList())
        assertEquals(jsonObjectOf("reasoning" to "Thirty is too low for good iron.", "choice" to "haggle"), structured)
        assertEquals(listOf("check_inventory"), decision.toolCalls.map { it.call.name })
        gorm.close()
    }

    @Test
    fun npcsDecisionsAndContent() = runBlocking<Unit> {
        fun reply(emotion: String, line: String) =
            Step.Json(jsonObjectOf("emotion" to emotion, "line" to line, "player_options" to listOf("I'll take one.", "Too dear."), "ends_conversation" to false))
        val model = script(
            lookup("iron sword"),
            reply("proud", "Three left, lad. 45 gold, and worth every coin."),
            lookup("shield"),
            reply("neutral", "Not with 60 gold and a sword, you can't."),
            Step.Json(jsonObjectOf("reasoning" to "Snik is outmatched.", "choice" to "flee", "confidence" to 90)),
            Step.Json(jsonObjectOf("name" to "Ring of Whispers", "curse" to "Its wearer hears footsteps that aren't there.", "value_gold" to 120)),
        )
        val inventory = inventoryTool()
        val dialogueBox = DialogueBox()

        val world = WorldState.of(
            "player" to mapOf("name" to "Aria", "gold" to 60),
            "time_of_day" to "evening",
        )

        val blacksmith = NPC(
            persona = Persona(
                name = "Gorm",
                role = "the village blacksmith",
                personality = "Gruff and proud, but fair.",
                speakingStyle = "Short, blunt sentences. Calls people 'lad'.",
            ),
            model = model,
            tools = listOf(inventory),
            world = world,                              // adds read_world_state
            options = NPCOptions(
                groundingTool = "check_inventory",      // look up stock before every reply
                worldContextPaths = listOf("player.name", "player.gold", "time_of_day"),
            ),
        )

        val turn = blacksmith.talk("Evening! Got any iron swords? How much?")
        println("${turn.emotion}: ${turn.line}")  // an Emotion (such as PROUD) and the spoken line
        println(turn.playerOptions)               // suggested replies
        println(turn.isFallback)                  // true if a canned line replaced an unusable reply

        // Typewriter streaming
        blacksmith.talkStream("Can I afford a shield too?").events.collect { event ->
            when (event) {
                is DialogueEvent.LineDelta -> dialogueBox.append(event.text)
                is DialogueEvent.LineReset -> dialogueBox.replace(event.text)
                else -> Unit
            }
        }

        // Enemy AI: always one of your option ids
        val choice = DecisionEngine(model).decide(
            situation = "The player, at full health, charges Snik with a flaming sword.",
            options = listOf(
                DecisionOption("flee", "Run into the tunnels"),
                DecisionOption("attack", "Stab with the rusty dagger"),
                DecisionOption("beg", "Beg for mercy and offer loot"),
            ),
            actor = Persona(name = "Snik", role = "a timid, greedy goblin"),
            fallbackOptionId = "flee",  // used if the answer is blocked or stays invalid
        )
        println(choice.optionId)

        // Schema-shaped content
        val ring = ContentGenerator(model).generate(
            prompt = "A cursed ring for a level 3 player.",
            schema = JsonSchema.obj(
                "name" to JsonSchema.string(),
                "curse" to JsonSchema.string(description = "One sentence"),
                "value_gold" to JsonSchema.integer(minimum = 1, maximum = 500),
            ),
        )

        assertEquals(Emotion.PROUD, turn.emotion)
        assertEquals("Three left, lad. 45 gold, and worth every coin.", turn.line)
        assertEquals(listOf("I'll take one.", "Too dear."), turn.playerOptions)
        assertFalse(turn.isFallback)
        assertEquals(listOf("check_inventory"), turn.toolCalls.map { it.call.name })
        assertEquals("Not with 60 gold and a sword, you can't.", dialogueBox.text)
        assertEquals(2, blacksmith.turnCount)
        assertTrue("Aria" in model.requests.first().systemInstruction.orEmpty(), "worldContextPaths are summarized into the turn")
        assertEquals("flee", choice.optionId)
        assertFalse(choice.isFallback)
        assertEquals(listOf("name", "curse", "value_gold"), ring.jsonObject.keys.toList())
        assertEquals(0, model.remainingSteps)
        blacksmith.close()
    }

    @Test
    fun scriptedModelsInYourOwnTests() = runBlocking<Unit> {
        val inventory = inventoryTool()

        val model = ScriptedLanguageModel(
            Step.call("check_inventory", "item" to "iron sword"),  // decide: call the tool
            Step.respond(),                                        // decide: reply
            Step.Text("Three swords, 45 gold each."),              // the reply
        )
        val agent = Agent(model, tools = listOf(inventory))
        val response = agent.respond("Swords?")
        check(response.toolCalls.single().call.name == "check_inventory")
        check(model.requests.map { it.kind } == listOf(GenerationKind.DECIDE, GenerationKind.DECIDE, GenerationKind.RESPOND))

        assertEquals("Three swords, 45 gold each.", response.text)
        agent.close()
    }

    // MARK: README in step with the tested copies

    /** The repository root (Gradle runs tests in the module's directory). */
    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    }

    /** The files that hold the tested copies (see oam-game's build script, which declares them as inputs). */
    private val exampleSources = listOf(
        "oam-game/src/test/kotlin/com/spacecorps/oam/readme/ReadmeExamplesTest.kt",
        "oam-mlkit/src/test/kotlin/com/spacecorps/oam/readme/ReadmeAndroidExamples.kt",
    )

    private val oamJniSource = "oam-mlkit/src/main/kotlin/com/spacecorps/oam/jni/OamJni.kt"

    /**
     * Every ```kotlin block in README.md is a tested copy: its lines appear, in
     * order and up to a uniform indentation, in one of [exampleSources], and its
     * imports are imports of that file. Build-script blocks (starting with a
     * `// ….gradle.kts` comment) are not Kotlin examples. The `OamJni` outline
     * must list declarations of `OamJni.kt`, and the C declaration must name its
     * JNI symbol.
     */
    @Test
    fun readmeExamplesAreTheTestedCopies() {
        val readme = File(root, "README.md").readText()
        val blocks = Regex("^```kotlin\\n(.*?)^```", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
            .findAll(readme).map { it.groupValues[1].trimEnd().lines() }.toList()
        assertTrue(blocks.size >= 8, "expected the README's Kotlin examples, found ${blocks.size} blocks")
        val sources = exampleSources.associateWith { File(root, it).readLines() }
        val oamJni = File(root, oamJniSource).readText().replace(Regex("\\s+"), " ")
        var examples = 0
        for (block in blocks) {
            val first = block.first { it.isNotBlank() }.trim()
            when {
                Regex("^// \\S+\\.gradle\\.kts").containsMatchIn(first) -> continue
                block.any { it.trim() == "object OamJni {" } -> checkOutline(block, oamJni)
                else -> {
                    val imports = block.filter { it.startsWith("import ") }
                    val body = block.filterNot { it.startsWith("import ") }.dropWhile { it.isBlank() }
                    val home = sources.entries.firstOrNull { (_, lines) -> lines.containsBlock(body) }
                    checkNotNull(home) { "This README example has no tested copy in ${exampleSources.joinToString()}:\n${block.joinToString("\n")}" }
                    val missing = imports.filter { it !in home.value }
                    assertTrue(missing.isEmpty(), "${home.key} lacks the README example's imports $missing")
                    examples++
                }
            }
        }
        assertTrue(examples >= 6, "expected at least six tested README examples, found $examples")

        val jniPackage = Regex("^package (\\S+)", RegexOption.MULTILINE).find(File(root, oamJniSource).readText())!!.groupValues[1]
        val symbol = "Java_${jniPackage.replace('.', '_')}_OamJni_nativeDeliver"
        val cBlock = Regex("```c\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).find(readme)?.groupValues?.get(1).orEmpty()
        assertTrue(symbol in cBlock, "the README's C declaration must name $symbol")
    }

    /** Each `fun`/`var` line of the outline, minus `@JvmStatic` and its comment, is a public declaration of OamJni. */
    private fun checkOutline(block: List<String>, oamJni: String) {
        val declarations = block.map { it.substringBefore("//").trim().removePrefix("@JvmStatic").trim() }
            .filter { it.contains("fun ") || it.startsWith("var ") }
        assertTrue(declarations.size >= 5, "the OamJni outline lists too few members: $declarations")
        for (declaration in declarations) {
            assertTrue("public $declaration" in oamJni, "OamJni.kt has no 'public $declaration' (README outline)")
        }
    }

    /** Whether [block] appears here line for line, shifted right by the same number of spaces throughout. */
    private fun List<String>.containsBlock(block: List<String>): Boolean {
        val lines = block.dropLastWhile { it.isBlank() }
        if (lines.isEmpty()) return true
        val indentOf = { line: String -> line.length - line.trimStart().length }
        for (start in 0..size - lines.size) {
            val shift = indentOf(this[start]) - indentOf(lines[0])
            if (shift < 0 || this[start].trim() != lines[0].trim()) continue
            val pad = " ".repeat(shift)
            val matches = lines.indices.all { i ->
                val expected = lines[i].trimEnd()
                val actual = this[start + i].trimEnd()
                if (expected.isEmpty()) actual.isEmpty() else actual == pad + expected
            }
            if (matches) return true
        }
        return false
    }
}
