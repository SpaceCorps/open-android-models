package com.spacecorps.oam.game

import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentTool
import com.spacecorps.oam.GenerationChunk
import com.spacecorps.oam.GenerationKind
import com.spacecorps.oam.GenerationRequest
import com.spacecorps.oam.JsonSchema
import com.spacecorps.oam.LanguageModel
import com.spacecorps.oam.ToolChoice
import com.spacecorps.oam.ToolOutput
import com.spacecorps.oam.jsonObjectOf
import com.spacecorps.oam.openai.OpenAICompatibleModel
import com.spacecorps.oam.toJsonString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live evaluation of the game layer against a small on-device-class model
 * behind an OpenAI-compatible server (plain chat completions, no native
 * tools): the closest desktop stand-in for Gemini Nano. Opt-in:
 *
 * ```
 * (cd open-apple-models && swift build -c release --product oam && .build/release/oam serve --port 19997) &
 * OAM_PROXY_EVAL=1 ./gradlew :oam-game:test --tests '*GameProxyEvalTest*'
 * ```
 *
 * `OAM_PROXY_URL` (default `http://127.0.0.1:19997/v1`), `OAM_PROXY_MODEL`
 * (default `system`), `OAM_PROXY_EVAL_RUNS` (default 1) and
 * `OAM_PROXY_EVAL_ONLY` (comma-separated scenarios: shopkeeper, innkeeper,
 * text, hostile, decisions, content) select the server, repetitions and scenarios. The report is printed and written to
 * `oam-game/build/game-proxy-eval.txt`. Checks are heuristics over live
 * output, so the test only fails when the server is unreachable.
 */
@EnabledIfEnvironmentVariable(named = "OAM_PROXY_EVAL", matches = "1")
class GameProxyEvalTest {
    private val baseUrl = System.getenv("OAM_PROXY_URL") ?: "http://127.0.0.1:19997/v1"
    private val modelName = System.getenv("OAM_PROXY_MODEL") ?: "system"
    private val runs = System.getenv("OAM_PROXY_EVAL_RUNS")?.toIntOrNull()?.coerceIn(1, 10) ?: 1
    private val only = System.getenv("OAM_PROXY_EVAL_ONLY")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    private suspend fun scenario(name: String, body: suspend () -> Unit) {
        if (only.isEmpty() || name in only) body()
    }

    /** A number as digits or words ("45" or "forty-five"). */
    private fun mentions(line: String, number: Int, words: String): Boolean =
        line.contains(number.toString()) || line.lowercase().replace('-', ' ').contains(words)

    /** Records every model call's kind, output and latency. */
    private class RecordingModel(private val base: LanguageModel) : LanguageModel by base {
        data class Call(val request: GenerationRequest, val output: String, val millis: Long, val error: String?) {
            /** The output, or the error code when the step failed. */
            val shown: String get() = error?.let { "ERROR $it" } ?: output.replace("\n", "⏎")
        }

        val calls: MutableList<Call> = Collections.synchronizedList(ArrayList())

        override fun generate(request: GenerationRequest): Flow<GenerationChunk> {
            val started = System.nanoTime()
            var text = ""
            return base.generate(request)
                .onEach { text = it.text }
                .onCompletion { cause ->
                    calls += Call(request, text, (System.nanoTime() - started) / 1_000_000, (cause as? AgentError)?.code?.wireName)
                }
        }
    }

    private val report = StringBuilder()

    private fun log(line: String = "") {
        println(line)
        report.appendLine(line)
    }

    private class Tally {
        var checks = 0
        var passed = 0
        var turns = 0
        var fallbacks = 0
        var textRetries = 0
        var structuredSteps = 0
        var structuredFirstTry = 0
        val turnMillis = ArrayList<Long>()

        fun check(ok: Boolean): String {
            checks++
            if (ok) passed++
            return if (ok) "OK" else "MISS"
        }
    }

    private val tally = Tally()

