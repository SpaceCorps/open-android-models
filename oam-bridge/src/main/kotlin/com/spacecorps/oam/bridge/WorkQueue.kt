package com.spacecorps.oam.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Runs operations one after another in the order they were scheduled. Each
 * session and each NPC has one, so pipelined requests apply in arrival order.
 *
 * **Cancellation and results agree.** A cancelled operation must not report
 * `cancelled` and then change state anyway (or the reverse):
 *
 * - Work that changes state calls [commit] right before the change. It
 *   throws `cancelled` if the operation was already cancelled; otherwise the
 *   operation passes its point of no return: later cancellation no longer
 *   applies to it ([cancelAll] skips it) and its result is reported as is.
 * - Work whose change happens elsewhere (a model turn committing its
 *   transcript) calls [markCommitted] once the change has happened.
 * - Work that returns successfully after being cancelled without reaching a
 *   commit point is reported as `cancelled`.
 *
 * @param scope Where the operations run.
 */
internal class WorkQueue(private val scope: CoroutineScope) {
    private class Item(val job: Job) {
        var cancelled = false
        var committed = false
    }

    /** Identifies the operation running in the current coroutine. */
    private class Slot(val queue: WorkQueue, val token: Long) : AbstractCoroutineContextElement(Slot) {
        companion object Key : CoroutineContext.Key<Slot>
    }

    private val lock = Any()
    private var tail: CompletableDeferred<Unit>? = null
    private val items = HashMap<Long, Item>()
    private var nextToken = 0L

    /**
     * Queues [work] behind earlier work and returns a reply that completes
     * with its result. The queue position is taken now, so call this from the
     * (ordered) method handler, not from inside deferred work.
     */
    fun schedule(work: suspend () -> JsonElement): BridgeReply {
        val outcome = OneShot<Result<JsonElement>>()
        val scheduled = synchronized(lock) {
            val token = nextToken++
            val previous = tail
            // Completes once this operation *and every earlier one* are done, even
            // when this one is cancelled before it starts, so nothing can overtake.
            val done = CompletableDeferred<Unit>()
            tail = done
            val job = scope.launch(Slot(this, token), start = CoroutineStart.LAZY) {
                if (previous != null) withContext(NonCancellable) { previous.await() }
                var result: Result<JsonElement> = if (!currentCoroutineContext().isActive) {
                    Result.failure(BridgeError.cancelled("The request was cancelled before it started."))
                } else {
                    try {
                        Result.success(work())
                    } catch (error: Throwable) {
                        Result.failure(BridgeError.normalizing(error))
                    }
                }
                val item = synchronized(lock) { items.remove(token) }
                if (result.isSuccess && item != null && item.cancelled && !item.committed) {
                    result = Result.failure(BridgeError.cancelled())
                }
                outcome.resolve(result)
            }
            items[token] = Item(job)
            job.invokeOnCompletion {
                synchronized(lock) { items.remove(token) }
                // Only takes effect when the body never ran (cancelled before it started).
                outcome.resolve(Result.failure(BridgeError.cancelled("The request was cancelled before it started.")))
                if (previous == null) done.complete(Unit) else previous.invokeOnCompletion { done.complete(Unit) }
            }
            token to job
        }
        val (token, job) = scheduled
        job.start()
        return BridgeReply.Deferred {
            // If the waiting request is cancelled (shutdown, a cancelled in-process call), cancel the
            // operation but still report its real outcome: it may already have committed.
            val result = try {
                outcome.await()
            } catch (error: CancellationException) {
                cancel(token)
                withContext(NonCancellable) { outcome.await() }
            }
            result.getOrThrow()
        }
    }

    /**
     * Cancels running and queued work that has not passed its commit point.
     *
     * @return How many operations were cancelled.
     */
    fun cancelAll(): Int {
        val jobs = synchronized(lock) {
            items.values.filter { !it.committed && !it.cancelled }.onEach { it.cancelled = true }.map { it.job }
        }
        jobs.forEach { it.cancel() }
        return jobs.size
    }

    /** Operations running or waiting. */
    val pendingOperations: Int get() = synchronized(lock) { items.size }

    private fun cancel(token: Long) {
        val job = synchronized(lock) {
            val item = items[token]?.takeIf { !it.committed } ?: return
            item.cancelled = true
            item.job
        }
        job.cancel()
    }

    companion object {
        /**
         * Marks the current operation's point of no return: throws `cancelled`
         * if it was cancelled, and otherwise makes it immune to later
         * cancellation. Call right before changing state. Outside scheduled
         * work it only checks whether the coroutine was cancelled.
         *
         * @throws BridgeError `cancelled`.
         */
        suspend fun commit() {
            val context = currentCoroutineContext()
            val slot = context[Slot]
            if (slot == null) {
                if (!context.isActive) throw BridgeError.cancelled()
                return
            }
            val cancelled = synchronized(slot.queue.lock) {
                val item = slot.queue.items[slot.token] ?: return
                if (!item.cancelled) item.committed = true
                item.cancelled
            }
            if (cancelled) throw BridgeError.cancelled()
        }

        /**
         * Records that the current operation's change has already happened (a
         * turn completed and is in the transcript), so it is reported as
         * succeeded even if it is cancelled meanwhile.
         */
        suspend fun markCommitted() {
            val slot = currentCoroutineContext()[Slot] ?: return
            synchronized(slot.queue.lock) { slot.queue.items[slot.token]?.committed = true }
        }
    }
}
