package com.spacecorps.oam

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonSchemaTest {
    private fun schema(text: String) = JsonSchema.parse(text)

    private fun violations(schema: JsonSchema, value: String) = schema.validate(LenientJson.parse(value)).map { it.toString() }

    // MARK: Builders and checking

    @Test
    fun buildersProduceClosedObjectsWithAllPropertiesRequired() {
        val order = Tavern.orderSchema
        assertEquals(
            """{"type":"object","properties":{"item":{"type":"string","description":"menu item","enum":["ale","stew","bread"]},""" +
                """"quantity":{"type":"integer","minimum":1,"maximum":10}},"required":["item","quantity"],"additionalProperties":false}""",
            order.toString(),
        )
        assertEquals(listOf("item", "quantity"), order.propertyNames)
        assertTrue(JsonSchema.empty.isEmptyObject)
        assertFalse(order.isEmptyObject)
        assertEquals(
            """{"type":"object","properties":{"a":{"type":"string"}},"required":[],"additionalProperties":true}""",
            JsonSchema.obj("a" to JsonSchema.string(), required = emptyList(), additionalProperties = true).toString(),
        )
    }

    @Test
    fun checkReportsMalformedSchemasWithPaths() {
        fun error(text: String) = assertFailsWith<InvalidSchemaException> { schema(text).check() }
        assertEquals("#/properties/a/type", error("""{"type":"object","properties":{"a":{"type":"strin"}}}""").path)
        assertEquals("#/required", error("""{"type":"object","required":"a"}""").path)
        assertEquals("#/\$ref", error("""{"${'$'}ref":"#/${'$'}defs/Missing"}""").path)
        assertEquals("#/\$ref", error("""{"${'$'}ref":"https://example.com/schema"}""").path)
        assertEquals("#/anyOf", error("""{"anyOf":[]}""").path)
        assertEquals("#/enum", error("""{"enum":[]}""").path)
        assertEquals("#/pattern", error("""{"type":"string","pattern":"(unclosed"}""").path)
        assertEquals("#/minimum", error("""{"type":"integer","minimum":"one"}""").path)
        assertEquals("#/maxItems", error("""{"type":"array","maxItems":-1}""").path)
        assertEquals("#/items", error("""{"type":"array","items":[{"type":"string"}]}""").path)
        assertFailsWith<InvalidSchemaException> { JsonSchema.parse("[1]") }
        assertFailsWith<InvalidSchemaException> { JsonSchema.parse("not json") }
    }

    @Test
    fun checkWarnsAboutIgnoredKeywords() {
        val warnings = schema("""{"type":"object","properties":{"email":{"type":"string","format":"email"}},"uniqueItems":true,"x-custom":1}""").check()
        assertEquals(
            setOf("#/properties/email: 'format' is not supported and is ignored", "#: 'uniqueItems' is not supported and is ignored"),
            warnings.toSet(),
        )
        assertEquals(emptyList(), Tavern.orderSchema.check())
    }

    @Test
    fun agentToolRejectsInvalidSchemaAndReservedNames() {
        val schemaError = assertFailsWith<AgentError> {
            AgentTool.external("bad", "x", schema("""{"type":"object","properties":{"a":{"type":"nope"}}}"""))
        }
        assertEquals(AgentErrorCode.INVALID_SCHEMA, schemaError.code)
        assertEquals(AgentErrorCode.INVALID_REQUEST, assertFailsWith<AgentError> { AgentTool.external("respond", "x") }.code)
        assertEquals(AgentErrorCode.INVALID_REQUEST, assertFailsWith<AgentError> { AgentTool.external(" padded", "x") }.code)
        val warned = AgentTool.external("mail", "x", schema("""{"type":"object","properties":{"to":{"type":"string","format":"email"}}}"""))
        assertEquals(listOf("mail: #/properties/to: 'format' is not supported and is ignored"), warned.schemaWarnings)
    }

    // MARK: Validation

    @Test
    fun validatesTypesWithPreciseMessages() {
        val order = Tavern.orderSchema
        assertEquals(emptyList(), violations(order, """{"item":"ale","quantity":2}"""))
        assertEquals(
            listOf("$.quantity: expected an integer, got a string (\"two\")"),
            violations(order, """{"item":"ale","quantity":"two"}"""),
        )
        assertEquals(listOf("$.quantity: expected an integer, got a number (2.5)"), violations(order, """{"item":"ale","quantity":2.5}"""))
        assertEquals(emptyList(), violations(order, """{"item":"ale","quantity":2.0}"""))
        assertEquals(listOf("$: expected an object, got an array"), violations(order, "[]"))
        assertEquals(listOf("$.x: expected a string or null, got a boolean (true)"), violations(schema("""{"properties":{"x":{"type":["string","null"]}}}"""), """{"x":true}"""))
    }

    @Test
    fun validatesEnumsConstsAndRanges() {
        val order = Tavern.orderSchema
        assertEquals(
            listOf("$.item: must be one of \"ale\", \"stew\", \"bread\"; got \"wine\"", "$.quantity: must be at most 10; got 12"),
            violations(order, """{"item":"wine","quantity":12}"""),
        )
        assertEquals(listOf("$.quantity: must be at least 1; got 0"), violations(order, """{"item":"ale","quantity":0}"""))
        val numbers = schema("""{"type":"number","exclusiveMinimum":0,"exclusiveMaximum":1.5}""")
        assertEquals(listOf("$: must be greater than 0; got 0"), violations(numbers, "0"))
        assertEquals(listOf("$: must be less than 1.5; got 1.5"), violations(numbers, "1.5"))
        assertEquals(emptyList(), violations(numbers, "0.5"))
        assertEquals(listOf("$: must be exactly \"yes\"; got \"no\""), violations(schema("""{"const":"yes"}"""), "\"no\""))
        assertEquals(emptyList(), violations(schema("""{"enum":[1, "one", null]}"""), "1.0"))
        assertEquals(emptyList(), violations(schema("""{"enum":[1, "one", null]}"""), "null"))
    }

    @Test
    fun validatesStringsAndArrays() {
        val name = JsonSchema.string(minLength = 2, maxLength = 5, pattern = "^[a-z]+$")
        assertEquals(listOf("$: must have at least 2 characters; got 1"), violations(name, "\"a\""))
        assertEquals(listOf("$: must have at most 5 characters; got 6", "$: must match the pattern ^[a-z]+$; got \"abcdef\"".replace("abcdef", "abcdeF")), violations(name, "\"abcdeF\""))
        val list = JsonSchema.array(JsonSchema.integer(), minItems = 1, maxItems = 2)
        assertEquals(listOf("$: must have at least 1 item; got 0"), violations(list, "[]"))
        assertEquals(listOf("$: must have at most 2 items; got 3"), violations(list, "[1,2,3]"))
        assertEquals(listOf("$[1]: expected an integer, got a string (\"x\")"), violations(list, """[1,"x"]"""))
    }

    @Test
    fun validatesObjectsRequiredAndAdditionalProperties() {
        val order = Tavern.orderSchema
        assertEquals(
            listOf("$: missing required property \"quantity\"", "$: unexpected property \"colour\" (allowed: \"item\", \"quantity\")"),
            violations(order, """{"item":"ale","colour":"red"}"""),
        )
        assertEquals(listOf("$: unexpected property \"x\" (no properties are allowed)"), violations(JsonSchema.empty, """{"x":1}"""))
        val map = schema("""{"type":"object","additionalProperties":{"type":"integer"}}""")
        assertEquals(listOf("$.b: expected an integer, got a string (\"2\")"), violations(map, """{"a":1,"b":"2"}"""))
        assertEquals(listOf("$[\"odd key\"]: expected a string, got a number (1)"), violations(schema("""{"properties":{"odd key":{"type":"string"}}}"""), """{"odd key":1}"""))
    }

    @Test
    fun validatesCompositionAndNullable() {
        val either = schema("""{"anyOf":[{"type":"string"},{"type":"object","properties":{"x":{"type":"number"}},"required":["x"]}]}""")
        assertEquals(emptyList(), violations(either, "\"hi\""))
        assertEquals(emptyList(), violations(either, """{"x":1}"""))
        assertEquals(
            listOf("$: does not match any of the 2 allowed alternatives (closest: $.x: expected a number, got a string (\"1\"))"),
            violations(either, """{"x":"1"}"""),
        )
        val exactlyOne = schema("""{"oneOf":[{"type":"integer"},{"type":"number"}]}""")
        assertEquals(listOf("$: matches 2 of the alternatives but must match exactly one"), violations(exactlyOne, "3"))
        assertEquals(emptyList(), violations(exactlyOne, "3.5"))
        val all = schema("""{"allOf":[{"type":"object","required":["a"]},{"required":["b"]}]}""")
        assertEquals(listOf("$: missing required property \"b\""), violations(all, """{"a":1}"""))
        val nullable = schema("""{"type":"string","nullable":true}""")
        assertEquals(emptyList(), violations(nullable, "null"))
        assertEquals(emptyList(), JsonSchema.string().nullable().validate(JsonNull))
        assertEquals(emptyList(), Tavern.orderSchema.nullable().validate(JsonNull))
        assertEquals(listOf("$: no value is allowed here"), violations(schema("""{"properties":{"a":false}}"""), """{"a":1}""").map { it.replace("$.a", "$") })
    }

    @Test
    fun resolvesReferences() {
        val tree = schema(
            """
            {"${'$'}defs": {"Node": {"type":"object","properties":{"name":{"type":"string"},"children":{"type":"array","items":{"${'$'}ref":"#/${'$'}defs/Node"}}},"required":["name"]}},
             "${'$'}ref": "#/${'$'}defs/Node"}
            """.trimIndent(),
        )
        tree.check()
        assertEquals(emptyList(), violations(tree, """{"name":"root","children":[{"name":"leaf","children":[]}]}"""))
        assertEquals(listOf("$.children[0]: missing required property \"name\""), violations(tree, """{"name":"root","children":[{}]}"""))
        val legacy = schema("""{"definitions":{"Id":{"type":"integer"}},"properties":{"id":{"${'$'}ref":"#/definitions/Id"}}}""")
        assertEquals(listOf("$.id: expected an integer, got a string (\"x\")"), violations(legacy, """{"id":"x"}"""))
        val built = JsonSchema.obj("pet" to JsonSchema.ref("Pet")).withDefinitions(mapOf("Pet" to JsonSchema.string(enum = listOf("cat"))))
        assertEquals(listOf("$.pet: must be one of \"cat\"; got \"dog\""), violations(built, """{"pet":"dog"}"""))
    }

    // MARK: Coercion

    @Test
    fun coercesNearMissesSafely() {
        val order = Tavern.orderSchema
        assertEquals(jsonObjectOf("item" to "ale", "quantity" to 2), order.coerce(LenientJson.parse("""{"item":"Ale ","quantity":"2"}""")))
        assertEquals(jsonObjectOf("item" to "stew", "quantity" to 3), order.coerce(LenientJson.parse("""{"Item":"STEW","quantity":"3.0"}""")))
        // Unknown keys are dropped only when nothing required is missing.
        assertEquals(jsonObjectOf("item" to "bread", "quantity" to 1), order.coerce(LenientJson.parse("""{"item":"bread","quantity":1,"note":"warm"}""")))
        val incomplete = order.coerce(LenientJson.parse("""{"item_name":"bread","quantity":1}""")) as JsonObject
        assertEquals(setOf("item_name", "quantity"), incomplete.keys)

        val mixed = JsonSchema.obj(
            "flag" to JsonSchema.boolean(),
            "label" to JsonSchema.string(),
            "tags" to JsonSchema.array(JsonSchema.string()),
            "nested" to JsonSchema.obj("x" to JsonSchema.number()),
            "maybe" to JsonSchema.string(),
            "itemName" to JsonSchema.string(),
            required = listOf("flag", "label", "tags", "nested", "itemName"),
        )
        val coerced = mixed.coerce(LenientJson.parse("""{"flag":"yes","label":42,"tags":"solo","nested":"{\"x\": \"1.5\"}","maybe":null,"item_name":"x"}"""))
        assertEquals(
            jsonObjectOf("flag" to true, "label" to "42", "tags" to listOf("solo"), "nested" to mapOf("x" to 1.5), "itemName" to "x"),
            coerced,
        )
        assertTrue(mixed.accepts(coerced))
    }

    @Test
    fun coercionLeavesAmbiguousOrInvalidValuesAlone() {
        val sizes = JsonSchema.string(enum = listOf("dark ale", "Dark-Ale"))
        assertEquals(JsonPrimitive("DARK ALE"), sizes.coerce(JsonPrimitive("DARK ALE")))
        assertEquals(JsonPrimitive("many"), JsonSchema.integer().coerce(JsonPrimitive("many")))
        assertEquals(JsonPrimitive(50), JsonSchema.integer(maximum = 10).coerce(JsonPrimitive(50)))
        val choice = schema("""{"anyOf":[{"type":"integer"},{"type":"boolean"}]}""")
        assertEquals(JsonPrimitive(7), choice.coerce(JsonPrimitive("7")))
        assertEquals(JsonPrimitive(true), choice.coerce(JsonPrimitive("true")))
    }

    // MARK: Ordering and rendering

    @Test
    fun ordersKeysBySchemaRecursively() {
        val reply = JsonSchema.obj(
            "reasoning" to JsonSchema.string(),
            "choice" to JsonSchema.string(),
            "details" to JsonSchema.array(JsonSchema.obj("a" to JsonSchema.integer(), "b" to JsonSchema.integer())),
        )
        val value = LenientJson.parse("""{"extra":0,"details":[{"b":2,"a":1}],"choice":"x","reasoning":"r"}""")
        assertEquals("""{"reasoning":"r","choice":"x","details":[{"a":1,"b":2}],"extra":0}""", reply.order(value).toJsonString())
        val union = schema("""{"anyOf":[{"properties":{"p":{},"q":{}}},{"properties":{"y":{},"x":{}}}]}""")
        assertEquals("""{"y":1,"x":2}""", union.order(LenientJson.parse("""{"x":2,"y":1}""")).toJsonString())
    }

    @Test
    fun rendersCompactlyInWords() {
        assertEquals(
            """{"item": one of "ale", "stew", "bread" (menu item), "quantity": integer 1-10}""",
            Tavern.orderSchema.render(),
        )
        val rich = schema(
            """
            {"type":"object","properties":{
              "tags":{"type":"array","items":{"type":"string"},"maxItems":3},
              "note":{"type":["string","null"],"description":"optional note"},
              "code":{"type":"string","pattern":"^[A-Z]{3}$","minLength":3,"maxLength":3},
              "mode":{"const":"fast"},
              "ok":{"type":"boolean"},
              "target":{"anyOf":[{"type":"integer","minimum":0},{"type":"string"}]}
            },"required":["tags","ok"]}
            """.trimIndent(),
        )
        assertEquals(
            """{"tags": [string, …] (at most 3 items), "note"?: string or null (optional note), "code"?: string matching /^[A-Z]{3}$/ (exactly 3 chars), """ +
                """"mode"?: exactly "fast", "ok": true or false, "target"?: integer >= 0 or string}""",
            rich.render(),
        )
        assertEquals("{}", JsonSchema.empty.render())
    }

    @Test
    fun rendersFieldsOnePerLine() {
        val npc = JsonSchema.obj(
            "emotion" to JsonSchema.string(enum = listOf("happy", "sad")),
            "line" to JsonSchema.string(description = "what Mira says"),
            "endsConversation" to JsonSchema.boolean(),
            required = listOf("emotion", "line"),
        )
        assertEquals(
            """
            - "emotion": one of "happy", "sad"
            - "line": string, what Mira says
            - "endsConversation" (optional): true or false
            """.trimIndent(),
            npc.renderFields(),
        )
        assertEquals("[integer, …]", JsonSchema.array(JsonSchema.integer()).renderFields())
    }

    @Test
    fun serializesAsItsDocument() {
        val definition = ToolDefinition("take_order", "Order.", Tavern.orderSchema)
        val encoded = OamJson.encodeToString(ToolDefinition.serializer(), definition)
        assertTrue(encoded.contains(""""parameters":{"type":"object""""), encoded)
        assertEquals(definition, OamJson.decodeFromString(ToolDefinition.serializer(), encoded))
        assertEquals(
            """{"type":"function","function":{"name":"take_order","description":"Order.","parameters":${Tavern.orderSchema}}}""",
            definition.openAIDefinition.toJsonString(),
        )
    }
}
