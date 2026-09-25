package com.spacecorps.oam

import com.spacecorps.oam.testing.ScriptedLanguageModel.Step
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StructuredOutputTest {
    private val reply = JsonSchema.obj(
        "emotion" to JsonSchema.string(enum = listOf("neutral", "happy", "worried")),
        "line" to JsonSchema.string(description = "what Mira says"),
        "endsConversation" to JsonSchema.boolean(),
    )

    @Serializable
    data class Reply(val emotion: String, val line: String, val endsConversation: Boolean)

    @Test
    fun validOutputIsCoercedOrderedAndDecodable() = runTest {
        val model = scripted(Step.Text("""Sure! ```json
            {"line": "Welcome!", "endsConversation": "false", "emotion": "Happy"}
            ```"""))
        val agent = Agent(model, scope = backgroundScope)
        val response = agent.respond("Hello", reply)
        assertEquals("""{"emotion":"happy","line":"Welcome!","endsConversation":false}""", response.text)
        assertEquals(Reply("happy", "Welcome!", false), response.decode<Reply>())
        assertEquals(listOf(StepKind.STRUCTURED), response.steps.map { it.kind })
        assertEquals(TranscriptEntry.Response(response.text, response.structured), agent.history.last())
        val prompt = model.requests.single()
        assertEquals(GenerationKind.STRUCTURED, prompt.kind)
        assertEquals(
            "Hello\n\nAnswer with one JSON object with these keys:\n- \"emotion\": one of \"neutral\", \"happy\", \"worried\"\n" +
                "- \"line\": string, what Mira says\n- \"endsConversation\": true or false\nAnswer with the JSON object only.",
            prompt.prompt,
        )
    }

    @Test
    fun invalidOutputIsRepairedOnce() = runTest {
        val model = scripted(
            Step.Json(jsonObjectOf("emotion" to "ecstatic", "line" to "Hi!", "endsConversation" to false)),
            Step.Json(jsonObjectOf("emotion" to "happy", "line" to "Hi!", "endsConversation" to false)),
        )
        val agent = Agent(model, scope = backgroundScope)
        val response = agent.respond("Hello", reply)
        assertEquals(listOf(false, true), response.steps.map { it.isRepair })
        assertTrue(
            model.requests[1].prompt.contains(
                "Your previous answer was invalid: the JSON does not match the required form: $.emotion: must be one of \"neutral\", \"happy\", \"worried\"; got \"ecstatic\".",
            ),
        )
    }

    @Test
    fun outputStillInvalidAfterRepairFailsTheTurn() = runTest {
        val model = scripted(Step.Text("I'd rather just talk."), Step.Json(jsonObjectOf("line" to "Hi!")))
        val agent = Agent(model, scope = backgroundScope)
        val error = assertFailsWith<AgentError> { agent.respond("Hello", reply) }
        assertEquals(AgentErrorCode.GENERATION_FAILED, error.code)
        assertTrue(error.message.contains("missing required property \"emotion\""), error.message)
        assertEquals(emptyList(), agent.history)
    }

    @Test
    fun partialEventsStreamAsJsonArrives() = runTest {
        val model = scripted(Step.Text("""{"emotion": "happy", "line": "Welcome to the Prancing Pony, traveler!", "endsConversation": false}""", chunks = 6))
        val agent = Agent(model, scope = backgroundScope)
        val events = agent.run("Hi", reply).events.toList()
        val partials = events.filterIsInstance<AgentEvent.Partial>().map { it.value }
        assertTrue(partials.size >= 3, "$partials")
        assertTrue(partials.any { it.objectValue?.get("line")?.stringValue?.let { line -> line.isNotEmpty() && line != "Welcome to the Prancing Pony, traveler!" } == true })
        assertEquals(jsonObjectOf("emotion" to "happy", "line" to "Welcome to the Prancing Pony, traveler!", "endsConversation" to false), partials.last())
    }

    @Test
    fun toolsRunBeforeTheStructuredAnswer() = runTest {
        val model = scripted(
            Step.call("check_menu"),
            Step.respond(),
            Step.Json(jsonObjectOf("emotion" to "happy", "line" to "Ale is 3.", "endsConversation" to false)),
        )
        val agent = Agent(model, tools = listOf(Tavern.menu()), scope = backgroundScope)
        val response = agent.respond("Ale price?", reply)
        assertEquals(listOf(StepKind.DECIDE, StepKind.DECIDE, StepKind.STRUCTURED), response.steps.map { it.kind })
        val prompt = model.requests.last().prompt
        assertTrue(prompt.contains("[check_menu → "), prompt)
        assertTrue(prompt.contains("Write Assistant's new reply to User's last message (\"Ale price?\") as one JSON object with these keys:"), prompt)
    }

    @Test
    fun structuredRepliesAppearAsKeyValueTextInHistory() = runTest {
        val model = scripted(
            Step.Json(jsonObjectOf("emotion" to "happy", "line" to "Hi!", "endsConversation" to false)),
            Step.Json(jsonObjectOf("emotion" to "neutral", "line" to "Sure.", "endsConversation" to false)),
            Step.Json(jsonObjectOf("emotion" to "neutral", "line" to "Bye.", "endsConversation" to true)),
        )
        val agent = Agent(model, configuration = AgentConfiguration(userLabel = "Guest", assistantLabel = "Mira"), scope = backgroundScope)
        agent.respond("Hello", reply)
        agent.respond("Ale?", reply)
        assertTrue(model.requests[1].prompt.startsWith("Conversation:\nGuest: Hello\nMira: emotion: happy; line: Hi!; endsConversation: false\nGuest: Ale?"))
        // A game can show just the spoken line.
        agent.configuration = agent.configuration.copy(structuredReplyRenderer = { it.objectValue!!["line"]!!.stringValue!! })
        agent.respond("Bye", reply)
        assertTrue(model.requests[2].prompt.contains("Mira: Hi!\nGuest: Ale?\nMira: Sure.\nGuest: Bye"), model.requests[2].prompt)
    }

    @Test
    fun arrayRootsAndDecodeErrors() = runTest {
        val list = JsonSchema.array(JsonSchema.string(), minItems = 2)
        val model = scripted(Step.Text("""Names: ["Bree", "Tom"] and more"""))
        val response = Agent(model, scope = backgroundScope).respond("Two names", list)
        assertEquals("""["Bree","Tom"]""", response.text)
        assertTrue(model.requests.single().prompt.endsWith("Answer with JSON of this form:\n[string, …] (at least 2 items)\nAnswer with the JSON only."))
        assertFailsWith<AgentError> { response.decode<Reply>() }
        assertFailsWith<AgentError> { AgentResponse("plain").decode<Reply>() }
    }
}
