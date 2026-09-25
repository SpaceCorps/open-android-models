package com.spacecorps.oam.bridge

import com.spacecorps.oam.LanguageModel
import com.spacecorps.oam.stringValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A transport-agnostic JSON-RPC 2.0 engine that lets game engines and other
 * languages drive agents, NPCs, decisions and world state. It speaks
 * open-apple-models' bridge protocol v1.0 unchanged (see `docs/PROTOCOL.md`),
 * so a host implements the protocol once and injects the Apple backend
 * (`oam_bridge_*` C ABI) or this one (`OamJni` in oam-mlkit) per platform.
 *
 * ```kotlin
 * val engine = BridgeEngine(BridgeConfiguration(systemModel = GeminiNanoModel())) { line -> println(line) }
 * engine.receive("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""")
 * ```
 *
 * **Ordering.** Incoming requests are handled in arrival order: each handler
 * validates its input and does its order-sensitive part before the next
 * message is looked at, then long work (model turns) continues concurrently.
 * Turn-affecting operations on one session (or NPC) run in arrival order.
 * Responses from the peer (to the engine's `tool/call` requests) bypass that
 * queue, so a slow turn never blocks them.
 *
 * **Delivery.** Outgoing messages are single-line JSON strings passed to
 * `send` one at a time, from a background thread, in the order they were
 * produced; `send` is never called concurrently with itself. For a request,
 * every notification and `tool/call` it causes is sent before its response.
 *
 * @property configuration Models, limits, logger and extensions.
 * @param send Receives each outgoing message as one line of JSON (no trailing newline).
 */
public class BridgeEngine(
    public val configuration: BridgeConfiguration = BridgeConfiguration(),
    send: (String) -> Unit,
) : AutoCloseable {
    private enum class Phase { RUNNING, SHUT_DOWN, CLOSED }

    private sealed interface Sink {
        /** Reply through `send`. */
        data object Peer : Sink

        /** Reply to an in-process [call]. */
        class Local(val call: LocalCall) : Sink
    }

    private class Inbound(val method: String, val id: JsonRpcId?, val params: JsonElement?, val sink: Sink)

    private class Teardown(val sessions: List<BridgeSession>, val requests: List<ClientRequest>, val jobs: List<Job>)

    private val lock = Any()
    private val outbox = Outbox(send) { error -> log(BridgeLogLevel.ERROR, "The send callback threw: $error") }
    private val inbound = Channel<Inbound>(Channel.UNLIMITED)
    private val registry = BridgeMethodRegistry()
    private val sessionMap = LinkedHashMap<String, BridgeSession>()
    private val clientRequests = HashMap<String, ClientRequest>()
    private val deferred = HashMap<Long, Job>()
    private var nextClientRequest = 1L
    private var nextLocalCall = 1L
    private var nextSession = 1L
    private var nextToken = 0L
    private var phase = Phase.RUNNING
    private var client: JsonObject? = null
    private var shutdownHookPending = false

    /**
     * Where the engine's work runs (request handling, deferred replies,
     * forwarded tool calls). Extensions launch their own work here.
     */
    public val scope: CoroutineScope = CoroutineScope(SupervisorJob() + configuration.dispatcher + CoroutineName("oam-bridge"))

    init {
        BuiltinMethods.register(registry)
        for (extension in configuration.extensions) extension.register(registry, this)
        scope.launch {
            for (item in inbound) process(item)
        }
    }

    // MARK: Incoming messages

    /**
     * Handles one incoming message (a request, a notification, or a response
     * to one of the engine's own requests). Returns immediately; results are
     * delivered through `send`. Blank lines are ignored.
     */
    public fun receive(line: String) {
        if (line.isBlank()) return
        val message = try {
            JsonText.parse(line)
        } catch (error: IllegalArgumentException) {
            outbox.send(JsonRpcMessage.error(null, BridgeError.parseError(error.message ?: "Parse error.")))
            return
        }
        receive(message)
    }

    /** Handles one already-parsed incoming message. See [receive]. */
    public fun receive(message: JsonElement) {
        val obj = message as? JsonObject ?: run {
            val reason = if (message is JsonArray) {
                "Batch requests are not supported; send one message per line."
            } else {
                "A JSON-RPC message must be an object."
            }
            outbox.send(JsonRpcMessage.error(null, BridgeError.invalidRequest(reason)))
            return
        }
        val rawId = obj["id"]
        val id = rawId?.let { JsonRpcId.of(it) }
        if (obj["jsonrpc"]?.stringValue != "2.0") {
            outbox.send(JsonRpcMessage.error(id, BridgeError.invalidRequest("Missing or unsupported 'jsonrpc' version; expected \"2.0\".")))
            return
        }
        if (rawId != null && id == null) {
            outbox.send(JsonRpcMessage.error(null, BridgeError.invalidRequest(JsonRpcId.rejectionReason(rawId))))
            return
        }
        val methodValue = obj["method"]
        if (methodValue != null) {
            val method = methodValue.stringValue
            if (method.isNullOrEmpty()) {
                outbox.send(JsonRpcMessage.error(id, BridgeError.invalidRequest("'method' must be a non-empty string.")))
                return
            }
            enqueue(Inbound(method, id, obj["params"], Sink.Peer))
            return
        }
        if (id != null && ("result" in obj || "error" in obj)) {
            val error = obj["error"]
            val result = if (error != null && error !is JsonNull) {
                Result.failure(BridgeError.fromJson(error))
            } else {
                Result.success(obj["result"] ?: JsonNull)
            }
            completeClientRequest(id, result)
            return
        }
        outbox.send(
            JsonRpcMessage.error(id, BridgeError.invalidRequest("A message needs 'method' (request or notification) or 'result'/'error' (response).")),
        )
    }

    /**
     * Calls a method in-process and returns its result, as if a peer had sent
     * the request. Notifications and `tool/call` requests the method causes
     * still go through `send` (answer those with [receive]). Cancelling the
     * calling coroutine cancels the request.
     *
     * @param id The request id those notifications and `tool/call` requests carry
     *   as `requestId`, so the caller can route them. When `null`, a private `local-<n>` id is used.
     * @throws BridgeError when the method fails.
     */
    public suspend fun call(method: String, params: JsonElement? = null, id: JsonRpcId? = null): JsonElement {
        val local = LocalCall()
        val requestId = id ?: synchronized(lock) { JsonRpcId.of("local-${nextLocalCall++}") }
        if (inbound.trySend(Inbound(method, requestId, params, Sink.Local(local))).isFailure) throw BridgeError.shutDown()
        return local.await().getOrThrow()
    }

    /** Waits until every message produced so far has been passed to `send`. */
    public suspend fun flush() {
        outbox.flush()
    }

    private fun enqueue(item: Inbound) {
        if (inbound.trySend(item).isFailure) log(BridgeLogLevel.DEBUG, "Dropped '${item.method}': the engine is closed.")
    }

    private suspend fun process(item: Inbound) {
        val (handler, running) = synchronized(lock) { registry.handler(item.method) to (phase == Phase.RUNNING) }
        if (!running) {
            deliver(Result.failure(BridgeError.shutDown()), item)
            return
        }
        if (handler == null) {
            deliver(Result.failure(BridgeError.methodNotFound(item.method)), item)
            return
        }
        val reply = try {
            handler(BridgeRequest(this, item.method, item.id, BridgeParams.of(item.params)))
        } catch (error: Throwable) {
            deliver(Result.failure(BridgeError.normalizing(error)), item)
            return
        }
        when (reply) {
            is BridgeReply.Result -> deliver(Result.success(reply.value), item)
            is BridgeReply.Deferred -> startDeferred(reply.work, item)
        }
        fireShutdownHookIfPending()
    }

    private fun startDeferred(work: suspend () -> JsonElement, item: Inbound) {
        val replied = AtomicBoolean(false)
        val job = synchronized(lock) {
            if (phase != Phase.RUNNING) return@synchronized null
            val token = nextToken++
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val result = try {
                    Result.success(work())
                } catch (error: Throwable) {
                    Result.failure(BridgeError.normalizing(error))
                }
                if (replied.compareAndSet(false, true)) deliver(result, item)
            }
            deferred[token] = job
            job.invokeOnCompletion {
                synchronized(lock) { deferred.remove(token) }
                // Only when the body never ran (cancelled before it started).
                if (replied.compareAndSet(false, true)) deliver(Result.failure(BridgeError.cancelled()), item)
            }
            job
        }
        if (job == null) {
            deliver(Result.failure(BridgeError.shutDown()), item)
            return
        }
        (item.sink as? Sink.Local)?.call?.attach(job)
        job.start()
    }

    private fun deliver(result: Result<JsonElement>, item: Inbound) {
        when (val sink = item.sink) {
            Sink.Peer -> {
                val id = item.id
                if (id == null) {
                    result.exceptionOrNull()?.let { log(BridgeLogLevel.DEBUG, "Notification '${item.method}' failed: $it") }
                    return
                }
                result.fold(
                    onSuccess = { outbox.send(JsonRpcMessage.result(id, it)) },
                    onFailure = { outbox.send(JsonRpcMessage.error(id, BridgeError.normalizing(it))) },
                )
            }
            is Sink.Local -> sink.call.resolve(result)
        }
    }

    // MARK: Outgoing messages

    /** Sends a notification to the peer. */
    public fun notify(method: String, params: JsonElement) {
        outbox.send(JsonRpcMessage.notification(method, params))
    }

    /**
     * Sends a request to the peer (for example `tool/call`) and returns a
     * handle for its response. The message is queued for delivery before this
     * returns, so it is ordered before anything sent afterwards. After
     * shutdown the returned request has already failed with `shut_down`.
     */
    public fun sendRequest(method: String, params: JsonElement): ClientRequest {
        val request = synchronized(lock) {
            if (phase != Phase.RUNNING) return@synchronized null
            val request = ClientRequest("t-${nextClientRequest++}", method) { id -> synchronized(lock) { clientRequests.remove(id) } }
            clientRequests[request.id] = request
            request
        }
        if (request == null) {
            return ClientRequest("t-0", method) {}.also { it.complete(Result.failure(BridgeError.shutDown())) }
        }
        outbox.send(JsonRpcMessage.request(JsonRpcId.of(request.id), method, params))
        return request
    }

    private fun completeClientRequest(id: JsonRpcId, result: Result<JsonElement>) {
        val key = id.value.stringValue ?: id.toString()
        val request = synchronized(lock) { clientRequests.remove(key) }
        if (request == null) {
            log(BridgeLogLevel.WARNING, "Ignoring a response to unknown or finished request '$id'.")
            return
        }
        request.complete(result)
    }

    // MARK: Sessions

    /**
     * Looks up a session.
     *
     * @throws BridgeError `session_not_found`.
     */
    public fun session(id: String): BridgeSession =
        synchronized(lock) { sessionMap[id] } ?: throw BridgeError.sessionNotFound(id)

    /** Live sessions, in creation order. */
    public val sessions: List<BridgeSession> get() = synchronized(lock) { sessionMap.values.toList() }

    /**
     * Adds a session, enforcing [BridgeConfiguration.maxSessions] and unique ids.
     *
     * @throws BridgeError `shut_down`, `session_exists` or `session_limit`.
     */
    public fun insert(session: BridgeSession) {
        val limit = configuration.maxSessions
        synchronized(lock) {
            if (phase != Phase.RUNNING) throw BridgeError.shutDown()
            if (session.id in sessionMap) throw BridgeError.sessionExists(session.id)
            if (sessionMap.size >= limit) throw BridgeError.sessionLimitReached(limit)
            sessionMap[session.id] = session
        }
    }

    /** Removes a session, cancels its running and queued work and closes its agent. */
    public fun removeSession(id: String): BridgeSession? {
        val session = synchronized(lock) { sessionMap.remove(id) } ?: return null
        session.close()
        return session
    }

    /** Whether a session id is free to use. */
    public fun isSessionIdAvailable(id: String): Boolean = synchronized(lock) { id !in sessionMap }

    /** Generates an unused session id (`s1`, `s2`, …). */
    public fun makeSessionId(): String = synchronized(lock) {
        var id: String
        do {
            id = "s${nextSession++}"
        } while (id in sessionMap)
        id
    }

    /**
     * Creates a language model for a `model` parameter through
     * [BridgeConfiguration.modelFactory].
     *
     * @throws BridgeError `invalid_params` when scripted models are disabled or the type is unknown,
     *   or whatever the factory throws, normalized.
     */
    public fun makeModel(spec: BridgeModelSpec): LanguageModel {
        if (spec is BridgeModelSpec.Scripted && !configuration.allowsScriptedModels) {
            throw BridgeError.invalidParams("Scripted models are disabled on this bridge.")
        }
        return try {
            configuration.modelFactory(spec)
        } catch (error: Exception) {
            throw BridgeError.normalizing(error)
        }
    }

    // MARK: Lifecycle

    /** Client information from `initialize`, if the peer sent any. */
    public val clientInfo: JsonObject? get() = synchronized(lock) { client }

    internal fun recordClient(info: JsonObject) {
        synchronized(lock) { client = info }
    }

    /** Registered method names, sorted. */
    public val methods: List<String> get() = synchronized(lock) { registry.methods }

    /** Registers (or replaces) a method at runtime. */
    public fun register(method: String, handler: BridgeMethodHandler) {
        synchronized(lock) { registry.register(method, handler) }
    }

    /** Whether `shutdown` (or [close]) has run. */
    public val isShutDown: Boolean get() = synchronized(lock) { phase != Phase.RUNNING }

    /**
     * Cancels all turns, fails pending `tool/call` requests, removes all
     * sessions and shuts down extensions. Later requests fail with
     * `shut_down`. Waits at most about two seconds for extensions to shut down
     * and for cancelled requests to send their error responses; work that
     * ignores cancellation is left to finish in the background.
     */
    public suspend fun shutdown() {
        val work = takeEverything(Phase.SHUT_DOWN) ?: return
        work.sessions.forEach { it.close() }
        work.requests.forEach { it.complete(Result.failure(BridgeError.shutDown())) }
        work.jobs.forEach { it.cancel() }
        val extensions = configuration.extensions
        // A separate job, joined with a time limit: the bound holds even for work that ignores cancellation.
        val waiter = scope.launch {
            for (extension in extensions) {
                try {
                    extension.shutdown()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log(BridgeLogLevel.ERROR, "Extension shutdown failed: $error")
                }
            }
            work.jobs.forEach { it.join() }
        }
        withTimeoutOrNull(SHUTDOWN_GRACE_PERIOD) { waiter.join() }
    }

    /**
     * Shuts down immediately without waiting, and stops delivering messages:
     * once this returns, `send` is never called again (if called from inside
     * `send`, the current call is the last). Used by `OamJni.destroy`.
     */
    override fun close() {
        val work = takeEverything(Phase.CLOSED)
        inbound.close()
        if (work != null) {
            work.sessions.forEach { it.close() }
            work.requests.forEach { it.complete(Result.failure(BridgeError.shutDown())) }
            work.jobs.forEach { it.cancel() }
            val extensions = configuration.extensions
            if (extensions.isNotEmpty()) {
                scope.launch {
                    for (extension in extensions) {
                        try {
                            extension.shutdown()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            log(BridgeLogLevel.ERROR, "Extension shutdown failed: $error")
                        }
                    }
                }
            }
        }
        outbox.close()
    }

    internal fun markShutdownHookPending() {
        synchronized(lock) { shutdownHookPending = true }
    }

    private fun fireShutdownHookIfPending() {
        val fire = synchronized(lock) { shutdownHookPending.also { shutdownHookPending = false } }
        val hook = configuration.onShutdown
        if (fire && hook != null) outbox.perform(hook)
    }

    private fun takeEverything(next: Phase): Teardown? = synchronized(lock) {
        val wasRunning = phase == Phase.RUNNING
        if (next == Phase.CLOSED || wasRunning) phase = next
        if (!wasRunning) return@synchronized null
        val teardown = Teardown(sessionMap.values.toList(), clientRequests.values.toList(), deferred.values.toList())
        sessionMap.clear()
        clientRequests.clear()
        teardown
    }

    internal fun log(level: BridgeLogLevel, message: String) {
        configuration.logger?.invoke(level, message)
    }

    public companion object {
        /** How long [shutdown] waits for extensions and cancelled requests. */
        public val SHUTDOWN_GRACE_PERIOD: Duration = 2.seconds
    }
}

