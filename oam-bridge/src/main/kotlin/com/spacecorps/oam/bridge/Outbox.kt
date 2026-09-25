package com.spacecorps.oam.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume

/**
 * A value that is set once and awaited by any number of coroutines.
 * Unlike [CompletableDeferred], the waiting side can run a synchronous action
 * the moment its coroutine is cancelled (see [await]).
 */
internal class OneShot<T : Any> {
    private val lock = Any()
    private var value: T? = null
    private val waiters = ArrayList<(T) -> Unit>()

    /** Sets the value; returns false if it was already set. */
    fun resolve(value: T): Boolean {
        val toResume = synchronized(lock) {
            if (this.value != null) return false
            this.value = value
            waiters.toList().also { waiters.clear() }
        }
        toResume.forEach { it(value) }
        return true
    }

    /** The value, if set. */
    val current: T? get() = synchronized(lock) { value }

    /**
     * Waits for the value. If the waiting coroutine is cancelled first,
     * [onCancel] runs synchronously, on the cancelling thread, before the
     * cancellation propagates (so its side effects are ordered before
     * anything the canceller does next), and the [kotlinx.coroutines.CancellationException] is thrown.
     */
    suspend fun await(onCancel: (() -> Unit)? = null): T = suspendCancellableCoroutine { continuation ->
        val ready = synchronized(lock) {
            value ?: run {
                waiters += { resolved -> continuation.resume(resolved) }
                null
            }
        }
        if (ready != null) {
            continuation.resume(ready)
        } else if (onCancel != null) {
            continuation.invokeOnCancellation { onCancel() }
        }
    }
}

/**
 * Delivers outgoing lines one at a time, in the order they were queued, from
 * a single background coroutine: the host's callback is never called
 * concurrently with itself nor re-entered.
 *
 * @param deliver The host's callback.
 * @param onFailure Reports an exception thrown by [deliver] (the outbox keeps running).
 */
internal class Outbox(
    private val deliver: (String) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private sealed interface Item {
        class Line(val text: String) : Item

        class Action(val run: () -> Unit) : Item

        class Flush(val done: CompletableDeferred<Unit>) : Item
    }

    private val channel = Channel<Item>(Channel.UNLIMITED)

    /** Held while [deliver] runs, so [close] can wait for a delivery in progress. Reentrant: [close] may run inside [deliver]. */
    private val deliveryLock = ReentrantLock()

    @Volatile
    private var closed = false

    init {
        // The host callback may block (a pipe, a JNI call), so it runs on the IO pool.
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("oam-bridge-outbox")).launch {
            for (item in channel) handle(item)
        }
    }

    /** Queues one outgoing line. */
    fun send(line: String) {
        channel.trySend(Item.Line(line))
    }

    /** Runs [action] on the delivery coroutine, after everything queued before it (skipped once closed). */
    fun perform(action: () -> Unit) {
        channel.trySend(Item.Action(action))
    }

    /** Waits until everything queued so far has been delivered (or dropped by [close]). */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        if (channel.trySend(Item.Flush(done)).isFailure) return
        done.await()
    }

    /**
     * Stops delivery: once this returns, [deliver] is not called again. Waits
     * for a delivery in progress, unless called from inside [deliver] (then
     * the current delivery is the last).
     */
    fun close() {
        closed = true
        channel.close()
        deliveryLock.withLock { }
    }

    private fun handle(item: Item) {
        when (item) {
            is Item.Flush -> item.done.complete(Unit)
            is Item.Line -> guarded { deliver(item.text) }
            is Item.Action -> guarded(item.run)
        }
    }

    private inline fun guarded(block: () -> Unit) {
        deliveryLock.withLock {
            if (closed) return
            try {
                block()
            } catch (error: Exception) {
                onFailure(error)
            }
        }
    }
}
