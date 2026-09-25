package com.spacecorps.oam.game

import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentErrorCode
import com.spacecorps.oam.GenerationChunk
import com.spacecorps.oam.GenerationKind
import com.spacecorps.oam.GenerationRequest
import com.spacecorps.oam.JsonSchema
import com.spacecorps.oam.LanguageModel
import com.spacecorps.oam.ToolCall
import com.spacecorps.oam.ToolChoice
import com.spacecorps.oam.jsonObjectOf
import com.spacecorps.oam.objectValue
import com.spacecorps.oam.testing.ScriptedLanguageModel
import com.spacecorps.oam.testing.ScriptedLanguageModel.Step
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class DecisionEngineTest {
    private val options = listOf(
        DecisionOption("attack", "Keep fighting"),
        DecisionOption("flee", "Run into the woods"),
        DecisionOption("beg", "Beg for mercy"),
    )

    @Test
    fun decideUsesEnumOfOptionIds() = runTest {
        val model = scripted(Step.Json(jsonObjectOf("reasoning" to "Three HP left against a full-health hero.", "choice" to "flee", "confidence" to 82)))
        val decision = DecisionEngine(model).decide(
            situation = "The goblin has 3 HP; the player is at full health.",
            options = options,
            actor = Persona(name = "Snik", role = "a cowardly goblin", personality = "Greedy and timid", goals = listOf("Survive")),
            context = jsonObjectOf("goblin_hp" to 3, "player_hp" to 40),
        )
        assertEquals("flee", decision.optionId)
        assertEquals("Three HP left against a full-health hero.", decision.reasoning)
        assertEquals(82, decision.confidence)
        assertTrue(decision.toolCalls.isEmpty())
        assertTrue(!decision.isFallback)

        val request = model.requests.single()
        assertEquals(GenerationKind.STRUCTURED, request.kind)
        assertTrue(
            request.prompt.startsWith(
                """
                Situation: The goblin has 3 HP; the player is at full health.
                Facts: {"goblin_hp":3,"player_hp":40}
                Options:
                - attack: Keep fighting
                - flee: Run into the woods
                - beg: Beg for mercy
                """.trimIndent(),
            ),
            request.prompt,
        )
        val reasoning = request.prompt.indexOf("\"reasoning\"")
        val choice = request.prompt.indexOf("\"choice\": one of \"attack\", \"flee\", \"beg\"")
        val confidence = request.prompt.indexOf("\"confidence\"")
        assertTrue(reasoning in 0 until choice && choice < confidence, request.prompt)
        assertTrue(request.system.contains("You decide for Snik, a cowardly goblin."))
        assertTrue(request.system.contains("Personality: Greedy and timid."))
        assertTrue(request.system.contains("Goals: Survive."))
    }

    @Test
    fun invalidOptionsAreRejectedWithoutCallingTheModel() = runTest {
        val model = scripted()
        val engine = DecisionEngine(model)
        for (bad in listOf(
            emptyList(),
            listOf(DecisionOption("a"), DecisionOption("a")),
            listOf(DecisionOption("a"), DecisionOption("  ")),
            listOf(DecisionOption("a"), DecisionOption(" a ")),
        )) {
            assertEquals(AgentErrorCode.INVALID_REQUEST, assertFailsWith<AgentError> { engine.decide("x", bad) }.code, bad.toString())
        }
        assertEquals(AgentErrorCode.INVALID_REQUEST, assertFailsWith<AgentError> { engine.decide("x", options, fallbackOptionId = "dance") }.code)
        assertEquals(AgentErrorCode.INVALID_REQUEST, assertFailsWith<AgentError> { engine.copy(maxToolRounds = -1).decide("x", options) }.code)
        assertTrue(model.requests.isEmpty())
    }

    @Test
    fun singleOptionShortCircuits() = runTest {
        val model = scripted()
        val decision = DecisionEngine(model).decide("Cornered.", listOf(DecisionOption(" fight ")))
        assertEquals("fight", decision.optionId)
        assertEquals(100, decision.confidence)
        assertTrue(model.requests.isEmpty())
    }

    @Test
    fun toolsCanBeRequiredBeforeDeciding() = runTest {
        val calls = Collections.synchronizedList(ArrayList<ToolCall>())
        val model = scripted(
            Fixtures.call("check_inventory", "item" to "potion"),
            Step.Json(jsonObjectOf("reasoning" to "Potions in stock.", "choice" to "attack", "confidence" to 140)),
        )
        val decision = DecisionEngine(model).decide(
            "Low health.", options, tools = listOf(Fixtures.inventory(calls)), toolChoice = ToolChoice.Required,
        )
        assertEquals("attack", decision.optionId)
        assertEquals(100, decision.confidence) // clamped
        assertEquals(1, decision.toolCalls.size)
        assertEquals(listOf(GenerationKind.TOOL_ARGUMENTS, GenerationKind.DECIDE, GenerationKind.STRUCTURED), model.requests.map { it.kind })
        // The decide step speaks of the game and the character, not a user and an assistant.
        assertTrue(model.requests[1].prompt.contains("Game's last message"), model.requests[1].prompt)
        assertTrue(model.requests[1].prompt.contains("What does Character do next?"), model.requests[1].prompt)
    }

    @Test
    fun nearMissesAreMatchedAndOffListChoicesRepaired() = runTest {
        val model = scripted(
            Step.Json(jsonObjectOf("reasoning" to "r", "choice" to "FLEE", "confidence" to "70")),
            Step.Json(jsonObjectOf("reasoning" to "r", "choice" to "dance", "confidence" to 50)),
            Step.Json(jsonObjectOf("reasoning" to "r", "choice" to "beg", "confidence" to 12.0)),
        )
        val engine = DecisionEngine(model)
        val first = engine.decide("x", options)
        assertEquals("flee", first.optionId)
        assertEquals(70, first.confidence)
        // "dance" is re-asked once with the list of ids.
        val second = engine.decide("x", options)
        assertEquals("beg", second.optionId)
        assertEquals(12, second.confidence)
        assertTrue(model.requests[2].prompt.contains("must be one of"), model.requests[2].prompt)
    }

    @Test
    fun choicesOutsideTheOptionsFailWithoutAFallback() = runTest {
        val dance = Step.Json(jsonObjectOf("reasoning" to "r", "choice" to "dance", "confidence" to 50))
        val engine = DecisionEngine(scripted(dance, dance, dance, dance))
        assertEquals(AgentErrorCode.GENERATION_FAILED, assertFailsWith<AgentError> { engine.decide("x", options) }.code)
        // With a fallback, an unusable answer returns it.
        val decision = engine.decide("x", options, fallbackOptionId = "flee")
        assertEquals(Decision(optionId = "flee", reasoning = "", confidence = 0, isFallback = true), decision)
    }

    @Test
    fun blockedDecisionsReturnTheFallbackAndOtherErrorsThrow() = runTest {
        val model = scripted(
            Fixtures.guardrail,
            Fixtures.refusal,
            Step.Fail(AgentError(AgentErrorCode.CONTEXT_SIZE_EXCEEDED, "too long")),
            Fixtures.guardrail,
        )
        val engine = DecisionEngine(model)
        assertEquals(Decision(optionId = "flee", reasoning = "", confidence = 0, isFallback = true), engine.decide("Blocked.", options, fallbackOptionId = "flee"))
        assertEquals("beg", engine.decide("Refused.", options, fallbackOptionId = " beg ").optionId)
        assertEquals(AgentErrorCode.CONTEXT_SIZE_EXCEEDED, assertFailsWith<AgentError> { engine.decide("x", options, fallbackOptionId = "flee") }.code)
        assertEquals(AgentErrorCode.GUARDRAIL_VIOLATION, assertFailsWith<AgentError> { engine.decide("x", options) }.code)
        assertEquals(4, model.requests.size)
    }

    @Test
    fun decideManyKeepsOrderBoundsConcurrencyAndIsolatesFailures() = runTest {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val model = ScriptedLanguageModel(
            fallback = Step.Dynamic { request ->
                val now = running.incrementAndGet()
                peak.accumulateAndGet(now, ::maxOf)
                val prompt = request.prompt
                val choice = listOf("attack", "flee", "beg").firstOrNull { prompt.contains("pick $it") } ?: "attack"
                Step.Delay(20.milliseconds, Step.Json(jsonObjectOf("reasoning" to "Scripted.", "choice" to choice, "confidence" to 70)))
            },
            style = ScriptedLanguageModel.ScriptStyle.NATIVE,
        )
        val tracked = object : LanguageModel by model {
            override fun generate(request: GenerationRequest): Flow<GenerationChunk> =
                model.generate(request).onCompletion { running.decrementAndGet() }
        }
        val engine = DecisionEngine(tracked)
        val picks = listOf("flee", "beg", "attack", "flee", "beg")
        val requests = picks.mapIndexed { index, pick -> DecisionRequest("Guard $index: pick $pick", options) }.toMutableList()
        requests.add(2, DecisionRequest("broken", listOf(DecisionOption("x"), DecisionOption("x"))))

        val results = engine.decideMany(requests, maxConcurrency = 3)
        assertEquals(6, results.size)
        val chosen = results.map { result -> result.fold({ it.optionId }, { "error:${(it as AgentError).code.wireName}" }) }
        assertEquals(listOf("flee", "beg", "error:invalid_request", "attack", "flee", "beg"), chosen)
        assertEquals(5, model.requests.size)
        assertTrue(peak.get() in 2..3, "peak concurrency ${peak.get()}")
        assertTrue(engine.decideMany(emptyList()).isEmpty())
    }

    @Test
    fun decisionTypesSerialize() {
        val decision = Decision(optionId = "flee", reasoning = "r", confidence = 3)
        val json = GameJson.encodeToJsonElement(Decision.serializer(), decision).objectValue!!
        assertEquals(listOf("optionID", "reasoning", "confidence", "toolCalls", "usage", "isFallback"), json.keys.toList())
        assertEquals(decision, GameJson.decodeFromJsonElement(Decision.serializer(), json))
        assertEquals(Decision(optionId = "wait", reasoning = "", confidence = 50), GameJson.decodeFromString(Decision.serializer(), """{"optionID":"wait"}"""))
        assertEquals(DecisionOption("wait", ""), GameJson.decodeFromString(DecisionOption.serializer(), """{"id":"wait"}"""))
    }
}

