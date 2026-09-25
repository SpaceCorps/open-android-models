package com.spacecorps.oam.sample

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spacecorps.oam.Agent
import com.spacecorps.oam.AgentConfiguration
import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentErrorCode
import com.spacecorps.oam.AgentEvent
import com.spacecorps.oam.LanguageModel
import com.spacecorps.oam.ModelAvailability
import com.spacecorps.oam.ToolChoice
import com.spacecorps.oam.ToolPolicy
import com.spacecorps.oam.mlkit.AvailabilityMonitor
import com.spacecorps.oam.mlkit.DownloadEvent
import com.spacecorps.oam.mlkit.GeminiNanoModel
import com.spacecorps.oam.toJsonString
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which model plays Mira. */
enum class Backend {
    /** Gemini Nano through ML Kit (AICore devices only). */
    GEMINI_NANO,

    /** [ScriptedMira]: works on any device or emulator. */
    SCRIPTED,
}

/** One row of the conversation. */
sealed interface ChatLine {
    /** Stable key for lists. */
    val id: Long

    /** What the player said. */
    data class Player(override val id: Long, val text: String) : ChatLine

    /** Mira's reply; [streaming] while it is being written. */
    data class Mira(override val id: Long, val text: String, val streaming: Boolean) : ChatLine

    /** A tool call: `name {arguments}`. */
    data class ToolCall(override val id: Long, val name: String, val arguments: String) : ChatLine

    /** A tool's result, shown under its call. */
    data class ToolResult(override val id: Long, val text: String, val isError: Boolean) : ChatLine

    /** Scene text and problems. */
    data class Notice(override val id: Long, val text: String, val isProblem: Boolean = false) : ChatLine
}

/**
 * Everything the screen shows.
 *
 * @property availability The active model's availability (`null` while checking).
 * @property download Progress of a running Gemini Nano download (`null` when none runs).
 */
data class TavernUiState(
    val backend: Backend = Backend.GEMINI_NANO,
    val availability: ModelAvailability? = null,
    val modelName: String? = null,
    val download: DownloadEvent? = null,
    val gold: Int = 0,
    val lines: List<ChatLine> = emptyList(),
    val responding: Boolean = false,
    val suggestions: List<String> = Tavern.SUGGESTIONS,
)

/**
 * Runs the tavern: owns the models, the agent playing Mira and the chat
 * transcript shown on screen.
 */
class TavernViewModel : ViewModel() {
    private val nanoDelegate = lazy { GeminiNanoModel() }
    private val nano: GeminiNanoModel by nanoDelegate
    private val state = MutableStateFlow(TavernUiState())
    private var nextId = 0L

    private var tavern = Tavern()
    private var agent: Agent? = null
    private var monitor: AvailabilityMonitor? = null
    private var sessionJobs: List<Job> = emptyList()
    private var turnJob: Job? = null
    private var downloadJob: Job? = null

    /** The screen state. */
    val ui: StateFlow<TavernUiState> = state.asStateFlow()

    init {
        start(Backend.GEMINI_NANO)
    }

    /** Switches the model playing Mira; starts a fresh evening. */
    fun selectBackend(backend: Backend) {
        if (backend != state.value.backend) start(backend)
    }

