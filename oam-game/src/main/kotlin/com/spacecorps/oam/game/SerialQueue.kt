package com.spacecorps.oam.game

import com.spacecorps.oam.AgentError
import com.spacecorps.oam.AgentErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs suspending jobs one at a time, in submission order, in [scope].
 *
 * Used by [NPC] so a conversation turn, its bookkeeping (memory,
 * compaction) and the next turn never interleave. Each job's completion
 * token completes only once the job *and its predecessor* are done, even
 * when the job is cancelled before it starts, so nothing can overtake a
 * running job.
 */
internal class SerialQueue(private val scope: CoroutineScope) {
    private val lock = Any()
    private var tail: CompletableDeferred<Unit>? = null

    /** Runs [work] after everything enqueued before it. */
    fun enqueue(work: suspend () -> Unit): Job = synchronized(lock) {
        val previous = tail
        val done = CompletableDeferred<Unit>()
        tail = done
        val job = scope.launch {
            previous?.await()
            work()
        }
        job.invokeOnCompletion {
            if (previous == null) done.complete(Unit) else previous.invokeOnCompletion { done.complete(Unit) }
        }
        job
    }

    /**
     * Runs [work] after everything enqueued so far and returns its result.
     * Cancelling the caller cancels [work]; if the queue's scope is shut down
     * first, this throws [AgentError] with [AgentErrorCode.CANCELLED].
     */
    suspend fun <T> perform(work: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        val job = enqueue {
            try {
                result.complete(work())
            } catch (error: CancellationException) {
                // Never hand a cancellation to a caller that was not cancelled itself.
                result.completeExceptionally(AgentError(AgentErrorCode.CANCELLED, "The work was cancelled.", cause = error))
                throw error
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) result.completeExceptionally(AgentError(AgentErrorCode.CANCELLED, "The work was cancelled.", cause = cause))
        }
        return try {
            result.await()
        } catch (error: CancellationException) {
            job.cancel()
            throw error
        }
    }

    /** Waits until every job enqueued so far, and any enqueued meanwhile, has finished. */
    suspend fun waitUntilIdle() {
        while (true) {
            val current = synchronized(lock) { tail } ?: return
            current.await()
            if (synchronized(lock) { tail } === current) return
        }
    }
}