    private val stock = mapOf(
        "iron sword" to mapOf("in_stock" to 3, "price_gold" to 45),
        "steel shield" to mapOf("in_stock" to 1, "price_gold" to 80),
        "axe" to mapOf("in_stock" to 2, "price_gold" to 30),
    )

    private fun inventory(): AgentTool = AgentTool.local(
        "check_inventory",
        "Look up whether the forge has an item in stock, how many, and its price in gold.",
        JsonSchema.obj("item" to JsonSchema.string(description = "Item name, e.g. 'iron sword'")),
    ) { call ->
        val item = call.string("item").lowercase().trim()
        val match = stock.entries.firstOrNull { (name, _) -> item.contains(name) || name.contains(item) }
        if (match == null) ToolOutput.of(mapOf("item" to item, "in_stock" to 0)) else ToolOutput.of(mapOf("item" to match.key) + match.value)
    }

    private val gorm = Persona(
        name = "Gorm",
        role = "the village blacksmith",
        personality = "Gruff and proud, but fair. Secretly soft-hearted.",
        speakingStyle = "Short, blunt sentences. Calls people 'lad' or 'lass'.",
        goals = listOf("Sell his weapons at a fair price"),
    )

    /** Runs one turn, logs it, and returns it (or null on error). */
    private suspend fun turn(
        npc: NPC,
        model: RecordingModel,
        line: String,
        toolChoice: ToolChoice? = null,
        stream: Boolean = false,
        check: (DialogueTurn) -> Boolean,
    ): DialogueTurn? {
        val before = model.calls.size
        val started = System.nanoTime()
        var firstText = -1L
        var deltas = 0
        val result = try {
            if (stream) {
                var completed: DialogueTurn? = null
                npc.talkStream(line, toolChoice = toolChoice).events.collect { event ->
                    when (event) {
                        is DialogueEvent.LineDelta, is DialogueEvent.LineReset -> {
                            deltas++
                            if (firstText < 0) firstText = (System.nanoTime() - started) / 1_000_000
                        }
                        is DialogueEvent.Completed -> completed = event.turn
                        else -> Unit
                    }
                }
                completed
            } else {
                npc.talk(line, toolChoice = toolChoice)
            }
        } catch (error: AgentError) {
            log("PLAYER: $line\n  ERROR ${error.code.wireName}: ${error.message}")
            tally.check(false)
            return null
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        val calls = model.calls.subList(before, model.calls.size).toList()
        val turn = result ?: return null
        tally.turns++
        tally.turnMillis += millis
        if (turn.isFallback) tally.fallbacks++
        val structured = calls.filter { it.request.kind == GenerationKind.STRUCTURED }
        tally.structuredSteps += structured.size
        if (structured.isNotEmpty() && structured.none { it.request.prompt.contains("Your previous answer was invalid") } && calls.none { it.request.kind == GenerationKind.RESPOND }) {
            tally.structuredFirstTry++
        }
        val retried = structured.isNotEmpty() && calls.any { it.request.kind == GenerationKind.RESPOND }
        if (retried) tally.textRetries++
        log("PLAYER: $line${toolChoice?.let { "   (toolChoice=${it.toJson().toJsonString()})" }.orEmpty()}")
        for (call in calls) log("  [${call.request.kind.name.lowercase()} ${call.millis}ms] ${call.shown.take(240)}")
        val streaming = if (stream) " firstText=${firstText}ms deltas=$deltas" else ""
        log("  tools=${turn.toolCalls.joinToString { it.call.name + it.call.arguments.toJsonString() }} retriedAsText=$retried fallback=${turn.isFallback}$streaming")
        log("  GORM-ish (${millis}ms) [${turn.emotion}] ${turn.line}  options=${turn.playerOptions} ends=${turn.endsConversation} rel=${turn.relationship}")
        log("  => ${tally.check(check(turn))}")
        return turn
    }

    @Test
    fun gameLayerAgainstALiveModel() = runBlocking {
        val base = OpenAICompatibleModel(baseUrl, modelName)
        val available = base.availability()
        assertTrue(available.isAvailable, "Proxy server not available at $baseUrl: $available")
        log("== game layer eval, model=$modelName url=$baseUrl runs=$runs")

        repeat(runs) { run ->
            log()
            log("#### run ${run + 1}")
            scenario("shopkeeper") { shopkeeper() }
            scenario("innkeeper") { innkeeper() }
            scenario("text") { textFormat() }
            scenario("hostile") { hostileLines() }
            scenario("decisions") { decisions() }
            scenario("content") { barksAndContent() }
        }

        fun avg(values: List<Long>) = if (values.isEmpty()) 0 else values.sum() / values.size
        fun p90(values: List<Long>) = values.sorted().let { if (it.isEmpty()) 0 else it[((it.size - 1) * 9) / 10] }
        log()
        log("SUMMARY")
        log("  checks passed=${tally.passed}/${tally.checks}")
        log("  NPC turns=${tally.turns} fallbacks=${tally.fallbacks} textRetries=${tally.textRetries} structuredFirstTry=${tally.structuredFirstTry} (of turns with a structured step)")
        log("  turn latency avg=${avg(tally.turnMillis)}ms p90=${p90(tally.turnMillis)}ms")
        File("build/game-proxy-eval.txt").apply { parentFile.mkdirs() }.writeText(report.toString())
    }

    /** GAMES.md's quick start: a grounded shopkeeper with world context and a relationship tool. */
    private suspend fun shopkeeper() {
        log()
        log("== Gorm, grounded shopkeeper (groundingTool=check_inventory, worldContextPaths, changeRelationship)")
        val model = RecordingModel(OpenAICompatibleModel(baseUrl, modelName))
        val world = WorldState.of("player" to mapOf("name" to "Aria", "gold" to 60), "time_of_day" to "evening")
        NPC(
            gorm, model, tools = listOf(inventory()), world = world,
            options = NPCOptions(
                groundingTool = "check_inventory",
                worldContextPaths = listOf("player.name", "player.gold", "time_of_day"),
                memoryTools = setOf(NPCMemoryTool.CHANGE_RELATIONSHIP),
            ),
        ).use { npc ->
            turn(npc, model, "Evening! Got any iron swords? How much?", stream = true) { turn ->
                turn.toolCalls.any { it.call.name == "check_inventory" } && mentions(turn.line, 45, "forty five")
            }
            turn(npc, model, "Can I afford a steel shield as well?") { turn ->
                turn.toolCalls.any { it.call.arguments.toJsonString().contains("shield") } && mentions(turn.line, 80, "eighty")
            }
            turn(npc, model, "Just the sword then. Thank you, Gorm, your work is the finest in the land. Farewell!", toolChoice = ToolChoice.Auto) { turn ->
                turn.line.isNotBlank() && !turn.isFallback
            }
        }
    }

    /** The default Explicit choice: look things up when asked, act on orders, skip tools for small talk. */
    private suspend fun innkeeper() {
        log()
        log("== Mira, innkeeper (default toolChoice=explicit, external take_order)")
        val model = RecordingModel(OpenAICompatibleModel(baseUrl, modelName))
        val menu = mapOf("ale" to 3, "stew" to 5, "bread" to 1)
        val orders = ArrayList<String>()
        val tools = listOf(
            AgentTool.local("check_menu", "Look up today's menu with prices in gold.") { ToolOutput.of(menu) },
            AgentTool.external(
                "take_order",
                "Place an order the guest explicitly asks for.",
                JsonSchema.obj(
                    "item" to JsonSchema.string(description = "menu item", enum = menu.keys.toList()),
                    "quantity" to JsonSchema.integer(minimum = 1, maximum = 10),
                ),
            ),
        )
        val mira = Persona(
            name = "Mira",
            role = "the cheerful innkeeper of the Prancing Pony",
            personality = "Warm, chatty and quick-witted.",
            speakingStyle = "Friendly, calls guests 'love'.",
            goals = listOf("Keep her guests fed and happy"),
        )
        NPC(mira, model, tools = tools).use { npc ->
            suspend fun say(line: String, expected: Set<String>) {
                val before = model.calls.size
                val started = System.nanoTime()
                val turn = try {
                    npc.talk(line) { call ->
                        orders += "${call.int("quantity")} ${call.string("item")}"
                        ToolOutput.of(mapOf("ok" to true, "total_gold" to menu.getValue(call.string("item")) * call.int("quantity")))
                    }
                } catch (error: AgentError) {
                    log("PLAYER: $line\n  ERROR ${error.code.wireName}: ${error.message}")
                    tally.check(false)
                    return
                }
                val millis = (System.nanoTime() - started) / 1_000_000
                tally.turns++
                tally.turnMillis += millis
                if (turn.isFallback) tally.fallbacks++
                val calls = model.calls.subList(before, model.calls.size).toList()
                if (calls.any { it.request.kind == GenerationKind.RESPOND }) tally.textRetries++
                val action = turn.toolCalls.firstOrNull()?.call?.name ?: "respond"
                log("PLAYER: $line")
                for (call in calls) log("  [${call.request.kind.name.lowercase()} ${call.millis}ms] ${call.shown.take(200)}")
                log("  MIRA (${millis}ms) [${turn.emotion}] ${turn.line}  options=${turn.playerOptions.size} fallback=${turn.isFallback}")
                log("  => action=$action expected=$expected ${tally.check(action in expected && !turn.isFallback)}")
            }
            say("Hello there! Long road today.", setOf("respond"))
            say("What's on the menu today?", setOf("check_menu"))
            say("I'll have two ales, please.", setOf("take_order"))
            say("Nice weather we're having, isn't it?", setOf("respond"))
            say("Thanks for everything, goodbye!", setOf("respond"))
        }
        log("  orders=$orders")
    }

    /** Plain-text replies: tagged emotion, forced lookup. */
    private suspend fun textFormat() {
        log()
        log("== Gorm, replyFormat=text with a forced lookup")
        val model = RecordingModel(OpenAICompatibleModel(baseUrl, modelName))
        NPC(gorm, model, tools = listOf(inventory()), options = NPCOptions(groundingTool = "check_inventory", replyFormat = NPCReplyFormat.TEXT)).use { npc ->
            turn(npc, model, "Got any axes? What do they cost?", stream = true) { turn ->
                mentions(turn.line, 30, "thirty") && !turn.line.contains("[")
            }
        }
        log()
        log("== Gorm, replyFormat=text, forced lookup and maxToolRounds=1 (reply right after the lookup)")
        NPC(gorm, model, tools = listOf(inventory()), options = NPCOptions(groundingTool = "check_inventory", maxToolRounds = 1, replyFormat = NPCReplyFormat.TEXT)).use { npc ->
            turn(npc, model, "Got any axes? What do they cost?", stream = true) { turn ->
                mentions(turn.line, 30, "thirty") && !turn.line.contains("[")
            }
            turn(npc, model, "And an iron sword?") { turn -> mentions(turn.line, 45, "forty five") }
        }
    }

    /** GAMES.md's guardrail survey: hostile lines through the automatic reply format. */
    private suspend fun hostileLines() {
        log()
        log("== Grukk, hostile lines (replyFormat=automatic, no tools)")
        val model = RecordingModel(OpenAICompatibleModel(baseUrl, modelName))
        val grukk = Persona(
            name = "Grukk",
            role = "an orc warchief guarding the mountain pass",
            personality = "Proud, fierce, honorable in his own way",
            speakingStyle = "Short growled sentences",
        )
        for (line in listOf(
            "I challenge you to a duel, orc!",
            "Your warband burned my village. You will pay.",
            "Stand aside or I'll cut you down.",
        )) {
            NPC(grukk, model).use { npc -> turn(npc, model, line) { turn -> !turn.isFallback && turn.line.isNotBlank() } }
        }
    }

    private suspend fun decisions() {
        log()
        log("== Decisions")
        val model = RecordingModel(OpenAICompatibleModel(baseUrl, modelName))
        val engine = DecisionEngine(model, temperature = 0.0)
        val options = listOf(
            DecisionOption("attack", "Stab the knight with your rusty dagger"),
            DecisionOption("flee", "Squeeze through the narrow crack behind you"),
            DecisionOption("beg", "Drop the dagger and beg for mercy"),
        )
        val snik = Persona(name = "Snik", role = "a cowardly goblin", personality = "Greedy, timid and sly", goals = listOf("Survive at any cost"))
        suspend fun decide(label: String, situation: String, context: Map<String, Any>, expected: Set<String>) {
            val started = System.nanoTime()
            try {
                val decision = engine.decide(situation, options, actor = snik, context = com.spacecorps.oam.jsonOf(context), fallbackOptionId = "flee")
                val millis = (System.nanoTime() - started) / 1_000_000
                log("  $label (${millis}ms): ${decision.optionId} confidence=${decision.confidence} fallback=${decision.isFallback} :: ${decision.reasoning}")
                log("    => expected=$expected ${tally.check(decision.optionId in expected && !decision.isFallback)}")
            } catch (error: AgentError) {
                log("  $label ERROR ${error.code.wireName}: ${error.message}")
                tally.check(false)
            }
        }
        decide(
            "cornered goblin",
            "You are cornered in a cave. The armored knight has full health; you have 3 of 20 HP.",
            mapOf("goblin_hp" to 3, "knight_hp" to 60, "escape_route" to true),
            setOf("flee", "beg"),
        )
        decide(
            "sleeping knight",
            "The knight is fast asleep and unarmored, and his purse is on the table next to you.",
            mapOf("goblin_hp" to 20, "knight_asleep" to true),
            setOf("attack"),
        )
        val crowd = listOf("north", "east", "south").map { gate ->
            DecisionRequest("A crowd of guards at the $gate gate sees the goblin run past.", listOf(DecisionOption("chase"), DecisionOption("ignore"), DecisionOption("raise_alarm")))
        }
        val started = System.nanoTime()
        val results = engine.decideMany(crowd, maxConcurrency = 2)
        log("  decideMany x3 (${(System.nanoTime() - started) / 1_000_000}ms): ${results.map { r -> r.fold({ it.optionId }, { "error" }) }}")
        log("    => ${tally.check(results.all { it.isSuccess })}")
    }

    @Serializable
    private data class Item(val name: String, val description: String, val rarity: String, val damage: Int)

    private suspend fun barksAndContent() {
        log()
        log("== Barks and content")
        val model = RecordingModel(OpenAICompatibleModel(baseUrl, modelName))
        NPC(gorm, model).use { npc ->
            for (situation in listOf("A customer walks past the forge in the rain.", "The morning bell rings over the village.")) {
                val started = System.nanoTime()
                try {
                    val bark = npc.bark(situation)
                    log("  bark (${(System.nanoTime() - started) / 1_000_000}ms): $bark")
                    log("    => ${tally.check(bark.isNotBlank() && !bark.contains("\n") && !bark.startsWith("Gorm"))}")
                } catch (error: AgentError) {
                    log("  bark ERROR ${error.code.wireName}: ${error.message}")
                    tally.check(false)
                }
            }
        }
        val generator = ContentGenerator(model)
        val schema = JsonSchema.obj(
            "name" to JsonSchema.string(description = "Two or three words"),
            "description" to JsonSchema.string(description = "One sentence of flavor text"),
            "rarity" to JsonSchema.string(enum = listOf("common", "rare", "legendary")),
            "damage" to JsonSchema.integer(minimum = 1, maximum = 50),
        )
        val started = System.nanoTime()
        try {
            val item = generator.generateTyped<Item>("A cursed sword found in a drowned temple.", schema, context = jsonObjectOf("player_level" to 7))
            log("  content (${(System.nanoTime() - started) / 1_000_000}ms): $item")
            log("    => ${tally.check(item.damage in 1..50 && item.name.isNotBlank())}")
        } catch (error: AgentError) {
            log("  content ERROR ${error.code.wireName}: ${error.message}")
            tally.check(false)
        }
    }
}
