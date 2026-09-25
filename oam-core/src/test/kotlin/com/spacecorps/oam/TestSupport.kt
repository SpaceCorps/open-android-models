package com.spacecorps.oam

import com.spacecorps.oam.testing.ScriptedLanguageModel
import com.spacecorps.oam.testing.ScriptedLanguageModel.Step
import kotlinx.coroutines.flow.toList
import java.util.concurrent.atomic.AtomicInteger

/** Shared fixtures: the innkeeper's tools. */
internal object Tavern {
    val menuCalls = AtomicInteger()

    val menuSchema = JsonSchema.empty

    val orderSchema = JsonSchema.obj(
        "item" to JsonSchema.string(description = "menu item", enum = listOf("ale", "stew", "bread")),
        "quantity" to JsonSchema.integer(minimum = 1, maximum = 10),
    )

    fun menu(): AgentTool = AgentTool.local("check_menu", "Look up today's menu and prices.") {
        menuCalls.incrementAndGet()
        ToolOutput.of(mapOf("ale" to 3, "stew" to 5, "bread" to 1))
    }

    fun order(orders: MutableList<ToolCall> = mutableListOf()): AgentTool =
        AgentTool.local("take_order", "Place an order for the guest.", orderSchema) { call ->
            orders += call
            ToolOutput.of(mapOf("ok" to true, "item" to call.string("item"), "quantity" to call.int("quantity")))
        }
}

internal fun scripted(vararg steps: Step, capabilities: ModelCapabilities = ModelCapabilities()): ScriptedLanguageModel =
    ScriptedLanguageModel(steps.toList(), capabilities = capabilities)

internal suspend fun AgentRun.collectAll(): List<AgentEvent> = events.toList()
