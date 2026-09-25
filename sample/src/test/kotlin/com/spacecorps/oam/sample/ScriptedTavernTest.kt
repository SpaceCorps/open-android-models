package com.spacecorps.oam.sample

import com.spacecorps.oam.Agent
import com.spacecorps.oam.AgentConfiguration
import com.spacecorps.oam.ToolChoice
import com.spacecorps.oam.ToolOutput
import com.spacecorps.oam.ToolPolicy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The demo's scripted mode, end to end through the real agent loop and tools. */
class ScriptedTavernTest {
    private fun agent(tavern: Tavern, scope: kotlinx.coroutines.CoroutineScope) = Agent(
        ScriptedMira.model(tavern),
        instructions = Tavern.MIRA_INSTRUCTIONS,
        tools = tavern.tools(),
        configuration = AgentConfiguration(userLabel = "Player", assistantLabel = "Mira"),
        scope = scope,
    )

    private val policy = ToolPolicy(choice = ToolChoice.Explicit, maxToolRounds = 2)

    @Test
    fun cannedEveningChecksTheMenuThenTakesGold() = runTest {
        val tavern = Tavern()
        val mira = agent(tavern, backgroundScope)

        val menu = mira.respond(Tavern.SUGGESTIONS[0], policy)
        assertEquals(listOf("check_menu"), menu.toolCalls.map { it.call.name })
        assertTrue(menu.text.contains("rabbit stew for 5"), menu.text)

        val order = mira.respond(Tavern.SUGGESTIONS[1], policy)
        assertEquals(listOf("take_order"), order.toolCalls.map { it.call.name })
        assertEquals("rabbit stew", order.toolCalls.single().call.string("item"))
        assertEquals(25, tavern.gold.value)
        assertTrue(order.text.contains("25 gold"), order.text)

        val news = mira.respond(Tavern.SUGGESTIONS[2], policy)
        assertTrue(news.toolCalls.isEmpty())
        assertTrue(news.text.isNotBlank())
    }

    @Test
    fun theTillRefusesWhatThePlayerCannotAfford() = runTest {
        val tavern = Tavern(startingGold = 3)
        val reply = agent(tavern, backgroundScope).respond("I'll have the rabbit stew, please.", policy)
        val output = reply.toolCalls.single().output
        assertTrue(output is ToolOutput.Error && output.message.contains("only 3 gold"), output.toString())
        assertEquals(3, tavern.gold.value)
        assertTrue(reply.text.startsWith("Sorry, love."), reply.text)
    }

    @Test
    fun itemsMatchLoosely() {
        val tavern = Tavern()
        assertEquals("rabbit stew", tavern.matchItem("How much is the stew?"))
        assertEquals("mulled wine", tavern.matchItem("a mulled wine"))
        assertEquals("ale", tavern.matchItem("ale"))
        assertEquals(null, tavern.matchItem("Any news?"))
    }
}
