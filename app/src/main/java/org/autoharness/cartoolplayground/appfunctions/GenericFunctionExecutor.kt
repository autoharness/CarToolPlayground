package org.autoharness.cartoolplayground.appfunctions

import android.util.Log
import androidx.appfunctions.AppFunctionData
import androidx.appfunctions.AppFunctionManagerCompat
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.appfunctions.metadata.AppFunctionArrayTypeMetadata
import androidx.appfunctions.metadata.AppFunctionComponentsMetadata
import androidx.appfunctions.metadata.AppFunctionDataTypeMetadata
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionObjectTypeMetadata
import androidx.appfunctions.metadata.AppFunctionParameterMetadata
import androidx.appfunctions.metadata.AppFunctionReferenceTypeMetadata
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
        appFunctionMetadata: AppFunctionMetadata,
        functionDefinition: FunctionDefinition,
        arguments: Map<String, JsonElement>,
    ): Result<JsonElement> = Result.runCatching {
        if (!manager.isAppFunctionEnabled(targetPackageName, functionDefinition.fullName)) {
            throw IllegalStateException("Function (${functionDefinition.fullName}) is disabled")
        }

        val functionParameters = buildAppFunctionData(
            appFunctionMetadata.parameters,
            appFunctionMetadata.components,
            functionDefinition.parameters,
            arguments,
        )
        val request = ExecuteAppFunctionRequest(
            functionIdentifier = functionDefinition.fullName,
            targetPackageName = targetPackageName,
            functionParameters = functionParameters,
        )

        when (val response = manager.executeAppFunction(request)) {
            is ExecuteAppFunctionResponse.Success -> parseSuccessResponse(
                functionDefinition.response,
                response.returnValue,
            )

            is ExecuteAppFunctionResponse.Error -> throw response.error
        }
    }

    private fun buildAppFunctionData(
        appFunctionParameterMetadataList: List<AppFunctionParameterMetadata>,
        appFunctionComponentsMetadata: AppFunctionComponentsMetadata,
        schema: FunctionSchema?,
        arguments: Map<String, JsonElement>,
    ): AppFunctionData {
        if (schema == null || schema.properties.isEmpty()) return AppFunctionData.EMPTY

        val appFunctionParameterMetadataMap =
            appFunctionParameterMetadataList.associateBy { it.name }
        val builder = AppFunctionData.Builder(
            appFunctionParameterMetadataList,
            appFunctionComponentsMetadata,
        )
        return populateBuilderFromSchema(
            builder = builder,
            components = appFunctionComponentsMetadata,
            schema = schema,
            arguments = arguments,
            metadataProvider = { paramName ->
                appFunctionParameterMetadataMap[paramName]?.dataType
                    ?: throw IllegalStateException("Failed to find AppFunctionParameterMetadata for parameter $paramName")
            },
        )
    }

    private fun buildAppFunctionData(
        appFunctionObjectTypeMetadata: AppFunctionObjectTypeMetadata,
        appFunctionComponentsMetadata: AppFunctionComponentsMetadata,
        schema: FunctionSchema,
        arguments: Map<String, JsonElement>,
    ): AppFunctionData {
        val builder = AppFunctionData.Builder(
            appFunctionObjectTypeMetadata,
            appFunctionComponentsMetadata,
        )

        return populateBuilderFromSchema(
            builder = builder,
            components = appFunctionComponentsMetadata,
            schema = schema,
            arguments = arguments,
            metadataProvider = { propName ->
                appFunctionObjectTypeMetadata.properties[propName]
                    ?: throw IllegalStateException("Failed to find AppFunctionDataTypeMetadata for property $propName")
            },
        )
    }

    private fun populateBuilderFromSchema(
        builder: AppFunctionData.Builder,
        components: AppFunctionComponentsMetadata,
        schema: FunctionSchema,
        arguments: Map<String, JsonElement>,
        metadataProvider: (String) -> AppFunctionDataTypeMetadata,
    ): AppFunctionData {
        schema.properties.forEach { (paramName, paramSchema) ->
            val jsonValue = arguments[paramName]

            if (jsonValue != null && jsonValue !is JsonNull) {
                val paramMetadata = metadataProvider(paramName)
                setValueOnBuilder(
                    appFunctionDataTypeMetadata = paramMetadata,
                    appFunctionComponentsMetadata = components,
                    builder = builder,
                    key = paramName,
                    schema = paramSchema,
                    value = jsonValue,
                )
            } else if (schema.required.contains(paramName) && !paramSchema.nullable) {
                throw IllegalArgumentException("Missing required parameter: $paramName")
            }
        }
        return builder.build()
    }

    private fun setValueOnBuilder(
        appFunctionDataTypeMetadata: AppFunctionDataTypeMetadata,
        appFunctionComponentsMetadata: AppFunctionComponentsMetadata,
        builder: AppFunctionData.Builder,
        key: String,
        schema: FunctionSchema,
        value: JsonElement,
    ) {
        try {
            if (appFunctionDataTypeMetadata is AppFunctionReferenceTypeMetadata) {
                val resolvedType = resolveReference(appFunctionDataTypeMetadata, appFunctionComponentsMetadata)
                setValueOnBuilder(resolvedType, appFunctionComponentsMetadata, builder, key, schema, value)
                return
            }
            when (schema.type) {
                DataType.STRING -> builder.setString(key, (value as JsonPrimitive).content)
                DataType.INT -> builder.setInt(key, (value as JsonPrimitive).int)
                DataType.LONG -> builder.setLong(key, (value as JsonPrimitive).long)
                DataType.BOOLEAN -> builder.setBoolean(key, (value as JsonPrimitive).boolean)
                DataType.FLOAT -> builder.setFloat(key, (value as JsonPrimitive).float)
                DataType.DOUBLE -> builder.setDouble(key, (value as JsonPrimitive).double)
                DataType.OBJECT -> {
                    val appFunctionObjectTypeMetadata =
                        appFunctionDataTypeMetadata as? AppFunctionObjectTypeMetadata
                            ?: throw IllegalArgumentException("Metadata mismatch: Schema is OBJECT but metadata is not, $appFunctionDataTypeMetadata")

                    val subObject = value as JsonObject
                    builder.setAppFunctionData(
                        key,
                        buildAppFunctionData(
                            appFunctionObjectTypeMetadata,
                            appFunctionComponentsMetadata,
                            schema,
                            subObject,
                        ),
                    )
                }

                DataType.ARRAY -> {
                    val appFunctionArrayTypeMetadata =
                        appFunctionDataTypeMetadata as? AppFunctionArrayTypeMetadata
                            ?: throw IllegalStateException("Metadata mismatch: Schema is ARRAY but metadata is not, $appFunctionDataTypeMetadata")

                    setArrayValueOnBuilder(
                        appFunctionDataTypeMetadata = appFunctionArrayTypeMetadata.itemType,
                        appFunctionComponentsMetadata = appFunctionComponentsMetadata,
                        builder = builder,
                        key = key,
                        schema = schema,
                        value = value as JsonArray,
                    )
                }

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
        appFunctionDataTypeMetadata: AppFunctionDataTypeMetadata,
        appFunctionComponentsMetadata: AppFunctionComponentsMetadata,
        builder: AppFunctionData.Builder,
        key: String,
        schema: FunctionSchema,
        value: JsonArray,
    ) {
        val itemsSchema = schema.items
            ?: throw IllegalStateException("Array schema for '$key' is missing 'items' definition.")
        if (appFunctionDataTypeMetadata is AppFunctionReferenceTypeMetadata) {
            val resolvedItemType = resolveReference(appFunctionDataTypeMetadata, appFunctionComponentsMetadata)
            setArrayValueOnBuilder(resolvedItemType, appFunctionComponentsMetadata, builder, key, schema, value)
            return
        }
        val primitiveList = value.map { it as JsonPrimitive }

        when (itemsSchema.type) {
            DataType.STRING -> builder.setStringList(key, primitiveList.map { it.content })
            DataType.INT -> builder.setIntArray(key, primitiveList.map { it.int }.toIntArray())
            DataType.LONG -> builder.setLongArray(key, primitiveList.map { it.long }.toLongArray())
            DataType.OBJECT -> {
                val objectItemMetadata = appFunctionDataTypeMetadata as? AppFunctionObjectTypeMetadata
                    ?: throw IllegalArgumentException("Metadata mismatch: Array item is OBJECT but metadata is not, $appFunctionDataTypeMetadata")

                val objectList = value.map {
                    buildAppFunctionData(
                        objectItemMetadata,
                        appFunctionComponentsMetadata,
                        itemsSchema,
                        it as JsonObject,
                    )
                }
                builder.setAppFunctionDataList(key, objectList)
            }

            else -> throw IllegalArgumentException("Unsupported array item type: ${itemsSchema.type} for key '$key'")
        }
    }

    private fun resolveReference(
        ref: AppFunctionReferenceTypeMetadata,
        components: AppFunctionComponentsMetadata,
    ): AppFunctionDataTypeMetadata = components.dataTypes[ref.referenceDataType]
        ?: throw IllegalStateException("Reference to ${ref.referenceDataType} not found in components.")

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
