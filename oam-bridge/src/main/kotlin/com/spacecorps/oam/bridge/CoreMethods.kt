package com.spacecorps.oam.bridge

import com.spacecorps.oam.EmptyJsonObject
import com.spacecorps.oam.bridge.BridgeCoding.obj
import com.spacecorps.oam.bridge.BridgeCoding.strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Registers the built-in method set. */
internal object BuiltinMethods {
    fun register(registry: BridgeMethodRegistry) {
        CoreMethods.register(registry)
        SessionMethods.register(registry)
        ValidationMethods.register(registry)
    }
}

/** `initialize`, `ping`, `model/availability`, `shutdown`. */
internal object CoreMethods {
    fun register(registry: BridgeMethodRegistry) {
        registry.register("initialize", ::initialize)
        registry.register("ping") { BridgeReply.Result(EmptyJsonObject) }
        registry.register("model/availability") { request ->
            BridgeReply.Result(request.engine.configuration.modelAvailability().toJson())
        }
        registry.register("shutdown") { request ->
            request.engine.shutdown()
            request.engine.markShutdownHookPending()
            BridgeReply.Result(EmptyJsonObject)
        }
    }

    /** `initialize {client?: {name, version}, protocolVersion?}`. */
    private suspend fun initialize(request: BridgeRequest): BridgeReply {
        val engine = request.engine
        request.params.optionalObject("client")?.let(engine::recordClient)
        request.params.optionalString("protocolVersion")?.let { requested ->
            if (requested.substringBefore('.') != BridgeVersion.PROTOCOL_VERSION.substringBefore('.')) {
                throw BridgeError.invalidParams("Unsupported protocol version '$requested'; this bridge speaks ${BridgeVersion.PROTOCOL_VERSION}.")
            }
        }
        val configuration = engine.configuration
        val models = if (configuration.allowsScriptedModels) listOf("system", "scripted") else listOf("system")
        val notifications = LinkedHashSet(listOf("session/event", "tool/cancel"))
        for (extension in configuration.extensions) notifications += extension.notificationMethods
        return BridgeReply.Result(
            obj(
                "protocolVersion" to JsonPrimitive(BridgeVersion.PROTOCOL_VERSION),
                "server" to obj("name" to JsonPrimitive(BridgeVersion.SERVER_NAME), "version" to JsonPrimitive(BridgeVersion.LIBRARY)),
                "capabilities" to obj(
                    "methods" to strings(engine.methods),
                    "notifications" to strings(notifications.toList()),
                    "clientRequests" to strings(listOf("tool/call")),
                    "streaming" to JsonPrimitive(true),
                    "clientTools" to JsonPrimitive(true),
                    "structuredOutput" to JsonPrimitive(true),
                    "models" to strings(models),
                    "maxSessions" to JsonPrimitive(configuration.maxSessions),
                    "batch" to JsonPrimitive(false),
                ),
                "model" to configuration.modelAvailability().toJson(),
            ),
        )
    }
}

/**
 * `schema/validate`, `tools/validate`.
 *
 * Gemini Nano has no generation schema of its own: the JSON Schema is
 * described to the model in the prompt, and the output is validated and
 * repaired afterwards. So `generationSchema` is the schema as it is enforced,
 * and the Android-only `rendered` is the text the model is shown.
 */
internal object ValidationMethods {
    fun register(registry: BridgeMethodRegistry) {
        registry.register("schema/validate") { request ->
            val params = request.params
            val schema = BridgeCoding.schema(params.value("schema"))
            params.optionalString("name")
            val warnings = BridgeCoding.checkSchema(schema, "schema")
            BridgeReply.Result(
                obj("warnings" to strings(warnings), "generationSchema" to schema.json, "rendered" to JsonPrimitive(schema.renderFields())),
            )
        }
        registry.register("tools/validate") { request ->
            val parsed = BridgeCoding.tools(request.params.value("tools"), defaultTimeout = null)
            val tools = parsed.tools.map { tool ->
                obj(
                    "name" to JsonPrimitive(tool.name),
                    "warnings" to strings(tool.schemaWarnings.map { it.removePrefix("${tool.name}: ") }),
                    "generationSchema" to tool.parameters.json,
                    "rendered" to JsonPrimitive(tool.parameters.render()),
                )
            }
            BridgeReply.Result(obj("tools" to JsonArray(tools), "warnings" to strings(parsed.warnings)))
        }
    }
}
