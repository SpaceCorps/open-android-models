package com.spacecorps.oam.jni

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The placeholder engine [OamJni] uses until oam-bridge's `BridgeEngine` is
 * wired in: every JSON-RPC request gets an error response saying so, so a
 * host sees a clear failure instead of silence. Notifications are ignored.
 *
 * TODO(oam-bridge): remove once [OamJni.engineFactory] defaults to a BridgeEngine-backed factory.
 */
internal class UnwiredMessageEngine(private val output: MessageSink) : MessageEngine {
    override fun receive(line: String) {
        val message = try {
            Json.parseToJsonElement(line) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }
        if (message == null) {
            output.deliver(error(JsonNull, PARSE_ERROR, "Parse error: expected one JSON-RPC message object per line."))
            return
        }
        val id = message["id"] ?: return // a notification
        output.deliver(error(id, INTERNAL_ERROR, MESSAGE))
    }

    override fun close() {}

    private fun error(id: JsonElement, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject {
            put("code", JsonPrimitive(code))
            put("message", message)
        })
    }.toString()

    companion object {
        const val PARSE_ERROR = -32700
        const val INTERNAL_ERROR = -32603
        const val MESSAGE = "open-android-models: no JSON-RPC engine is wired into OamJni (set OamJni.engineFactory)."
    }
}