class ContentGeneratorTest {
    @Serializable
    data class Item(val name: String, val rarity: String, val damage: Int)

    private val itemSchema = JsonSchema.obj(
        "name" to JsonSchema.string(description = "Two or three words"),
        "rarity" to JsonSchema.string(enum = listOf("common", "rare", "legendary")),
        "damage" to JsonSchema.integer(minimum = 1, maximum = 50),
    )

    @Test
    fun generatesAndDecodesItems() = runTest {
        val model = scripted(Step.Json(jsonObjectOf("damage" to "12", "name" to "Drowned Blade", "rarity" to "Rare")))
        val item = ContentGenerator(model).generateTyped<Item>("A cursed sword from a sunken temple.", itemSchema)
        assertEquals(Item("Drowned Blade", "rare", 12), item)
        val request = model.requests.single()
        assertEquals(ContentGenerator.DEFAULT_INSTRUCTIONS, request.system)
        assertEquals(GenerationKind.STRUCTURED, request.kind)
        assertTrue(request.prompt.contains("one of \"common\", \"rare\", \"legendary\""), request.prompt)
    }

    @Test
    fun rawJsonFollowsSchemaOrderAndUsesContext() = runTest {
        val model = scripted(Step.Json(jsonObjectOf("damage" to 5, "rarity" to "common", "name" to "Rusty Knife")))
        val json = ContentGenerator(model, temperature = 0.9).generate(
            "A starter weapon.", itemSchema, instructions = "You design loot.", context = jsonObjectOf("player_level" to 1),
        )
        assertEquals(listOf("name", "rarity", "damage"), (json as JsonObject).keys.toList())
        val request = model.requests.single()
        assertEquals("You design loot.", request.system)
        assertTrue(request.prompt.startsWith("A starter weapon.\nFacts: {\"player_level\":1}"), request.prompt)
        assertEquals(0.9, request.temperature)
    }

