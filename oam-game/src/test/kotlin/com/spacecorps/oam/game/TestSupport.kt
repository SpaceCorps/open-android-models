package com.spacecorps.oam.game

import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentErrorCode
import com.spacecorps.oam.AgentTool
import com.spacecorps.oam.GenerationKind
import com.spacecorps.oam.GenerationRequest
import com.spacecorps.oam.JsonSchema
import com.spacecorps.oam.ToolCall
import com.spacecorps.oam.ToolExecution
import com.spacecorps.oam.ToolOutput
import com.spacecorps.oam.jsonObjectOf
import com.spacecorps.oam.testing.ScriptedLanguageModel
import com.spacecorps.oam.testing.ScriptedLanguageModel.Step
import kotlinx.serialization.json.JsonObject
import java.util.Collections

/** Shared fixtures, mirroring open-apple-models' game tests. */
internal object Fixtures {
    val gorm = Persona(
        name = "Gorm",
        role = "the village blacksmith",
        personality = "Gruff and proud, but fair",
        speakingStyle = "Short, blunt sentences. Calls people 'lad'.",
        backstory = "Forged weapons for the king's army for twenty years.",
        goals = listOf("Sell his weapons at a fair price"),
        secrets = listOf("He forged the blade that killed the old king"),
        knowledge = listOf("The mine to the north is haunted"),
        defaultEmotion = Emotion.NEUTRAL,
        maxSentences = 2,
    )

    fun inventory(calls: MutableList<ToolCall> = Collections.synchronizedList(ArrayList())): AgentTool = AgentTool.local(
        "check_inventory",
        "Look up stock and price of an item.",
        JsonSchema.obj("item" to JsonSchema.string(description = "Item name")),
    ) { call ->
        calls += call
        ToolOutput.of(mapOf("item" to call.string("item"), "stock" to 3, "price_gold" to 45))
    }

    fun reply(
        line: String,
        emotion: String = "neutral",
        options: List<String> = listOf("Tell me more.", "What else?", "Goodbye."),
        ends: Boolean = false,
    ): Step = Step.Json(jsonObjectOf("emotion" to emotion, "line" to line, "player_options" to options, "ends_conversation" to ends))

    val guardrail: Step = Step.Fail(AgentError(AgentErrorCode.GUARDRAIL_VIOLATION, "blocked"))

    val refusal: Step = Step.Fail(AgentError(AgentErrorCode.REFUSAL, "refused"))

    fun call(name: String, vararg arguments: Pair<String, Any?>): Step = Step.ToolCalls(Step.ScriptedCall(name, jsonObjectOf(*arguments)))
}

/** A native-style scripted model: a tool round takes one step and the answer another. */
internal fun scripted(vararg steps: Step, fallback: Step = Step.Text("(script exhausted)")): ScriptedLanguageModel =
    ScriptedLanguageModel(steps.toList(), fallback = fallback, style = ScriptedLanguageModel.ScriptStyle.NATIVE)

/** The system instruction the model saw. */
internal val GenerationRequest.system: String get() = systemInstruction.orEmpty()

/** Tools offered by a decide step (parsed from its tool list). */
internal val GenerationRequest.offeredTools: List<String>
    get() = if (kind != GenerationKind.DECIDE) {
        emptyList()
    } else {
        Regex("^- ([A-Za-z_][\\w.-]*): ", RegexOption.MULTILINE).findAll(prompt).map { it.groupValues[1] }.toList()
    }

/** Number of player lines in the rendered conversation. */
internal val GenerationRequest.playerLines: Int get() = Regex("^Player: ", RegexOption.MULTILINE).findAll(prompt).count()

/** Runs a local tool's handler directly. */
internal suspend fun AgentTool.invoke(arguments: JsonObject): ToolOutput {
    val local = execution as? ToolExecution.Local ?: error("expected a local tool")
    return local.handler(ToolCall(name = name, arguments = arguments))
}
