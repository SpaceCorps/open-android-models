package com.spacecorps.oam

import com.spacecorps.oam.openai.OpenAICompatibleModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live evaluation of the prompt-envelope tool loop against a small
 * on-device-class model behind an OpenAI-compatible server (plain chat
 * completions, no native tools) — the closest desktop stand-in for Gemini
 * Nano. Opt-in:
 *
 * ```
 * (cd open-apple-models && swift build -c release --product oam && .build/release/oam serve --port 19997) &
 * OAM_PROXY_EVAL=1 ./gradlew :oam-core:test --tests '*ProxyEvalTest*'
 * ```
 *
 * `OAM_PROXY_URL` (default `http://127.0.0.1:19997/v1`) and `OAM_PROXY_MODEL`
 * (default `system`) select the server. The report is printed and written to
 * `oam-core/build/proxy-eval.txt`.
 */
@EnabledIfEnvironmentVariable(named = "OAM_PROXY_EVAL", matches = "1")
class ProxyEvalTest {
    private val baseUrl = System.getenv("OAM_PROXY_URL") ?: "http://127.0.0.1:19997/v1"
    private val modelName = System.getenv("OAM_PROXY_MODEL") ?: "system"

    /** One player line and what a good first decision looks like. */
    private data class Line(
        val text: String,
        val expected: Set<String>,
        val expectedArguments: Map<String, Any>? = null,
    )

    private val conversation = listOf(
        Line("Hello there! Long road today.", setOf("respond")),
        Line("What's on the menu today?", setOf("check_menu")),
        Line("How much is the stew?", setOf("check_menu", "respond")),
        Line("I'll have two ales, please.", setOf("take_order"), mapOf("item" to "ale", "quantity" to 2)),
        Line("Nice weather we're having, isn't it?", setOf("respond")),
        Line("Do you serve roast boar? I'd like one.", setOf("check_menu", "respond")),
        Line("Then one bowl of stew, please.", setOf("take_order"), mapOf("item" to "stew", "quantity" to 1)),
        Line("What does a loaf of bread cost?", setOf("check_menu", "respond")),
        Line("Thanks for everything, goodbye!", setOf("respond")),
    )

    /** Records every model call's output and latency. */
    private class RecordingModel(private val base: LanguageModel) : LanguageModel by base {
        data class Call(val request: GenerationRequest, val output: String, val millis: Long)

        val calls: MutableList<Call> = Collections.synchronizedList(ArrayList())

        override fun generate(request: GenerationRequest): Flow<GenerationChunk> {
            val started = System.nanoTime()
            var text = ""
            return base.generate(request)
                .onEach { text = it.text }
                .onCompletion { calls += Call(request, text, (System.nanoTime() - started) / 1_000_000) }
        }
    }

    private val menu = mapOf("ale" to 3, "stew" to 5, "bread" to 1, "cider" to 4)

    private fun tools(orders: MutableList<ToolCall>) = listOf(
        AgentTool.local("check_menu", "Look up today's menu with prices in gold.") { ToolOutput.of(menu) },
        AgentTool.local(
            "take_order",
            "Place an order the guest explicitly asks for.",
            JsonSchema.obj(
                "item" to JsonSchema.string(description = "menu item", enum = menu.keys.toList()),
                "quantity" to JsonSchema.integer(minimum = 1, maximum = 10),
            ),
        ) { call ->
            val item = call.string("item").lowercase()
            if (item !in menu) {
                ToolOutput.Error("'$item' is not on the menu.")
            } else {
                orders += call
                ToolOutput.of(mapOf("ok" to true, "item" to item, "quantity" to call.int("quantity"), "total" to menu.getValue(item) * call.int("quantity")))
            }
        },
    )

    private val instructions = """
        You are Mira, the cheerful innkeeper of the Prancing Pony in a fantasy game.
        You serve food and drink to guests. Only offer what the menu has; never invent prices.
        Reply in one or two short sentences, in character.
    """.trimIndent()

