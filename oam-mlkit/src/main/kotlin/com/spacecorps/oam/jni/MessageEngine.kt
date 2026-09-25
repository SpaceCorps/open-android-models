package com.spacecorps.oam.jni

import android.content.Context

/**
 * A line-oriented JSON-RPC engine that [OamJni] and [BridgeHost] host: it
 * takes one JSON-RPC message per [receive] call and writes its own messages
 * (responses, `session/event`, `tool/call`, …) to the [MessageSink] it was
 * created with, one complete JSON document per line, from any thread.
 *
 * The oam-bridge module's `BridgeEngine` (protocol v1.0, as in
 * open-apple-models' `docs/PROTOCOL.md`) is the engine meant to run here.
 *
 * Contract:
 * - [receive] is never called concurrently for one engine; it should return
 *   quickly (start long work, such as a model turn, asynchronously).
 * - [close] may run while a [receive] call is still in progress; after
 *   [close] returns, [receive] is not called again. Output written after
 *   [close] is dropped.
 */
public interface MessageEngine : AutoCloseable {
    /** Handles one incoming JSON-RPC message (without a trailing newline). */
    public fun receive(line: String)

    /** Cancels running work and releases resources. */
    override fun close()
}

/** Where a [MessageEngine] writes its outgoing JSON-RPC messages. Thread-safe. */
public fun interface MessageSink {
    /** Queues one outgoing message (one JSON document, no trailing newline). Never blocks. */
    public fun deliver(line: String)
}

/** Creates the [MessageEngine] behind each [OamJni.create] call. */
public fun interface MessageEngineFactory {
    /**
     * Creates an engine that writes to [output].
     *
     * @param context The application context, for Android services.
     */
    public fun create(context: Context, output: MessageSink): MessageEngine
}
