package com.spacecorps.oam.readme

import com.spacecorps.oam.*
import com.spacecorps.oam.bridge.*
import com.spacecorps.oam.jni.*
import com.spacecorps.oam.mlkit.*

// The README's Android examples, unchanged. They need Gemini Nano (an AICore
// device), so they are compiled here but never run. oam-game's
// ReadmeExamplesTest checks that they match the README, so change both together.

private val shown = ArrayList<String>()

private fun showProgress(fraction: Float?) {
    shown += "progress: $fraction"
}

private fun showMessage(text: String) {
    shown += text
}

private val menuTool = AgentTool.local("check_menu", "Look up the tavern's menu and prices.") { ToolOutput.of(mapOf("ale" to 2)) }

/** "Gemini Nano in an Android app (oam-mlkit)". */
internal suspend fun geminiNanoInAnAndroidApp() {
    val nano = GeminiNanoModel()  // or GeminiNanoModel(GeminiNanoOptions(preference = GeminiNanoOptions.Preference.FAST))

    suspend fun prepare(): Boolean = when (val availability = nano.availability()) {
        ModelAvailability.Available -> true
        ModelAvailability.Downloadable, is ModelAvailability.Downloading -> {
            nano.download().collect { event ->
                if (event is DownloadEvent.Progress) showProgress(event.fraction)  // null when the size is unknown
            }
            true
        }
        is ModelAvailability.Unavailable -> {
            showMessage("Gemini Nano is not available: ${availability.reason}")  // e.g. device_not_eligible
            false
        }
    }
    if (!prepare()) return

    // Then use it like any LanguageModel, while the app is in the foreground.
    val mira = Agent(
        nano,
        instructions = "You are Mira, the innkeeper of the Sleeping Stag. Reply in at most two sentences.",
        tools = listOf(menuTool),
    )
    try {
        println(mira.respond("Evening! What have you got that's warm?").text)
    } catch (error: AgentError) {
        when (error.code) {
            AgentErrorCode.RATE_LIMITED -> showMessage("Busy, over quota or in the background. Retry later.")
            AgentErrorCode.GUARDRAIL_VIOLATION -> showMessage("Blocked by the safety filters.")
            else -> showMessage("${error.code.wireName}: ${error.message} (ML Kit code ${error.mlKitErrorCode})")
        }
    }
}

/** "Native hosts and game engines": replacing OamJni's engine factory. */
internal fun configureOamJni() {
    OamJni.engineFactory = BridgeEngineFactory(
        systemModel = { GeminiNanoModel(GeminiNanoOptions(preference = GeminiNanoOptions.Preference.FAST)) },
        configure = { model -> BridgeConfiguration(systemModel = model, maxSessions = 16) },
    )
}

/** "Native hosts and game engines": the bridge in process, without JNI. */
internal fun bridgeInProcess() {
    val engine = BridgeEngine(BridgeConfiguration(systemModel = GeminiNanoModel())) { line -> println(line) }
    engine.receive("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""")
}
