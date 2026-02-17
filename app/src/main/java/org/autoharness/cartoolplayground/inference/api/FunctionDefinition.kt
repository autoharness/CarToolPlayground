package org.autoharness.cartoolplayground.inference.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The struct refers to
 * https://github.com/google-ai-edge/ai-edge-apis/blob/main/local_agents/core/proto/content.proto
 */
data class FunctionDefinition(
    val fullName: String,
    val shortName: String,
    val description: String,
    val parameters: FunctionSchema? = null,
    val response: FunctionSchema? = null,
) {
    /**
     * Converts a FunctionDeclaration object into an OpenAI-style JSON string.
     * See https://platform.openai.com/docs/guides/function-calling#defining-functions.
     */
    fun toJsonString(): String {
        val jsonObject = buildJsonObject {
            put("name", JsonPrimitive(shortName))
            put("description", JsonPrimitive(description))

            // If 'parameters' exists, convert it recursively and add it.
            parameters?.let {
                put("parameters", convertSchemaToJsonObject(it))
            }
        }

        val json = Json { prettyPrint = true }
        return json.encodeToString(jsonObject)
    }

    private fun convertSchemaToJsonObject(schema: FunctionSchema): JsonObject = buildJsonObject {
        val jsonType = when (schema.type) {
            DataType.OBJECT -> "object"
            DataType.STRING -> "string"
            DataType.ARRAY -> "array"
            DataType.BOOLEAN -> "boolean"
            DataType.INT, DataType.LONG -> "integer"
            DataType.FLOAT, DataType.DOUBLE -> "number"
            // UNSPECIFIED and UNIT types are ignored in the final JSON.
            // See https://json-schema.org/understanding-json-schema/reference/type.
            else -> null
        }

        jsonType?.let { put("type", JsonPrimitive(it)) }

        if (schema.description.isNotBlank()) {
            put("description", JsonPrimitive(schema.description))
        }

        if (schema.enum.isNotEmpty()) {
            putJsonArray("enum") {
                schema.enum.forEach { value ->
                    val jsonElement = when (value) {
                        is String -> JsonPrimitive(value)
                        is Number -> JsonPrimitive(value)
                        is Boolean -> JsonPrimitive(value)
                        else -> JsonPrimitive(value.toString())
                    }
                    add(jsonElement)
                }
            }
        }

        // For an OBJECT, recursively convert its properties and add the 'required' list.
        if (schema.type == DataType.OBJECT) {
            if (schema.properties.isNotEmpty()) {
                putJsonObject("properties") {
                    schema.properties.forEach { (key, value) ->
                        put(key, convertSchemaToJsonObject(value))
                    }
                }
            }
            if (schema.required.isNotEmpty()) {
                putJsonArray("required") {
                    schema.required.forEach { add(JsonPrimitive(it)) }
                }
            }
        }

        // For an ARRAY, recursively convert its 'items' schema.
        if (schema.type == DataType.ARRAY && schema.items != null) {
            put("items", convertSchemaToJsonObject(schema.items))
        }
    }
}

data class FunctionSchema(
    val type: DataType = DataType.UNSPECIFIED,
    val description: String = "",
    val nullable: Boolean = false,
    val enum: List<Any> = emptyList(),
    val items: FunctionSchema? = null,
    val properties: Map<String, FunctionSchema> = emptyMap(),
    val required: List<String> = emptyList(),
)

data class FunctionCall(val name: String, val args: Map<String, JsonElement>)

data class FunctionResponse(val name: String, val response: JsonObject)

enum class DataType {
    UNSPECIFIED,
    BOOLEAN,
    OBJECT,
    DOUBLE,
    FLOAT,
    LONG,
    INT,
    STRING,
    ARRAY,
    UNIT,
}
