package com.spacecorps.oam.jni

import android.content.Context
import android.util.Log

/**
 * The JNI entry point for native hosts (game engines such as Space3d): the
 * Android counterpart of open-apple-models' `oam_bridge_*` C ABI. A host
 * speaks the same JSON-RPC 2.0 protocol (v1.0, open-apple-models'
 * `docs/PROTOCOL.md`) on both platforms and only swaps this transport.
 *
 * ```text
 * host thread ── send(id, line) ──▶ engine (in order, background)
 * host .so   ◀── nativeDeliver(handle, line) ── engine output (in order, background thread)
 * ```
 *
 * **Host checklist (Rust/C++ over JNI).**
 * 1. Export `Java_com_spacecorps_oam_jni_OamJni_nativeDeliver(JNIEnv*, jclass, jlong handle, jstring line)`,
 *    or register it with `RegisterNatives` on this class. Libraries loaded by
 *    `NativeActivity`/`GameActivity` rather than `System.loadLibrary` are not searched for
 *    JNI symbols, so such hosts must use `RegisterNatives`.
 * 2. Look this class up through the activity's class loader
 *    (`activity.getClassLoader().loadClass("com.spacecorps.oam.jni.OamJni")`): `FindClass`
 *    on a native thread only sees system classes. Keep a global reference.
 * 3. `create(activity, handle)` → bridge id (0 on failure); `send(id, line)` per message
 *    (non-blocking); `destroy(id)` when done. After `destroy` returns, `nativeDeliver` is
 *    never called with that handle again, so it may be freed. Do not call `destroy` while
 *    holding a lock your `nativeDeliver` takes.
 * 4. `nativeDeliver` runs on a JVM background thread (already attached): copy the string and
 *    return quickly, for example by pushing it to the game loop's queue.
 * 5. Gemini Nano only runs while the app is the top foreground app. The host app must be
 *    built with Gradle (the ML Kit AAR and its manifest entries must be merged), with the
 *    host's `.so` under `jniLibs`.
 *
 * R8 keeps this class and its members through the AAR's consumer rules.
 */
public object OamJni {
    private const val TAG = "OamJni"

    /**
     * Creates each bridge's engine. The default, a [BridgeEngineFactory], runs oam-bridge's
     * `BridgeEngine` (the protocol above, including scripted models) over one shared
     * `GeminiNanoModel`. Replace it before the first [create] (for example in
     * `Application.onCreate`) to configure the model, limits or extensions.
     */
    @JvmStatic
    @Volatile
    public var engineFactory: MessageEngineFactory = BridgeEngineFactory()

    /** Delivers outgoing messages; replaced in tests, where the native library is absent. */
    @Volatile
    internal var nativeSink: (handle: Long, line: String) -> Unit = { handle, line -> nativeDeliver(handle, line) }

    internal val host: BridgeHost = BridgeHost(
        deliver = { handle, line -> nativeSink(handle, line) },
        onError = { message, error -> Log.w(TAG, message, error) },
    )

    /**
     * Creates a bridge. Every message its engine writes is passed to
     * [nativeDeliver] with [nativeHandle].
     *
     * @param context Any context (an activity is fine); the application context is kept.
     * @param nativeHandle The host's opaque pointer or id for this bridge.
     * @return The bridge id, or 0 if the engine could not be created (logged).
     */
    @JvmStatic
    public fun create(context: Context, nativeHandle: Long): Long {
        val appContext = context.applicationContext ?: context
        val factory = engineFactory
        return host.create(nativeHandle) { output -> factory.create(appContext, output) }
    }

    /**
     * Queues one JSON-RPC message (one JSON document, no trailing newline) for
     * the bridge. Never blocks. Messages to an unknown or destroyed bridge
     * are dropped (logged).
     */
    @JvmStatic
    public fun send(bridgeId: Long, jsonLine: String) {
        if (!host.send(bridgeId, jsonLine)) Log.w(TAG, "send: no bridge $bridgeId; message dropped.")
    }

    /**
     * Closes the bridge: cancels its work and stops delivery. When this
     * returns, [nativeDeliver] will not be called for the bridge again.
     * Unknown ids are ignored.
     */
    @JvmStatic
    public fun destroy(bridgeId: Long) {
        host.destroy(bridgeId)
    }

    /**
     * Implemented by the host's native library: receives one outgoing
     * JSON-RPC message for the bridge created with [nativeHandle]. Called on a
     * background thread, in order per bridge.
     */
    @JvmStatic
    public external fun nativeDeliver(nativeHandle: Long, jsonLine: String)
}
