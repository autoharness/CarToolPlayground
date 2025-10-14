package org.autoharness.cartoolplayground.inference.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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
)

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