    @Test
    fun invalidContentIsRepairedOnceThenFails() = runTest {
        val tooStrong = Step.Json(jsonObjectOf("name" to "Godslayer", "rarity" to "legendary", "damage" to 999))
        val model = scripted(tooStrong, Step.Json(jsonObjectOf("name" to "Godslayer", "rarity" to "legendary", "damage" to 50)), tooStrong, tooStrong)
        val generator = ContentGenerator(model)
        assertEquals(50, generator.generateTyped<Item>("x", itemSchema).damage)
        assertTrue(model.requests[1].prompt.contains("must be at most 50"), model.requests[1].prompt)
        assertEquals(AgentErrorCode.GENERATION_FAILED, assertFailsWith<AgentError> { generator.generate("x", itemSchema) }.code)
    }

    @Test
    fun decodeFailuresAreReported() = runTest {
        val model = scripted(Step.Json(jsonObjectOf("name" to "Nameless", "rarity" to "rare", "damage" to "lots")))
        val error = assertFailsWith<AgentError> {
            ContentGenerator(model).generateTyped<Item>(
                "x",
                JsonSchema.obj("name" to JsonSchema.string(), "rarity" to JsonSchema.string(), "damage" to JsonSchema.string()),
            )
        }
        assertEquals(AgentErrorCode.GENERATION_FAILED, error.code)
        assertTrue(error.message.contains("Could not decode"), error.message)
    }

