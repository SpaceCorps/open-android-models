package com.spacecorps.oam.bridge

/**
 * Version constants of the bridge protocol and library.
 *
 * The protocol is open-apple-models' JSON-RPC 2.0 bridge protocol, so a host
 * that speaks it once can drive Apple Foundation Models on Apple platforms
 * and Gemini Nano on Android. See `docs/PROTOCOL.md`.
 */
public object BridgeVersion {
    /** The JSON-RPC protocol version reported by `initialize`. */
    public const val PROTOCOL_VERSION: String = "1.0"

    /** The library version reported by `initialize`. */
    public const val LIBRARY: String = "0.1.0"

    /** The server name reported by `initialize` (`open-apple-models` on Apple platforms). */
    public const val SERVER_NAME: String = "open-android-models"
}