/**
 * A request the engine sent to the peer (such as `tool/call`), awaiting the
 * peer's response.
 *
 * @property id The JSON-RPC id used on the wire (`t-<n>`).
 * @property method The method.
 */
public class ClientRequest internal constructor(
    public val id: String,
    public val method: String,
    private val forget: (String) -> Unit,
) {
    private val outcome = OneShot<Result<JsonElement>>()

    /**
     * Waits for the peer's response. A JSON-RPC error response becomes a
     * failure holding a [BridgeError]. Cancelling the waiting coroutine
     * cancels the request (a later response is ignored).
     */
    public suspend fun result(): Result<JsonElement> = awaitResult(null)

    /**
     * Waits for the peer's response.
     *
     * @throws BridgeError the peer's error, or `cancelled`.
     */
    public suspend fun response(): JsonElement = result().getOrThrow()

    /** Stops waiting; a later response from the peer is ignored. */
    public fun cancel() {
        cancelIfPending()
    }

    /** Whether a response (or cancellation) has arrived. */
    public val isFinished: Boolean get() = outcome.current != null

    internal fun complete(result: Result<JsonElement>) {
        outcome.resolve(result)
    }

    /** Cancels; returns true if the request was still pending. */
    internal fun cancelIfPending(): Boolean {
        val cancelled = outcome.resolve(Result.failure(BridgeError.cancelled()))
        if (cancelled) forget(id)
        return cancelled
    }

    /**
     * Like [result]; when the waiting coroutine is cancelled while the request
     * is pending, [onCancel] runs synchronously on the cancelling thread.
     */
    internal suspend fun awaitResult(onCancel: (() -> Unit)?): Result<JsonElement> =
        outcome.await { if (cancelIfPending()) onCancel?.invoke() }
}

/** The reply slot of an in-process [BridgeEngine.call]. */
internal class LocalCall {
    private val outcome = OneShot<Result<JsonElement>>()
    private val lock = Any()
    private var job: Job? = null
    private var cancelled = false

    fun attach(job: Job) {
        val cancelNow = synchronized(lock) {
            this.job = job
            cancelled
        }
        if (cancelNow) job.cancel()
    }

    fun resolve(result: Result<JsonElement>) {
        outcome.resolve(result)
    }

    suspend fun await(): Result<JsonElement> = outcome.await {
        val running = synchronized(lock) {
            cancelled = true
            job
        }
        running?.cancel()
        outcome.resolve(Result.failure(BridgeError.cancelled("The call was cancelled.")))
    }
}
