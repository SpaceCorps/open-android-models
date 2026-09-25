package com.spacecorps.oam

import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * A running agent turn: a [Flow] of [AgentEvent]s plus a channel for
 * external tool outputs.
 *
 * ```kotlin
 * val run = agent.run("Open the gate for me")
 * run.events.collect { event ->
 *     when (event) {
 *         is AgentEvent.Text -> print(event.delta)
 *         is AgentEvent.ToolCallRequested -> run.submit(game.perform(event.call), event.call.id)
 *         is AgentEvent.Completed -> println("\n${event.response.toolCalls.size} tool calls")
 *         else -> Unit
 *     }
 * }
 * ```
 *
 * The turn starts as soon as it is created (queued behind earlier turns of
 * the same agent) and buffers its events until they are collected. [events]
 * can be collected once; a failed turn throws [AgentError] from the flow.
 *
 * Cancelling the coroutine that collects [events] (or awaits [response])
 * cancels the turn; so does [cancel]. A collector that merely stops early
 * (for example with `first()`) does not: the turn keeps running and still
 * waits for any external tool output it requested.
 */
public class AgentRun internal constructor(
    /** The tool policy of this turn. */
    public val policy: ToolPolicy,
) {
    private class Pending(val call: ToolCall, val output: CompletableDeferred<ToolOutput>)

    private val channel = Channel<AgentEvent>(Channel.UNLIMITED)
    private val collected = AtomicBoolean(false)
    private val lock = Any()
    private val pending = LinkedHashMap<String, Pending>()
    private val records = ArrayList<ToolRecord>()
    private val steps = ArrayList<ModelStep>()
    private var job: Job? = null
    private var started = false
    private var finished = false
    private var cancelled = false
    private var committed = false

    /**
     * The turn's events, ending with [AgentEvent.Completed] on success or
     * throwing [AgentError] on failure (including [AgentErrorCode.CANCELLED]).
     *
     * @throws IllegalStateException when collected a second time.
     */
    public val events: Flow<AgentEvent> = flow {
        check(collected.compareAndSet(false, true)) { "AgentRun.events can be collected only once." }
        try {
            for (event in channel) emit(event)
        } catch (error: CancellationException) {
            // Only a cancelled collector cancels the turn, not an early stop such as first().
            if (!currentCoroutineContext().isActive) this@AgentRun.cancel()
            throw error
        }
    }

    /**
     * Supplies the output of an external tool call announced by
     * [AgentEvent.ToolCallRequested].
     *
     * @return False if no such call is pending (unknown id, already answered, timed out or cancelled).
     */
    public fun submit(output: ToolOutput, callId: String): Boolean {
        val entry = synchronized(lock) { pending.remove(callId) } ?: return false
        return entry.output.complete(output)
    }

    /** External tool calls waiting for output. */
    public val pendingToolCalls: List<ToolCall> get() = synchronized(lock) { pending.values.map { it.call } }

    /** True once the turn has completed, failed or been cancelled. */
    public val isFinished: Boolean get() = synchronized(lock) { finished }

    /**
     * Cancels the turn. Pending external calls resolve with an error output,
     * and the turn fails with [AgentErrorCode.CANCELLED], leaving no trace in
     * the agent's history. A turn that has already completed is unaffected.
     */
    public fun cancel() {
        val runningJob = synchronized(lock) {
            if (committed) return
            cancelled = true
            job
        }
        // Stop the turn before resolving its pending external calls, so it cannot resume with
        // their error output and run further steps or tools.
        runningJob?.cancel()
        cancelPending("The turn was cancelled.")
        // A turn still queued behind another ends now; a running one ends once it has rolled back.
        finishIfNotStarted(AgentError(AgentErrorCode.CANCELLED, "The turn was cancelled."))
    }

    /**
     * Collects the events and returns the final response.
     *
     * Must be called at most once, and not together with collecting [events].
     *
     * @param externalTools Runs each external tool call; its result (or its
     *   exception, as [ToolOutput.Error]) is submitted automatically. Without
     *   it, external calls receive an error output.
     * @throws AgentError if the turn fails or is cancelled.
     */
    public suspend fun response(externalTools: (suspend (ToolCall) -> ToolOutput)? = null): AgentResponse {
        val handlers = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
        try {
            var response: AgentResponse? = null
            events.collect { event ->
                when (event) {
                    is AgentEvent.ToolCallRequested -> {
                        val call = event.call
                        if (externalTools == null) {
                            submit(ToolOutput.Error("No handler is registered for external tool '${call.name}'."), call.id)
                        } else {
                            handlers.launch { submit(runHandler(externalTools, call), call.id) }
                        }
                    }
                    is AgentEvent.Completed -> response = event.response
                    else -> Unit
                }
            }
            return response ?: throw AgentError(AgentErrorCode.GENERATION_FAILED, "The turn ended without a response.")
        } catch (error: CancellationException) {
            this@AgentRun.cancel()
            throw error
        } catch (error: Throwable) {
            throw AgentError.from(error)
        } finally {
            handlers.cancel()
        }
    }

    // MARK: Turn side (internal)

    internal fun attach(job: Job) {
        val cancelNow = synchronized(lock) {
            this.job = job
            cancelled
        }
        if (cancelNow) job.cancel()
    }

    internal fun emit(event: AgentEvent) {
        if (event is AgentEvent.ModelStep) synchronized(lock) { steps += event.step }
        channel.trySend(event)
    }

    internal fun record(record: ToolRecord) {
        synchronized(lock) { records += record }
        emit(AgentEvent.ToolCallCompleted(record))
    }

    internal val recordsSnapshot: List<ToolRecord> get() = synchronized(lock) { records.toList() }

    internal val stepsSnapshot: List<ModelStep> get() = synchronized(lock) { steps.toList() }

    internal val stepCount: Int get() = synchronized(lock) { steps.size }

    /** Marks the turn as running. Returns false if it already ended (cancelled while queued). */
    internal fun markStarted(): Boolean = synchronized(lock) {
        if (finished) return false
        started = true
        true
    }

    /**
     * The turn's point of no return: runs [apply] (adding the turn to the
     * history) unless the turn was cancelled, atomically with [cancel]. A
     * turn is either cancelled or committed, never both.
     *
     * @return False if the turn was cancelled; [apply] did not run.
     */
    internal fun commit(apply: () -> Unit): Boolean = synchronized(lock) {
        if (cancelled) return false
        apply()
        committed = true
        true
    }

    internal fun finish(result: Result<AgentResponse>) {
        val first = synchronized(lock) {
            if (finished) return@synchronized false
            finished = true
            true
        }
        if (!first) return
        cancelPending("The turn ended before the tool output arrived.")
        result.fold(
            onSuccess = {
                channel.trySend(AgentEvent.Completed(it))
                channel.close()
            },
            onFailure = { channel.close(AgentError.from(it)) },
        )
    }

    internal fun finishIfNotStarted(error: AgentError): Boolean {
        val shouldFinish = synchronized(lock) { !started && !finished }
        if (shouldFinish) finish(Result.failure(error))
        return shouldFinish
    }

    /**
     * Registers an external call, announces it, then waits for [submit]. Registering
     * first means a host that answers straight from the announcement never misses it.
     */
    internal suspend fun awaitExternal(call: ToolCall, timeout: Duration?): ToolOutput {
        val output = CompletableDeferred<ToolOutput>()
        val immediate = synchronized(lock) {
            when {
                finished -> ToolOutput.Error("The turn has ended.")
                cancelled -> ToolOutput.Error("The tool call was cancelled.")
                else -> {
                    pending[call.id] = Pending(call, output)
                    null
                }
            }
        }
        if (immediate != null) return immediate
        emit(AgentEvent.ToolCallRequested(call))
        try {
            return if (timeout == null) {
                output.await()
            } else {
                withTimeoutOrNull(timeout) { output.await() } ?: ToolOutput.Error("Tool '${call.name}' timed out after $timeout.")
            }
        } finally {
            synchronized(lock) { pending.remove(call.id) }
        }
    }

    private fun cancelPending(reason: String) {
        val entries = synchronized(lock) {
            val all = pending.values.toList()
            pending.clear()
            all
        }
        entries.forEach { it.output.complete(ToolOutput.Error(reason)) }
    }

    private suspend fun runHandler(handler: suspend (ToolCall) -> ToolOutput, call: ToolCall): ToolOutput =
        try {
            handler(call)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ToolOutput.Error(describe(error))
        }

    internal companion object {
        fun describe(error: Throwable): String = error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "Unknown error"
    }
}