    @Test
    fun innkeeperToolLoop() = runBlocking {
        val base = OpenAICompatibleModel(baseUrl, modelName)
        val available = base.availability()
        assertTrue(available.isAvailable, "Proxy server not available at $baseUrl: $available")
        val report = StringBuilder()
        fun log(line: String) {
            println(line)
            report.appendLine(line)
        }

        var decideCalls = 0
        var parsedAll = 0
        var firstTries = 0
        var parsedFirstTry = 0
        var correct = 0
        val turnMillis = ArrayList<Long>()
        val decideMillis = ArrayList<Long>()
        val respondMillis = ArrayList<Long>()

        for (choice in listOf(ToolChoice.Explicit)) {
            val model = RecordingModel(base)
            val orders = mutableListOf<ToolCall>()
            val toolSet = tools(orders)
            val agent = Agent(
                model,
                instructions = instructions,
                tools = toolSet,
                configuration = AgentConfiguration(
                    toolPolicy = ToolPolicy(choice = choice, maxToolRounds = 2),
                    userLabel = "Guest",
                    assistantLabel = "Mira",
                    retry = RetryPolicy.None,
                ),
            )
            log("== choice=$choice model=$modelName url=$baseUrl")
            for (line in conversation) {
                val before = model.calls.size
                val started = System.nanoTime()
                val response = try {
                    agent.respond(line.text)
                } catch (error: AgentError) {
                    log("GUEST: ${line.text}\n  ERROR ${error.code.wireName}: ${error.message}")
                    continue
                }
                val millis = (System.nanoTime() - started) / 1_000_000
                turnMillis += millis
                val calls = model.calls.subList(before, model.calls.size).toList()
                val decides = calls.filter { it.request.kind == GenerationKind.DECIDE }
                decides.forEach { decideMillis += it.millis }
                calls.filter { it.request.kind == GenerationKind.RESPOND }.forEach { respondMillis += it.millis }

                // First decision of the turn: a tool call, or respond.
                val repaired = response.steps.any { it.isRepair }
                val firstCall = response.toolCalls.firstOrNull()
                val action = firstCall?.call?.name ?: "respond"
                // Parse accounting over every decide output.
                for (call in decides) {
                    val isRepair = call.request.prompt.contains("Your previous answer was invalid")
                    val valid = when (val parsed = StepEnvelope.parse(call.output, toolSet.map { it.name }, allowRespond = true)) {
                        StepEnvelope.Result.Respond -> true
                        is StepEnvelope.Result.Invalid -> false
                        is StepEnvelope.Result.Call -> {
                            val tool = toolSet.first { it.name == parsed.tool }
                            parsed.candidates.any { tool.parameters.validate(tool.parameters.coerce(it)).isEmpty() }
                        }
                    }
                    decideCalls++
                    if (valid) parsedAll++
                    if (!isRepair) {
                        firstTries++
                        if (valid) parsedFirstTry++
                    }
                }
                val argumentsOk = line.expectedArguments?.let { expected ->
                    firstCall != null && expected.all { (key, value) ->
                        val actual = firstCall.call.arguments[key]
                        when (value) {
                            is Int -> actual?.intValue == value
                            else -> actual?.stringValue?.lowercase() == value.toString()
                        }
                    }
                } ?: true
                val ok = action in line.expected && argumentsOk
                if (ok) correct++
                log("GUEST: ${line.text}")
                for (call in calls) {
                    log("  [${call.request.kind.name.lowercase()} ${call.millis}ms] ${call.output.replace("\n", "⏎").take(220)}")
                }
                log("  tools=${response.toolCalls.joinToString { it.call.name + it.call.arguments.toJsonString() }} repaired=$repaired")
                log("  MIRA (${millis}ms): ${response.text}")
                log("  => action=$action expected=${line.expected} args=${if (argumentsOk) "ok" else "WRONG"} ${if (ok) "OK" else "MISS"}")
            }
            agent.close()
        }

        fun pct(part: Int, whole: Int) = if (whole == 0) "n/a" else "${100 * part / whole}%"
        fun avg(values: List<Long>) = if (values.isEmpty()) 0 else values.sum() / values.size
        fun p90(values: List<Long>) = values.sorted().let { if (it.isEmpty()) 0 else it[((it.size - 1) * 9) / 10] }
        log("")
        log("SUMMARY")
        log("  turns=${conversation.size} correct first action=$correct/${conversation.size} (${100 * correct / conversation.size}%)")
        log("  decide outputs=$decideCalls valid=$parsedAll (${pct(parsedAll, decideCalls)}); first attempts=$firstTries valid=$parsedFirstTry (${pct(parsedFirstTry, firstTries)})")
        log("  latency: turn avg=${avg(turnMillis)}ms p90=${p90(turnMillis)}ms; decide avg=${avg(decideMillis)}ms; respond avg=${avg(respondMillis)}ms")
        File("build").mkdirs()
        File("build/proxy-eval.txt").writeText(report.toString())
    }

