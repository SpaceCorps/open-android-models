package com.spacecorps.oam.jni

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** An engine that echoes each message back as `echo:<line>`. */
internal class EchoEngine(private val output: MessageSink) : MessageEngine {
    val received: MutableList<String> = Collections.synchronizedList(ArrayList())
    val closed = AtomicBoolean(false)

    override fun receive(line: String) {
        received += line
        output.deliver("echo:$line")
    }

    override fun close() {
        closed.set(true)
    }
}

class BridgeHostTest {
    private data class Delivery(val handle: Long, val line: String, val thread: Thread)

    private val deliveries = CopyOnWriteArrayList<Delivery>()
    private val errors = CopyOnWriteArrayList<String>()
    private val host = BridgeHost(
        deliver = { handle, line -> deliveries += Delivery(handle, line, Thread.currentThread()) },
        onError = { message, _ -> errors += message },
    )

    @AfterEach
    fun tearDown() {
        host.close()
    }

    private fun awaitDeliveries(count: Int, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (deliveries.size < count && System.currentTimeMillis() < deadline) Thread.sleep(2)
        assertTrue(deliveries.size >= count, "expected $count deliveries, got ${deliveries.size}")
    }

    @Test
    fun messagesRoundTripInOrderOnABackgroundThread() {
        val engines = ArrayList<EchoEngine>()
        val id = host.create(77) { sink -> EchoEngine(sink).also { engines += it } }
        assertTrue(id > 0)
        assertEquals(1, host.bridgeCount)

        repeat(500) { assertTrue(host.send(id, "m$it")) }
        awaitDeliveries(500)

        assertEquals((0 until 500).map { "m$it" }, engines.single().received.toList())
        assertEquals((0 until 500).map { "echo:m$it" }, deliveries.map { it.line })
        assertTrue(deliveries.all { it.handle == 77L })
        assertTrue(deliveries.none { it.thread == Thread.currentThread() })
    }

    @Test
    fun bridgesAreIndependent() {
        val a = host.create(1) { EchoEngine(it) }
        val b = host.create(2) { EchoEngine(it) }
        assertNotEquals(a, b)
        host.send(a, "to-a")
        host.send(b, "to-b")
        awaitDeliveries(2)
        assertEquals(setOf(1L to "echo:to-a", 2L to "echo:to-b"), deliveries.map { it.handle to it.line }.toSet())
    }

    @Test
    fun outputWrittenDuringCreationIsDelivered() {
        host.create(5) { sink ->
            sink.deliver("hello")
            EchoEngine(sink)
        }
        awaitDeliveries(1)
        assertEquals("hello", deliveries.single().line)
    }

    @Test
    fun unknownAndDestroyedBridgesRejectMessages() {
        assertFalse(host.send(999, "x"))
        assertFalse(host.destroy(999))
        val engine = arrayOfNulls<EchoEngine>(1)
        val id = host.create(1) { EchoEngine(it).also { e -> engine[0] = e } }
        assertTrue(host.destroy(id))
        assertTrue(engine[0]!!.closed.get())
        assertFalse(host.send(id, "late"))
        assertFalse(host.destroy(id))
        assertEquals(0, host.bridgeCount)
    }

    @Test
    fun noDeliveryAfterDestroyReturns() {
        val running = AtomicBoolean(true)
        val id = host.create(9) { sink ->
            object : MessageEngine {
                val writer = thread {
                    var n = 0
                    while (running.get()) {
                        sink.deliver("tick ${n++}")
                        Thread.sleep(1)
                    }
                }

                override fun receive(line: String) {}

                override fun close() {}
            }
        }
        awaitDeliveries(10)
        host.destroy(id)
        val countAtDestroy = deliveries.size
        Thread.sleep(100)
        running.set(false)
        assertEquals(countAtDestroy, deliveries.size)
    }

    @Test
    fun destroyWaitsForADeliveryInProgress() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val blocking = BridgeHost(deliver = { _, _ ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            finished.set(true)
        })
        val id = blocking.create(1) { sink -> EchoEngine(sink) }
        blocking.send(id, "x")
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val destroyed = AtomicBoolean(false)
        val destroyer = thread {
            blocking.destroy(id)
            destroyed.set(true)
        }
        Thread.sleep(100)
        assertFalse(destroyed.get(), "destroy returned while a delivery was in progress")
        release.countDown()
        destroyer.join(5_000)
        assertTrue(destroyed.get())
        assertTrue(finished.get())
    }

    @Test
    fun destroyFromInsideDeliveryDoesNotDeadlock() {
        val done = CountDownLatch(1)
        lateinit var reentrant: BridgeHost
        val ids = LongArray(1)
        reentrant = BridgeHost(deliver = { _, _ ->
            reentrant.destroy(ids[0])
            done.countDown()
        })
        ids[0] = reentrant.create(1) { EchoEngine(it) }
        reentrant.send(ids[0], "bye")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(0, reentrant.bridgeCount)
    }

    @Test
    fun aFailingFactoryReturnsZero() {
        val id = host.create(1) { throw IllegalStateException("no model") }
        assertEquals(0L, id)
        assertEquals(0, host.bridgeCount)
        assertTrue(errors.single().contains("no model"))
    }

    @Test
    fun engineFailuresAreReportedAndTheBridgeKeepsRunning() {
        val id = host.create(3) { sink ->
            object : MessageEngine {
                override fun receive(line: String) {
                    if (line == "bad") throw IllegalArgumentException("cannot parse")
                    sink.deliver("ok:$line")
                }

                override fun close() {}
            }
        }
        host.send(id, "bad")
        host.send(id, "good")
        awaitDeliveries(1)
        assertEquals("ok:good", deliveries.single().line)
        assertTrue(errors.single().contains("cannot parse"))
    }

    @Test
    fun deliveryFailuresAreReportedOnce() {
        val attempts = AtomicInteger()
        val failing = BridgeHost(
            deliver = { _, _ ->
                attempts.incrementAndGet()
                throw UnsatisfiedLinkError("nativeDeliver")
            },
            onError = { message, _ -> errors += message },
        )
        val id = failing.create(1) { EchoEngine(it) }
        repeat(3) { failing.send(id, "m$it") }
        val deadline = System.currentTimeMillis() + 5_000
        while (attempts.get() < 3 && System.currentTimeMillis() < deadline) Thread.sleep(2)
        assertEquals(3, attempts.get())
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("UnsatisfiedLinkError"))
        failing.close()
    }

    @Test
    fun closeDestroysEveryBridge() {
        val engines = ArrayList<EchoEngine>()
        repeat(3) { host.create(it.toLong()) { sink -> EchoEngine(sink).also { e -> engines += e } } }
        host.close()
        assertEquals(0, host.bridgeCount)
        assertTrue(engines.all { it.closed.get() })
    }
}
