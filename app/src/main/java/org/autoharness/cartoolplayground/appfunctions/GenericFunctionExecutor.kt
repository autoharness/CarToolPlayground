package org.autoharness.cartoolplayground.appfunctions

import android.annotation.SuppressLint
import android.util.Log
import androidx.appfunctions.AppFunctionData
import androidx.appfunctions.AppFunctionManagerCompat
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.ExecuteAppFunctionResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import org.autoharness.cartoolplayground.inference.api.DataType
import org.autoharness.cartoolplayground.inference.api.FunctionDefinition
import org.autoharness.cartoolplayground.inference.api.FunctionSchema

class GenericFunctionExecutor(private val manager: AppFunctionManagerCompat) {
    companion object {
        private const val TAG = "GenericFunctionExecutor"
    }

    suspend fun executeAppFunction(
        targetPackageName: String,
        functionDeclaration: FunctionDefinition,
        arguments: Map<String, JsonElement>,
    ): Result<JsonElement> = Result.runCatching {
        if (!manager.isAppFunctionEnabled(targetPackageName, functionDeclaration.fullName)) {
            throw IllegalStateException("Function (${functionDeclaration.fullName}) is disabled")
        }

        val functionParameters = buildAppFunctionData(functionDeclaration.parameters, arguments)
        val request = ExecuteAppFunctionRequest(
            functionIdentifier = functionDeclaration.fullName,
            targetPackageName = targetPackageName,
            functionParameters = functionParameters,
        )

        when (val response = manager.executeAppFunction(request)) {
            is ExecuteAppFunctionResponse.Success -> parseSuccessResponse(
                functionDeclaration.response,
                response.returnValue,
            )

            is ExecuteAppFunctionResponse.Error -> throw response.error
        }
    }

    @SuppressLint("RestrictedApi")
    private fun buildAppFunctionData(
        schema: FunctionSchema?,
        arguments: Map<String, JsonElement>,
    ): AppFunctionData {
        if (schema == null || schema.properties.isEmpty()) return AppFunctionData.EMPTY

        return AppFunctionData.Builder("").apply {
            schema.properties.forEach { (paramName, paramSchema) ->
                val jsonValue = arguments[paramName]
                if (jsonValue != null && jsonValue !is JsonNull) {
                    setValueOnBuilder(this, paramName, paramSchema, jsonValue)
                } else if (schema.required.contains(paramName) && !paramSchema.nullable) {
                    throw IllegalArgumentException("Missing required parameter: $paramName")
                }
            }
        }.build()
    }

    private fun setValueOnBuilder(
        builder: AppFunctionData.Builder,
        key: String,
        schema: FunctionSchema,
        value: JsonElement,
    ) {
        try {
            when (schema.type) {
                DataType.STRING -> builder.setString(key, (value as JsonPrimitive).content)
                DataType.INT -> builder.setInt(key, (value as JsonPrimitive).int)
                DataType.LONG -> builder.setLong(key, (value as JsonPrimitive).long)
                DataType.BOOLEAN -> builder.setBoolean(key, (value as JsonPrimitive).boolean)
                DataType.FLOAT -> builder.setFloat(key, (value as JsonPrimitive).float)
                DataType.DOUBLE -> builder.setDouble(key, (value as JsonPrimitive).double)
                DataType.OBJECT -> {
                    val subObject = value as JsonObject
                    builder.setAppFunctionData(
                        key,
                        buildAppFunctionData(schema, subObject),
                    )
                }

                DataType.ARRAY -> setArrayValueOnBuilder(builder, key, schema, value as JsonArray)
                else -> throw IllegalArgumentException("Unsupported data type: ${schema.type} for key '$key'")
            }
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "Failed to parse argument '$key' for type ${schema.type}. Reason: ${e.message}",
                e,
            )
        }
    }

    private fun setArrayValueOnBuilder(
        builder: AppFunctionData.Builder,
        key: String,
        schema: FunctionSchema,
        value: JsonArray,
    ) {
        val itemsSchema = schema.items
            ?: throw IllegalStateException("Array schema for '$key' is missing 'items' definition.")

        val primitiveList = value.map { it as JsonPrimitive }

        when (itemsSchema.type) {
            DataType.STRING -> builder.setStringList(key, primitiveList.map { it.content })
            DataType.INT -> builder.setIntArray(key, primitiveList.map { it.int }.toIntArray())
            DataType.LONG -> builder.setLongArray(key, primitiveList.map { it.long }.toLongArray())
            DataType.OBJECT -> {
                val objectList = value.map {
                    buildAppFunctionData(itemsSchema, it as JsonObject)
                }
                builder.setAppFunctionDataList(key, objectList)
            }

            else -> throw IllegalArgumentException("Unsupported array item type: ${itemsSchema.type} for key '$key'")
        }
    }

    private fun parseSuccessResponse(
        responseSchema: FunctionSchema?,
        returnValueContainer: AppFunctionData,
    ): JsonElement {
        if (responseSchema == null || responseSchema.type == DataType.UNIT) return JsonNull

        val returnValueKey = ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE
        if (!returnValueContainer.containsKey(returnValueKey)) return JsonNull

        return getValueFromDataObject(returnValueContainer, returnValueKey, responseSchema)
    }

    private fun convertDataObjectToMap(
        dataObject: AppFunctionData,
        schema: FunctionSchema,
    ): JsonObject = buildJsonObject {
        schema.properties.forEach { (propName, propSchema) ->
            val jsonValue = getValueFromDataObject(dataObject, propName, propSchema)
            if (jsonValue !is JsonNull) {
                put(propName, jsonValue)
            } else {
                if (propName in schema.required) {
                    Log.w(TAG, "Property $propName $propSchema is missing in the function data")
                }
            }
        }
    }

    private fun getValueFromDataObject(
        data: AppFunctionData,
        key: String,
        schema: FunctionSchema,
    ): JsonElement {
        if (!data.containsKey(key)) return JsonNull

        return when (schema.type) {
            DataType.STRING -> JsonPrimitive(data.getString(key))
            DataType.INT -> JsonPrimitive(data.getInt(key))
            DataType.LONG -> JsonPrimitive(data.getLong(key))
            DataType.BOOLEAN -> JsonPrimitive(data.getBoolean(key))
            DataType.FLOAT -> JsonPrimitive(data.getFloat(key))
            DataType.DOUBLE -> JsonPrimitive(data.getDouble(key))
            DataType.OBJECT -> data.getAppFunctionData(key)
                ?.let { convertDataObjectToMap(it, schema) } ?: JsonNull

            DataType.ARRAY -> getArrayFromDataObject(data, key, schema) ?: JsonNull
            else -> throw IllegalArgumentException("Unsupported item type for parsing: ${schema.type}")
        }
    }

    private fun getArrayFromDataObject(
        data: AppFunctionData,
        key: String,
        schema: FunctionSchema,
    ): JsonArray? {
        val itemsSchema = schema.items
            ?: throw IllegalStateException("Array schema for '$key' is missing 'items' definition.")

        val elements = when (itemsSchema.type) {
            DataType.STRING -> data.getStringList(key)?.map { JsonPrimitive(it) }
            DataType.INT -> data.getIntArray(key)?.map { JsonPrimitive(it) }
            DataType.LONG -> data.getLongArray(key)?.map { JsonPrimitive(it) }
            DataType.OBJECT -> data.getAppFunctionDataList(key)?.map {
                convertDataObjectToMap(it, itemsSchema)
            }

            else -> throw IllegalArgumentException("Unsupported array item type for parsing: ${itemsSchema.type}")
        }

        return elements?.let { buildJsonArray { it.forEach(::add) } }
    }
}
