package com.spacecorps.oam.jni

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs [MessageEngine]s for an external host, the logic behind [OamJni]
 * without the JNI boundary (so it is unit-testable, and usable from Kotlin
 * hosts that want the same threading guarantees).
 *
 * Each bridge gets:
 * - an **inbound queue**: [send] never blocks; messages reach
 *   [MessageEngine.receive] in order, one at a time, on [engineDispatcher];
 * - an **outbound queue**: the engine's messages reach [deliver] in order,
 *   one at a time, on [deliveryDispatcher] (a background thread, never the
 *   caller of [send]).
 *
 * After [destroy] returns, [deliver] is never called again for that bridge
 * (it waits for a delivery in progress to finish), so the host may free
 * whatever its handle points to. Messages still queued are dropped. [destroy]
 * may be called from inside [deliver]; it must not be called while holding a
 * lock that [deliver] needs.
 *
 * @param deliver Receives each outgoing message with the bridge's host handle.
 * @param onError Reports engine and delivery failures (the bridge keeps running).
 * @param engineDispatcher Where [MessageEngine.receive] runs.
 * @param deliveryDispatcher Where [deliver] runs.
 */
public class BridgeHost(
    private val deliver: (handle: Long, line: String) -> Unit,
    private val onError: (message: String, error: Throwable?) -> Unit = { _, _ -> },
    private val engineDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val deliveryDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val nextId = AtomicLong(1)
    private val bridges = ConcurrentHashMap<Long, Bridge>()

    /** Number of live bridges. */
    public val bridgeCount: Int get() = bridges.size

    /**
     * Creates a bridge whose engine comes from [engineFactory].
     *
     * @param handle The host's opaque handle, passed back with every delivery.
     * @return The bridge id (positive), or 0 if the factory failed (reported to [onError]).
     */
    public fun create(handle: Long, engineFactory: (MessageSink) -> MessageEngine): Long {
        val id = nextId.getAndIncrement()
        val bridge = Bridge(id, handle)
        val engine = try {
            engineFactory(bridge)
        } catch (error: Exception) {
            bridge.discard()
            onError("Could not create bridge $id: ${error.message}", error)
            return 0L
        }
        bridge.start(engine)
        bridges[id] = bridge
        return id
    }

    /**
     * Queues [line] for the bridge's engine. Never blocks.
     *
     * @return False if there is no such bridge (unknown or destroyed).
     */
    public fun send(bridgeId: Long, line: String): Boolean = bridges[bridgeId]?.send(line) ?: false

    /**
     * Closes the bridge's engine and stops delivering its messages.
     *
     * @return False if there is no such bridge.
     */
    public fun destroy(bridgeId: Long): Boolean {
        val bridge = bridges.remove(bridgeId) ?: return false
        bridge.destroy()
        return true
    }

    /** Destroys every bridge. The host stays usable. */
    override fun close() {
        // ArrayList's constructor snapshots with toArray, which tolerates a concurrent destroy
        // (toList() reads size() first and can then throw NoSuchElementException).
        ArrayList(bridges.keys).forEach { destroy(it) }
    }

    private inner class Bridge(val id: Long, val handle: Long) : MessageSink {
        private val scope = CoroutineScope(SupervisorJob() + CoroutineName("oam-bridge-$id"))
        private val inbound = Channel<String>(Channel.UNLIMITED)
        private val outbound = Channel<String>(Channel.UNLIMITED)
        private val deliveryLock = Any()

        @Volatile
        private var open = true
        private var reportedDeliveryFailure = false

        override fun deliver(line: String) {
            if (open) outbound.trySend(line)
        }

        fun send(line: String): Boolean = open && inbound.trySend(line).isSuccess

        @Volatile
        private var engine: MessageEngine? = null

        fun start(engine: MessageEngine) {
            this.engine = engine
            scope.launch(engineDispatcher) {
                for (line in inbound) {
                    try {
                        engine.receive(line)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        onError("Bridge $id: the engine failed on a message: ${error.message}", error)
                    }
                }
            }
            scope.launch(deliveryDispatcher) {
                for (line in outbound) deliverNow(line)
            }
        }

        private fun deliverNow(line: String) {
            synchronized(deliveryLock) {
                if (!open) return
                try {
                    deliver(handle, line)
                } catch (error: Exception) {
                    reportDelivery(error)
                } catch (error: LinkageError) {
                    // For example UnsatisfiedLinkError when the host never registered nativeDeliver.
                    reportDelivery(error)
                }
            }
        }

        private fun reportDelivery(error: Throwable) {
            if (reportedDeliveryFailure) return
            reportedDeliveryFailure = true
            onError("Bridge $id: delivering a message to the host failed (further failures are not reported): $error", error)
        }

        fun destroy() {
            inbound.close()
            try {
                engine?.close()
            } catch (error: Exception) {
                onError("Bridge $id: closing the engine failed: ${error.message}", error)
            }
            // Waits for a delivery in progress; none starts afterwards.
            synchronized(deliveryLock) { open = false }
            outbound.close()
            scope.cancel()
        }

        fun discard() {
            open = false
            inbound.close()
            outbound.close()
            scope.cancel()
        }
    }
}
