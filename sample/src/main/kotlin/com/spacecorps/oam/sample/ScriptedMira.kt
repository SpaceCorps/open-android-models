package com.spacecorps.oam.sample

import com.spacecorps.oam.GenerationChunk
import com.spacecorps.oam.GenerationKind
import com.spacecorps.oam.GenerationRequest
import com.spacecorps.oam.LanguageModel
import com.spacecorps.oam.ModelCapabilities
import com.spacecorps.oam.ToolOutput
import com.spacecorps.oam.TranscriptEntry
import com.spacecorps.oam.arrayValue
import com.spacecorps.oam.intValue
import com.spacecorps.oam.jsonObjectOf
import com.spacecorps.oam.objectValue
import com.spacecorps.oam.stringValue
import com.spacecorps.oam.testing.ScriptedLanguageModel
import com.spacecorps.oam.testing.ScriptedLanguageModel.Step
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A keyword-driven stand-in for Gemini Nano, so the demo runs on any device
 * or emulator: it plays Mira through the same agent, tools and prompt loop,
 * choosing tools from simple keywords and writing replies from the tool results.
 */
object ScriptedMira {
    /** A scripted Mira over [tavern], streaming at a readable pace. */
    fun model(tavern: Tavern): LanguageModel = PacedModel(
        ScriptedLanguageModel(
            steps = emptyList(),
            fallback = Step.Dynamic { request -> step(request, tavern) },
            capabilities = ModelCapabilities(modelName = "scripted Mira"),
            recordsRequests = false,
        ),
        perChunk = 45.milliseconds,
    )

    private fun step(request: GenerationRequest, tavern: Tavern): Step {
        val prompt = request.turn?.prompt.orEmpty()
        val uses = request.turn?.toolUses.orEmpty()
        val item = tavern.matchItem(prompt)
        return when (request.kind) {
            GenerationKind.DECIDE -> when {
                uses.isNotEmpty() -> Step.respond()
                chatting(prompt) && !ordering(prompt) -> Step.respond()
                item != null && ordering(prompt) -> Step.call("take_order", "item" to item)
                item != null || askingForMenu(prompt) -> Step.call("check_menu", "item" to (item ?: ""))
                else -> Step.respond()
            }
            GenerationKind.TOOL_ARGUMENTS -> Step.Json(jsonObjectOf("item" to (item ?: "")))
            GenerationKind.SUMMARY -> Step.Text("The traveler came in from the rain and chatted with Mira.")
            else -> reply(prompt, uses).let { Step.Text(it, chunks = (it.length / 6).coerceAtLeast(1)) }
        }
    }

    private fun ordering(text: String): Boolean {
        val lower = text.lowercase()
        return listOf("i'll take", "i'll have", "i will have", "i'd like", "give me", "one ", "a pint", "order", "please").any { lower.contains(it) }
    }

    private fun chatting(text: String): Boolean {
        val lower = text.lowercase()
        return (NEWS + FAREWELLS + THANKS).any { lower.contains(it) }
    }

    private fun askingForMenu(text: String): Boolean {
        val lower = text.lowercase()
        return listOf("menu", "what have you", "what do you have", "what's good", "eat", "drink", "warm", "hungry", "thirsty", "cost", "price", "how much")
            .any { lower.contains(it) }
    }

    private fun reply(prompt: String, uses: List<TranscriptEntry.ToolUse>): String {
        val last = uses.lastOrNull()
        val lower = prompt.lowercase()
        return when {
            last == null && NEWS.any { lower.contains(it) } ->
                "The old mill road's flooded, love, so mind your boots. And Tobin plays his lute here on Fridays."
            last == null && FAREWELLS.any { lower.contains(it) } ->
                "Safe travels, love. Mind the rain."
            last == null && THANKS.any { lower.contains(it) } ->
                "Glad you liked it, love! Anything else to warm you up?"
            last == null ->
                "Come in out of the rain, love. Stew's hot and the ale's cold."
            last.output is ToolOutput.Error ->
                "Sorry, love. ${(last.output as ToolOutput.Error).message}"
            last.call.name == "take_order" -> {
                val result = (last.output as? ToolOutput.Json)?.value?.objectValue
                val served = result?.get("served")?.stringValue ?: "that"
                val left = result?.get("player_gold_left")?.intValue
                "One $served coming right up, love." + (left?.let { " That leaves you $it gold." } ?: "")
            }
            else -> {
                val items = (last.output as? ToolOutput.Json)?.value?.arrayValue.orEmpty().mapNotNull { it.objectValue }
                val listed = items.joinToString(", ") { "${it["item"]?.stringValue} for ${it["price_gold"]?.intValue}" }
                if (items.size == 1) "The $listed gold, love. Want one?" else "Tonight we've $listed gold. What'll it be, love?"
            }
        }
    }

    private val NEWS = listOf("news", "road", "rumour", "rumor")
    private val FAREWELLS = listOf("bye", "goodnight", "good night", "farewell")
    private val THANKS = listOf("thank", "great", "best", "lovely", "delicious")

    /** Delays each streamed chunk so scripted replies animate like a real model. */
    private class PacedModel(private val inner: LanguageModel, private val perChunk: Duration) : LanguageModel by inner {
        override fun generate(request: GenerationRequest): Flow<GenerationChunk> =
            inner.generate(request).onEach { if (!it.isFinal) delay(perChunk) }
    }
}
