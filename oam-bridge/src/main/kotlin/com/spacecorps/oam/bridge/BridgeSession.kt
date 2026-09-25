package com.spacecorps.oam.bridge

import com.spacecorps.oam.Agent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * An agent owned by a bridge client, addressed by id.
 *
 * Turn-affecting operations (`session/respond`, `reset`, `compact`,
 * `setInstructions`, `setTools`, `setContextNote`) are queued with
 * [schedule] and run one after another in arrival order, so a client can
 * pipeline them. [cancelAll] cancels the running and queued ones.
 *
 * @property id The session id.
 * @property agent The agent.
 * @property modelKind The model kind (`system`, `scripted` or a custom type).
 * @property toolTimeout Time limit applied to client tools added later through `session/setTools`.
 * @property createdAt When the session was created.
 * @param context Where scheduled operations run.
 */
public class BridgeSession(
    public val id: String,
    public val agent: Agent,
    public val modelKind: String,
    public val toolTimeout: Duration?,
    public val createdAt: Instant = Instant.now(),
    context: CoroutineContext = Dispatchers.Default,
) : AutoCloseable {
    private val scope = CoroutineScope(context + SupervisorJob(context[Job]))
    private val queue = WorkQueue(scope)

    /**
     * Queues [work] behind this session's earlier work and returns a reply
     * that completes with its result. The queue position is taken now, so call
     * this from the (ordered) method handler, not from inside deferred work.
     *
     * Cancellation ([cancelAll], `session/delete`, `shutdown`) cancels the
     * work's coroutine. Work that finishes successfully after it was cancelled
     * is reported as `cancelled`, so call [commit] right before the work
     * changes anything.
     */
    public fun schedule(work: suspend () -> JsonElement): BridgeReply = queue.schedule(work)

    /**
     * Cancels running and queued work.
     *
     * @return How many operations were cancelled.
     */
    public fun cancelAll(): Int = queue.cancelAll()

    /** Operations running or waiting on this session. */
    public val pendingOperations: Int get() = queue.pendingOperations

    /** A summary for `session/list`: `{session, model, instructions?, tools, busy, pendingOperations, entries, createdAt}`. */
    public val summary: JsonObject
        get() {
            val members = linkedMapOf<String, JsonElement>(
                "session" to JsonPrimitive(id),
                "model" to JsonPrimitive(modelKind),
            )
            agent.instructions?.let { members["instructions"] = JsonPrimitive(it) }
            members["tools"] = JsonArray(agent.tools.map { JsonPrimitive(it.name) })
            val pending = pendingOperations
            members["busy"] = JsonPrimitive(pending > 0)
            members["pendingOperations"] = JsonPrimitive(pending)
            members["entries"] = JsonPrimitive(agent.history.size)
            members["createdAt"] = JsonPrimitive(timestamp(createdAt))
            return JsonObject(members)
        }

    /** Cancels all work and closes the agent. */
    override fun close() {
        cancelAll()
        agent.close()
        scope.cancel()
    }

    public companion object {
        /**
         * Inside [schedule] work: marks the point of no return, right before
         * the work changes state. Throws `cancelled` if the operation was
         * already cancelled (then change nothing); otherwise later cancellation
         * no longer applies to it and its result is reported as is. Outside
         * scheduled work it only checks whether the coroutine was cancelled.
         *
         * @throws BridgeError `cancelled`.
         */
        public suspend fun commit() {
            WorkQueue.commit()
        }

        /** An ISO 8601 timestamp in whole seconds, as used by `createdAt`. */
        public fun timestamp(instant: Instant): String = instant.truncatedTo(ChronoUnit.SECONDS).toString()
    }
}
