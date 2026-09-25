package com.spacecorps.oam.mlkit

import com.google.mlkit.genai.common.GenAiException.ErrorCode
import com.spacecorps.oam.Agent
import com.spacecorps.oam.AgentConfiguration
import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentErrorCode
import com.spacecorps.oam.AgentTool
import com.spacecorps.oam.JsonSchema
import com.spacecorps.oam.RetryPolicy
import com.spacecorps.oam.ToolChoice
import com.spacecorps.oam.ToolOutput
import com.spacecorps.oam.ToolPolicy
import com.spacecorps.oam.mlkit.FakeNanoClient.Companion.genAi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The agent's prompt-envelope tool loop running on [GeminiNanoModel] (with ML Kit faked). */
class GeminiNanoAgentTest {
    private val calls = ArrayList<String>()
    private val menu = AgentTool.local(
        "check_menu",
        "Look up the tavern's menu and prices.",
        JsonSchema.obj("item" to JsonSchema.string(description = "Menu item; empty for the whole menu.")),
    ) { call ->
        calls += call.stringOrNull("item").orEmpty()
        ToolOutput.of(mapOf("item" to "rabbit stew", "price_gold" to 5))
    }

    @Test
    fun toolLoopRunsThroughMlKitRequests() = runTest {
        val client = FakeNanoClient()
        client.respond("{\"action\": \"check_menu\", \"arguments\": {\"item\": \"stew\"}}")
        client.respond("{\"action\": \"respond\"}")
        client.respond("Stew's five ", "gold, love.")
        val agent = Agent(
            GeminiNanoModel(client, GeminiNanoOptions()),
            instructions = "You are Mira, the innkeeper.",
            tools = listOf(menu),
            configuration = AgentConfiguration(userLabel = "Player", assistantLabel = "Mira"),
            scope = backgroundScope,
        )

        val response = agent.respond("How much is the stew?", ToolPolicy(choice = ToolChoice.Explicit))

        assertEquals("Stew's five gold, love.", response.text)
        assertEquals(listOf("stew"), calls)
        assertEquals("check_menu", response.toolCalls.single().call.name)
        assertEquals(3, client.requests.size)
        // nano-v4: the persona travels as ML Kit's system instruction, byte-identical on every step.
        val systems = client.requests.map { it.systemInstruction }
        assertTrue(systems.all { it != null && it.contains("You are Mira, the innkeeper.") })
        assertEquals(1, systems.toSet().size)
        // Decide steps run cold and short.
        assertEquals(0f, client.requests[0].temperature)
        assertTrue(client.requests[0].text.contains("How much is the stew?"))
    }

    @Test
    fun olderDevicesGetTheInstructionInThePrompt() = runTest {
        val client = FakeNanoClient(features = FakeNanoClient.NANO_V2)
        client.respond("Evening, love.")
        val nano = GeminiNanoModel(client, GeminiNanoOptions())
        nano.availability() // detects nano-v2: no system instructions
        val agent = Agent(nano, instructions = "You are Mira.", scope = backgroundScope)

        assertEquals("Evening, love.", agent.respond("Hello").text)
        val request = client.requests.single()
        assertNull(request.systemInstruction)
        assertTrue(request.text.startsWith("You are Mira."), request.text)
    }

    @Test
    fun busyQuotaIsRetriedWithBackoff() = runTest {
        val client = FakeNanoClient()
        client.fail(genAi(ErrorCode.BUSY))
        client.respond("Evening.")
        val agent = Agent(GeminiNanoModel(client, GeminiNanoOptions()), scope = backgroundScope)
        assertEquals("Evening.", agent.respond("Hello").text)
        assertEquals(2, client.requests.size)
    }

    @Test
    fun guardrailViolationsFailTheTurn() = runTest {
        val client = FakeNanoClient()
        client.fail(genAi(ErrorCode.RESPONSE_GENERATION_ERROR))
        val agent = Agent(
            GeminiNanoModel(client, GeminiNanoOptions()),
            configuration = AgentConfiguration(retry = RetryPolicy.Default),
            scope = backgroundScope,
        )
        val error = assertFailsWith<AgentError> { agent.respond("Something rude") }
        assertEquals(AgentErrorCode.GUARDRAIL_VIOLATION, error.code)
        assertEquals(ErrorCode.RESPONSE_GENERATION_ERROR, error.mlKitErrorCode)
        assertEquals(1, client.requests.size)
        assertTrue(agent.history.isEmpty())
    }
}