    @Test
    fun lootTableArrays() = runTest {
        @Serializable
        data class Drop(val item: String, val weight: Int)

        @Serializable
        data class Table(val drops: List<Drop>)

        val model = scripted(Step.Json(jsonObjectOf("drops" to listOf(mapOf("item" to "Gold", "weight" to 60), mapOf("item" to "Gem", "weight" to 5)))))
        val table = ContentGenerator(model).generateTyped<Table>(
            "Loot for a goblin camp.",
            JsonSchema.obj(
                "drops" to JsonSchema.array(
                    JsonSchema.obj("item" to JsonSchema.string(), "weight" to JsonSchema.integer(minimum = 1, maximum = 100)),
                    minItems = 2,
                    maxItems = 5,
                ),
            ),
        )
        assertEquals(listOf("Gold", "Gem"), table.drops.map { it.item })
    }

    @Test
    fun toolsRunBeforeTheContent() = runTest {
        val model = scripted(
            Fixtures.call("check_inventory", "item" to "gem"),
            Step.Dynamic { request ->
                val stock = request.turn?.toolUses?.singleOrNull()?.output?.modelText.orEmpty()
                Step.Json(jsonObjectOf("name" to if (stock.contains("45")) "Priced Gem" else "Gem", "rarity" to "rare", "damage" to 1))
            },
        )
        val item = ContentGenerator(model).generateTyped<Item>("A gem from the shop.", itemSchema, tools = listOf(Fixtures.inventory()))
        assertEquals("Priced Gem", item.name)
        assertEquals(listOf(GenerationKind.DECIDE, GenerationKind.DECIDE, GenerationKind.STRUCTURED), model.requests.map { it.kind })
    }

    @Test
    fun invalidSchemasAndBlocksSurfaceAsErrors() = runTest {
        val generator = ContentGenerator(scripted(Fixtures.guardrail))
        val schema = JsonSchema(jsonObjectOf("type" to "object", "properties" to mapOf("a" to 5)))
        assertEquals(AgentErrorCode.INVALID_SCHEMA, assertFailsWith<AgentError> { generator.generate("x", schema) }.code)
        assertEquals(AgentErrorCode.GUARDRAIL_VIOLATION, assertFailsWith<AgentError> { generator.generate("x", itemSchema) }.code)
        assertEquals(AgentErrorCode.INVALID_REQUEST, assertFailsWith<AgentError> { generator.copy(temperature = -1.0).generate("x", itemSchema) }.code)
    }
}
