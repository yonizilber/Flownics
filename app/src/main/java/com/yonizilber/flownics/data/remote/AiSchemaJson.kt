package com.yonizilber.flownics.data.remote

import com.yonizilber.flownics.data.remote.gemini.GeminiSchema
import com.yonizilber.flownics.domain.ai.AiSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Standard JSON Schema, as Claude's structured output and strict tools take it. */
fun AiSchema.toJsonSchema(): JsonObject = buildJsonObject {
    description?.let { put("description", it) }
    when (val s = this@toJsonSchema) {
        is AiSchema.Obj -> {
            put("type", "object")
            put("properties", JsonObject(s.properties.mapValues { it.value.toJsonSchema() }))
            putJsonArray("required") { s.properties.keys.forEach { add(JsonPrimitive(it)) } }
            put("additionalProperties", false)
        }
        is AiSchema.Arr -> {
            put("type", "array")
            put("items", s.items.toJsonSchema())
        }
        is AiSchema.Str -> {
            put("type", "string")
            s.enum?.let { values -> putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } } }
        }
        is AiSchema.Num -> put("type", "number")
        is AiSchema.Integer -> put("type", "integer")
        is AiSchema.Bool -> put("type", "boolean")
    }
}

fun AiSchema.toGeminiSchema(): GeminiSchema = when (this) {
    is AiSchema.Obj -> GeminiSchema(
        type = "OBJECT",
        description = description,
        properties = properties.mapValues { it.value.toGeminiSchema() },
        required = properties.keys.toList(),
        propertyOrdering = properties.keys.toList(),
    )
    is AiSchema.Arr -> GeminiSchema(type = "ARRAY", description = description, items = items.toGeminiSchema())
    is AiSchema.Str -> GeminiSchema(type = "STRING", description = description, enum = enum, format = enum?.let { "enum" })
    is AiSchema.Num -> GeminiSchema(type = "NUMBER", description = description)
    is AiSchema.Integer -> GeminiSchema(type = "INTEGER", description = description)
    is AiSchema.Bool -> GeminiSchema(type = "BOOLEAN", description = description)
}

/** Plain Kotlin values (Map/List/String/...), which the Anthropic SDK's JsonValue.from accepts. */
fun JsonElement.toPlain(): Any? = when (this) {
    is JsonNull -> null
    is JsonObject -> mapValues { it.value.toPlain() }
    is JsonArray -> map { it.toPlain() }
    is JsonPrimitive -> when {
        isString -> content
        else -> booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
    }
}