    /** Downloads Gemini Nano (or follows a download AICore already runs). */
    fun downloadModel() {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            try {
                nano.download().collect { event -> state.update { it.copy(download = event) } }
                monitor?.requestRefresh()
            } catch (error: AgentError) {
                notice("Download failed: ${error.message}", problem = true)
            } finally {
                state.update { it.copy(download = null) }
            }
        }
    }

    /** Says [text] to Mira. */
    fun say(text: String) {
        val line = text.trim()
        if (line.isEmpty() || state.value.responding) return
        val agent = agent ?: return
        if (state.value.backend == Backend.GEMINI_NANO && state.value.availability != ModelAvailability.Available) {
            notice("Gemini Nano is not ready on this device. Download it, or switch to the scripted model.", problem = true)
            return
        }
        append(ChatLine.Player(newId(), line))
        state.update { it.copy(responding = true, suggestions = emptyList()) }
        turnJob = viewModelScope.launch {
            var miraId: Long? = null
            try {
                agent.run(line, ToolPolicy(choice = ToolChoice.Explicit, maxToolRounds = 2)).events.collect { event ->
                    when (event) {
                        is AgentEvent.Text -> {
                            val id = miraId ?: newId().also { miraId = it }
                            upsert(ChatLine.Mira(id, event.text, streaming = true))
                        }
                        is AgentEvent.ToolCallStarted -> append(ChatLine.ToolCall(newId(), event.call.name, event.call.arguments.toJsonString()))
                        is AgentEvent.ToolCallCompleted -> append(
                            ChatLine.ToolResult(newId(), event.record.output.modelText.take(160), event.record.output.isError),
                        )
                        is AgentEvent.Completed -> {
                            val id = miraId ?: newId().also { miraId = it }
                            upsert(ChatLine.Mira(id, event.response.text, streaming = false))
                        }
                        else -> Unit
                    }
                }
            } catch (error: AgentError) {
                miraId?.let { id -> state.update { s -> s.copy(lines = s.lines.filterNot { it.id == id }) } }
                if (error.code != AgentErrorCode.CANCELLED) notice(explain(error), problem = true)
            } finally {
                // A stopped reply keeps what was written, without the cursor.
                state.update { s ->
                    s.copy(
                        lines = s.lines.map { if (it is ChatLine.Mira && it.id == miraId) it.copy(streaming = false) else it },
                        responding = false,
                        suggestions = Tavern.SUGGESTIONS,
                    )
                }
            }
        }
    }

    /** Stops Mira mid-reply. */
    fun cancelTurn() {
        turnJob?.cancel()
    }

    private fun start(backend: Backend) {
        turnJob?.cancel()
        sessionJobs.forEach { it.cancel() }
        monitor?.close()
        agent?.close()

        tavern = Tavern()
        val model: LanguageModel = when (backend) {
            Backend.GEMINI_NANO -> nano
            Backend.SCRIPTED -> ScriptedMira.model(tavern)
        }
        val newAgent = Agent(
            model = model,
            instructions = Tavern.MIRA_INSTRUCTIONS,
            tools = tavern.tools(),
            configuration = AgentConfiguration(userLabel = "Player", assistantLabel = "Mira", maxResponseTokens = 160),
        )
        agent = newAgent
        val newMonitor = AvailabilityMonitor(model, viewModelScope)
        monitor = newMonitor
        state.value = TavernUiState(backend = backend, modelName = model.capabilities.modelName, gold = tavern.gold.value)
        notice("Rain hammers the shutters of the Sleeping Stag. A fire crackles, and Mira wipes down the bar.")

        val currentTavern = tavern
        sessionJobs = listOf(
            viewModelScope.launch {
                newMonitor.state.collect { availability ->
                    state.update { it.copy(availability = availability, modelName = model.capabilities.modelName) }
                    if (availability == ModelAvailability.Available) newAgent.prewarm()
                }
            },
            viewModelScope.launch { currentTavern.gold.collect { gold -> state.update { it.copy(gold = gold) } } },
        )
    }

    private fun explain(error: AgentError): String = when (error.code) {
        AgentErrorCode.GUARDRAIL_VIOLATION -> "The on-device safety filter blocked that line. Try saying it differently."
        AgentErrorCode.RATE_LIMITED -> "Gemini Nano is rate-limited (quota, or the app is not in the foreground). ${error.message}"
        AgentErrorCode.MODEL_UNAVAILABLE -> "Gemini Nano is unavailable: ${error.message}"
        AgentErrorCode.CONTEXT_SIZE_EXCEEDED -> "The conversation no longer fits Gemini Nano's input limit."
        else -> error.message
    }

    private fun newId(): Long = nextId++

    private fun append(line: ChatLine) = state.update { it.copy(lines = it.lines + line) }

    private fun upsert(line: ChatLine) = state.update { s ->
        val index = s.lines.indexOfFirst { it.id == line.id }
        s.copy(lines = if (index < 0) s.lines + line else s.lines.toMutableList().also { it[index] = line })
    }

    private fun notice(text: String, problem: Boolean = false) = append(ChatLine.Notice(newId(), text, problem))

    override fun onCleared() {
        agent?.close()
        monitor?.close()
        if (nanoDelegate.isInitialized()) nano.close()
    }
}
