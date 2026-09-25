package com.spacecorps.oam.bridge

import com.spacecorps.oam.AgentEvent
import com.spacecorps.oam.AgentResponse
import com.spacecorps.oam.AgentRun
import com.spacecorps.oam.ToolCall
import com.spacecorps.oam.ToolOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs an agent turn on behalf of this request and returns its response.
 *
 * - **Client tools:** each [AgentEvent.ToolCallRequested] becomes a `tool/call`
 *   request to the peer (`params` = [context] + `requestId` + `call`); the
 *   peer's response is submitted to the run. If the call times out or the turn
 *   ends first, a `tool/cancel` notification tells the peer to stop (its late
 *   response is ignored).
 * - **Streaming:** when [stream] is true, every event is sent as an
 *   [eventMethod] notification (`params` = [context] + `requestId` + `event`).
 * - **Cancellation:** cancelling the calling coroutine cancels the run, and
 *   this returns once the run has ended: it throws `cancelled` after the turn
 *   has rolled back, or returns the response of a turn that completed (and is
 *   in the history) before the cancellation reached it.
 *
 * All notifications and `tool/call` requests are queued before this returns,
 * so they reach the peer before the request's response.
 *
 * @param context Fields identifying the conversation, such as `{"session": "s1"}` or `{"npc": "gorm"}`.
 * @throws BridgeError when the turn fails (`cancelled` when it was cancelled before completing).
 */
public suspend fun BridgeRequest.drive(
    run: AgentRun,
    stream: Boolean,
    context: JsonObject,
    eventMethod: String = "session/event",
): AgentResponse = TurnDriver(engine, id?.value ?: JsonNull, context, stream, eventMethod).drive(run)

/**
 * Forwards client tool calls of one turn (an agent run or an NPC dialogue) to
 * the peer and cancels the ones the turn stops waiting for. Shared by the
 * session and NPC drivers.
 */
internal class ToolForwarder(
    private val engine: BridgeEngine,
    private val requestId: JsonElement,
    private val context: JsonObject,
) {
    /** Client tool calls awaiting the peer, by tool-call id. */
    private val pending = ConcurrentHashMap<String, ClientRequest>()

    /** `context` + `requestId` + [extra]. */
    fun params(vararg extra: Pair<String, JsonElement>): JsonObject {
        val members = LinkedHashMap<String, JsonElement>(context)
        members["requestId"] = requestId
        for ((key, value) in extra) members[key] = value
        return JsonObject(members)
    }

    /** Sends `tool/call` for [call]; the peer's answer (or failure) goes to [submit]. */
    fun forward(call: ToolCall, submit: (ToolOutput, String) -> Unit) {
        val request = engine.sendRequest("tool/call", params("call" to BridgeCoding.json(call)))
        pending[call.id] = request
        engine.scope.launch {
            val result = request.result()
            // Remove before submitting: the turn records the output (and reports it) as soon as it is submitted.
            if (pending.remove(call.id) != null) submit(BridgeCoding.toolOutput(result), call.id)
        }
    }

    /** The turn finished the call [callId] itself (it timed out): tell the peer to stop, if it still owes the answer. */
    fun completed(callId: String, reason: String) {
        pending.remove(callId)?.let { cancel(it, callId, reason) }
    }

    /** The turn ended: cancel every call still waiting for the peer. */
    fun finish() {
        // Snapshot with toArray (ArrayList's constructor): answers arriving meanwhile remove keys, and
        // Kotlin's toList()/sorted() would read size() and then iterate, throwing NoSuchElementException.
        val leftover = ArrayList(pending.keys).sorted().mapNotNull { key -> pending.remove(key)?.let { key to it } }
        for ((callId, request) in leftover) cancel(request, callId, "The turn ended before the tool finished.")
    }

    private fun cancel(request: ClientRequest, callId: String, reason: String) {
        if (!request.cancelIfPending()) return
        engine.notify(
            "tool/cancel",
            params("id" to JsonPrimitive(request.id), "callId" to JsonPrimitive(callId), "reason" to JsonPrimitive(reason)),
        )
    }
}

/**
 * Runs [collect] (which follows a turn to its end) so that cancelling the
 * calling request only *requests* the turn's cancellation, through [cancel],
 * and returns the turn's real outcome once it has ended: `cancelled` after it
 * has rolled back, or its result if it committed before the cancellation
 * reached it (it is in the history then, so it is reported as done).
 */
internal suspend fun <T> turnOutcome(cancel: () -> Unit, collect: suspend () -> T): Result<T> {
    // Detached from the request's job, but in its context (the WorkQueue slot, for markCommitted).
    val collecting = CoroutineScope(currentCoroutineContext().minusKey(Job)).async {
        try {
            Result.success(collect())
        } catch (error: Throwable) {
            Result.failure(BridgeError.normalizing(error))
        }
    }
    return try {
        collecting.await()
    } catch (_: CancellationException) {
        cancel()
        val outcome = withContext(NonCancellable) { collecting.await() }
        if (outcome.isSuccess) outcome else Result.failure(BridgeError.cancelled("The turn was cancelled."))
    }
}

/** Bridges one [AgentRun] to the peer. */
internal class TurnDriver(
    private val engine: BridgeEngine,
    requestId: JsonElement,
    context: JsonObject,
    private val stream: Boolean,
    private val eventMethod: String,
) {
    private val tools = ToolForwarder(engine, requestId, context)

    suspend fun drive(run: AgentRun): AgentResponse {
        val outcome = turnOutcome(run::cancel) {
            var response: AgentResponse? = null
            run.events.collect { event ->
                if (stream) BridgeCoding.json(event)?.let { engine.notify(eventMethod, tools.params("event" to it)) }
                when (event) {
                    is AgentEvent.ToolCallRequested -> tools.forward(event.call) { output, callId -> run.submit(output, callId) }
                    // Still waiting on the peer means the call timed out.
                    is AgentEvent.ToolCallCompleted -> tools.completed(event.record.call.id, event.record.output.modelText)
                    is AgentEvent.Completed -> {
                        // The turn is in the transcript now: report it even if a cancellation arrives before the response.
                        WorkQueue.markCommitted()
                        response = event.response
                    }
                    else -> Unit
                }
            }
            response ?: throw BridgeError.internalError("The turn ended without a response.")
        }
        tools.finish()
        return outcome.getOrThrow()
    }
}
