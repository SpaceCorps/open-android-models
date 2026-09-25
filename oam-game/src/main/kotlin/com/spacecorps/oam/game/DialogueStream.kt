package com.spacecorps.oam.game

import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentErrorCode
import com.spacecorps.oam.AgentRun
import com.spacecorps.oam.ToolCall
import com.spacecorps.oam.ToolOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * A streaming NPC turn: a [Flow] of [DialogueEvent]s plus a channel for
 * external tool outputs.
 *
 * ```kotlin
 * val stream = npc.talkStream("What's that glowing sword?")
 * stream.events.collect { event ->
 *     when (event) {
 *         is DialogueEvent.Emotion -> portrait.show(event.emotion)
 *         is DialogueEvent.LineDelta -> label.text += event.text
 *         is DialogueEvent.LineReset -> label.text = event.text
 *         is DialogueEvent.ExternalToolCall -> stream.submit(game.run(event.call), event.call.id)
 *         is DialogueEvent.Completed -> showOptions(event.turn.playerOptions)
 *         else -> Unit
 *     }
 * }
 * ```
 *
 * The turn runs even if nobody collects (queued behind the NPC's earlier
 * work), and buffers its events until they are collected. [events] can be
 * collected once; a failed turn throws [AgentError] from the flow.
 * Cancelling the coroutine that collects [events] (or awaits [turn])
 * cancels the turn, and so does [cancel]; a cancelled turn leaves the
 * history and memory unchanged.
 */
public class DialogueStream internal constructor() {
    private val channel = Channel<DialogueEvent>(Channel.UNLIMITED)
    private val collected = AtomicBoolean(false)
    private val lock = Any()
    private var run: AgentRun? = null
    private var cancelled = false
    private var finished = false

    /**
     * The turn's events, ending with [DialogueEvent.Completed] on success or
     * throwing [AgentError] on failure (including [AgentErrorCode.CANCELLED]).
     *
     * @throws IllegalStateException when collected a second time.
     */
    public val events: Flow<DialogueEvent> = flow {
        check(collected.compareAndSet(false, true)) { "DialogueStream.events can be collected only once." }
        try {
            for (event in channel) emit(event)
        } catch (error: CancellationException) {
            // Only a cancelled collector cancels the turn, not an early stop such as first().
            if (!currentCoroutineContext().isActive) this@DialogueStream.cancel()
            throw error
        }
    }

    /**
     * Supplies the output of an external tool call announced by
     * [DialogueEvent.ExternalToolCall].
     *
     * @return False if no such call is pending.
     */
    public fun submit(output: ToolOutput, callId: String): Boolean {
        // The agent registers an external call before announcing it, so an
        // answer sent from the announcement can never arrive too early.
        val current = synchronized(lock) { run } ?: return false
        return current.submit(output, callId)
    }

    /** External tool calls waiting for output. */
    public val pendingToolCalls: List<ToolCall> get() = synchronized(lock) { run }?.pendingToolCalls.orEmpty()

    /** True once [cancel] was called. */
    public val isCancelled: Boolean get() = synchronized(lock) { cancelled }

    /** True once the turn has completed, failed or been cancelled. */
    public val isFinished: Boolean get() = synchronized(lock) { finished }

    /**
     * Cancels the turn, also if it has not started yet. The events then throw
     * [AgentError] with [AgentErrorCode.CANCELLED]; the history and memory are
     * unchanged. A turn that has already completed is unaffected.
     */
    public fun cancel() {
        val current = synchronized(lock) {
            cancelled = true
            run
        }
        if (current != null) {
            current.cancel()
        } else {
            // Still queued behind another turn or background work: end now.
            fail(AgentError(AgentErrorCode.CANCELLED, "The turn was cancelled."))
        }
    }

    /**
     * Collects the events and returns the finished turn. Call it at most
     * once, and not together with collecting [events].
     *
     * @param externalTools Runs each external tool call; its result (or its
     *   exception, as [ToolOutput.Error]) is submitted automatically. Without
     *   it, external calls receive an error output.
     * @throws AgentError if the turn fails or is cancelled.
     */
    public suspend fun turn(externalTools: (suspend (ToolCall) -> ToolOutput)? = null): DialogueTurn {
        val handlers = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
        try {
            var result: DialogueTurn? = null
            events.collect { event ->
                when (event) {
                    is DialogueEvent.ExternalToolCall -> {
                        val call = event.call
                        if (externalTools == null) {
                            submit(ToolOutput.Error("No handler is registered for external tool '${call.name}'."), call.id)
                        } else {
                            handlers.launch { submit(runHandler(externalTools, call), call.id) }
                        }
                    }
                    is DialogueEvent.Completed -> result = event.turn
                    else -> Unit
                }
            }
            return result ?: throw AgentError(AgentErrorCode.GENERATION_FAILED, "The dialogue turn ended without a reply.")
        } catch (error: CancellationException) {
            cancel()
            throw error
        } catch (error: Throwable) {
            throw AgentError.from(error)
        } finally {
            handlers.cancel()
        }
    }

    // MARK: Producer side

    /** Associates the running agent turn. Returns false (and cancels [run]) if the stream was already cancelled. */
    internal fun attach(run: AgentRun): Boolean {
        val wasCancelled = synchronized(lock) {
            this.run = run
            cancelled
        }
        if (wasCancelled) run.cancel()
        return !wasCancelled
    }

    internal fun emit(event: DialogueEvent) {
        channel.trySend(event)
    }

    internal fun finish(turn: DialogueTurn) {
        if (!markFinished()) return
        channel.trySend(DialogueEvent.Completed(turn))
        channel.close()
    }

    internal fun fail(error: AgentError) {
        if (!markFinished()) return
        channel.close(error)
    }

    private fun markFinished(): Boolean = synchronized(lock) {
        if (finished) return@synchronized false
        finished = true
        true
    }

    private suspend fun runHandler(handler: suspend (ToolCall) -> ToolOutput, call: ToolCall): ToolOutput =
        try {
            handler(call)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ToolOutput.Error(error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "Unknown error")
        }
}