    /** Each line with a fresh agent (no history): decision accuracy without conversational context. */
    @Test
    fun freshContextDecisions() = runBlocking {
        val base = OpenAICompatibleModel(baseUrl, modelName)
        val report = StringBuilder()
        fun log(line: String) {
            println(line)
            report.appendLine(line)
        }
        val lines = conversation + listOf(
            Line("Can I get three breads?", setOf("take_order"), mapOf("item" to "bread", "quantity" to 3)),
            Line("Tell me a story about this inn.", setOf("respond")),
            Line("Which drinks do you have?", setOf("check_menu")),
            Line("A cider for me, thanks.", setOf("take_order"), mapOf("item" to "cider", "quantity" to 1)),
        )
        var correct = 0
        val millis = ArrayList<Long>()
        log("== fresh-context decisions (Explicit), ${lines.size} lines")
        for (line in lines) {
            val model = RecordingModel(base)
            val agent = Agent(
                model,
                instructions = instructions,
                tools = tools(mutableListOf()),
                configuration = AgentConfiguration(
                    toolPolicy = ToolPolicy(choice = ToolChoice.Explicit, maxToolRounds = 1),
                    userLabel = "Guest",
                    assistantLabel = "Mira",
                    retry = RetryPolicy.None,
                ),
            )
            val started = System.nanoTime()
            val response = agent.respond(line.text)
            millis += (System.nanoTime() - started) / 1_000_000
            val first = response.toolCalls.firstOrNull()
            val action = first?.call?.name ?: "respond"
            val argumentsOk = line.expectedArguments?.let { expected -> first != null && matches(first.call, expected) } ?: true
            val ok = action in line.expected && argumentsOk
            if (ok) correct++
            log("  ${if (ok) "OK  " else "MISS"} ${line.text} -> $action${first?.call?.arguments?.toJsonString().orEmpty()} | ${model.calls.first().output.replace("\n", "⏎").take(120)}")
            agent.close()
        }
        log("  correct=$correct/${lines.size} (${100 * correct / lines.size}%) avg turn=${millis.sum() / millis.size}ms")
        File("build").mkdirs()
        File("build/proxy-eval-fresh.txt").writeText(report.toString())
    }

    /** ToolChoice.Tool and Required: argument extraction, and structured NPC replies. */
    @Test
    fun forcedToolsAndStructuredReplies() = runBlocking {
        val base = OpenAICompatibleModel(baseUrl, modelName)
        val report = StringBuilder()
        fun log(line: String) {
            println(line)
            report.appendLine(line)
        }
        val orders = listOf(
            "Two ales and make it quick!" to mapOf("item" to "ale", "quantity" to 2),
            "I'd love a bowl of your stew." to mapOf("item" to "stew", "quantity" to 1),
            "Four loaves of bread for the road, please." to mapOf("item" to "bread", "quantity" to 4),
        )
        var ok = 0
        for (choice in listOf(ToolChoice.Tool("take_order"), ToolChoice.Required)) {
            log("== $choice")
            for ((text, expected) in orders) {
                val model = RecordingModel(base)
                val agent = Agent(
                    model,
                    instructions = instructions,
                    tools = tools(mutableListOf()),
                    configuration = AgentConfiguration(
                        toolPolicy = ToolPolicy(choice = choice, maxToolRounds = 1),
                        userLabel = "Guest",
                        assistantLabel = "Mira",
                        retry = RetryPolicy.None,
                    ),
                )
                val response = agent.respond(text)
                val call = response.toolCalls.firstOrNull()?.call
                val good = call != null && call.name == "take_order" && matches(call, expected)
                if (good) ok++
                val repairs = response.steps.count { it.isRepair }
                log("  ${if (good) "OK  " else "MISS"} $text -> ${call?.name}${call?.arguments?.toJsonString().orEmpty()} repairs=$repairs | ${response.text}")
                agent.close()
            }
        }
        log("  forced tool calls correct=$ok/${orders.size * 2}")

        val schema = JsonSchema.obj(
            "emotion" to JsonSchema.string(enum = listOf("neutral", "happy", "sad", "angry", "surprised", "worried")),
            "line" to JsonSchema.string(description = "what Mira says"),
            "endsConversation" to JsonSchema.boolean(),
        )
        var valid = 0
        var firstTry = 0
        val structuredLines = listOf("Hello!", "Someone stole my horse!", "Your stew is terrible.", "Goodbye, see you tomorrow.")
        log("== structured replies")
        val model = RecordingModel(base)
        val agent = Agent(
            model,
            instructions = instructions,
            tools = tools(mutableListOf()),
            configuration = AgentConfiguration(userLabel = "Guest", assistantLabel = "Mira", retry = RetryPolicy.None),
        )
        for (text in structuredLines) {
            try {
                val response = agent.respond(text, schema, ToolPolicy(choice = ToolChoice.None))
                valid++
                if (response.steps.none { it.isRepair }) firstTry++
                log("  OK   $text -> ${response.text} repairs=${response.steps.count { it.isRepair }}")
            } catch (error: AgentError) {
                log("  FAIL $text -> ${error.code.wireName}: ${error.message}")
            }
        }
        log("  structured valid=$valid/${structuredLines.size} first try=$firstTry/${structuredLines.size}")
        agent.close()
        File("build").mkdirs()
        File("build/proxy-eval-forced.txt").writeText(report.toString())
    }

    private fun matches(call: ToolCall, expected: Map<String, Any>): Boolean = expected.all { (key, value) ->
        val actual = call.arguments[key]
        when (value) {
            is Int -> actual?.intValue == value
            else -> actual?.stringValue?.lowercase() == value.toString()
        }
    }
}
