package org.autoharness.cartoolplayground.ui.chat

import androidx.appfunctions.metadata.AppFunctionArrayTypeMetadata
import androidx.appfunctions.metadata.AppFunctionBooleanTypeMetadata
import androidx.appfunctions.metadata.AppFunctionComponentsMetadata
import androidx.appfunctions.metadata.AppFunctionDataTypeMetadata
import androidx.appfunctions.metadata.AppFunctionDoubleTypeMetadata
import androidx.appfunctions.metadata.AppFunctionFloatTypeMetadata
import androidx.appfunctions.metadata.AppFunctionIntTypeMetadata
import androidx.appfunctions.metadata.AppFunctionLongTypeMetadata
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionObjectTypeMetadata
import androidx.appfunctions.metadata.AppFunctionParameterMetadata
import androidx.appfunctions.metadata.AppFunctionReferenceTypeMetadata
import androidx.appfunctions.metadata.AppFunctionStringTypeMetadata
import androidx.appfunctions.metadata.AppFunctionUnitTypeMetadata
import org.autoharness.cartoolplayground.inference.api.DataType
import org.autoharness.cartoolplayground.inference.api.FunctionDefinition
import org.autoharness.cartoolplayground.inference.api.FunctionSchema

fun List<AppFunctionMetadata>.toFunctionDefinitions(): Map<FunctionDefinition, AppFunctionMetadata> = this.associateBy { it.toFunctionDefinition() }

private fun AppFunctionMetadata.toFunctionDefinition(): FunctionDefinition = FunctionDefinition(
    fullName = this.id,
    shortName = this.id.substringAfterLast("#"),
    description = this.description,
    parameters = this.toParametersSchema(),
    response = this.response.valueType.toSchema(this.components),
)

private fun AppFunctionMetadata.toParametersSchema(): FunctionSchema? {
    if (parameters.isEmpty()) return null

    // Parameters are wrapped into a single, top-level object.
    return createObjectSchema(parameters, components)
}

private fun createObjectSchema(
    params: List<AppFunctionParameterMetadata>,
    components: AppFunctionComponentsMetadata,
): FunctionSchema = FunctionSchema(
    type = DataType.OBJECT,
    properties = params.associate { param ->
        val baseSchema = param.dataType.toSchema(components)
        // An argument, use the description of parameter level.
        param.name to baseSchema.copy(description = param.description)
    },
    required = params.filter { it.isRequired }.map { it.name },
)

private fun AppFunctionDataTypeMetadata.toSchema(
    components: AppFunctionComponentsMetadata,
): FunctionSchema = when (this) {
    // Primitives
    is AppFunctionIntTypeMetadata -> createPrimitiveSchema(
        DataType.INT,
        description,
        isNullable,
        enumValues?.toList(),
    )

    is AppFunctionLongTypeMetadata -> createPrimitiveSchema(DataType.LONG, description, isNullable)
    is AppFunctionFloatTypeMetadata -> createPrimitiveSchema(
        DataType.FLOAT,
        description,
        isNullable,
    )

    is AppFunctionDoubleTypeMetadata -> createPrimitiveSchema(
        DataType.DOUBLE,
        description,
        isNullable,
    )

    is AppFunctionBooleanTypeMetadata -> createPrimitiveSchema(
        DataType.BOOLEAN,
        description,
        isNullable,
    )

    is AppFunctionStringTypeMetadata -> createPrimitiveSchema(
        DataType.STRING,
        description,
        isNullable,
        enumValues?.toList(),
    )

    is AppFunctionUnitTypeMetadata -> createPrimitiveSchema(DataType.UNIT, description, isNullable)

    // Complex Types
    is AppFunctionArrayTypeMetadata -> FunctionSchema(
        type = DataType.ARRAY,
        description = this.description,
        nullable = this.isNullable,
        items = this.itemType.toSchema(components),
    )

    is AppFunctionObjectTypeMetadata -> FunctionSchema(
        type = DataType.OBJECT,
        description = this.description,
        nullable = this.isNullable,
        properties = this.properties.mapValues { (_, value) -> value.toSchema(components) },
        required = this.required,
    )

    is AppFunctionReferenceTypeMetadata -> {
        val resolvedType = components.dataTypes[this.referenceDataType]
            ?: throw IllegalStateException("Reference to ${this.referenceDataType} not found.")
        resolvedType.toSchema(components)
    }

    else -> throw IllegalStateException("Unexpected data type: $this")
}

private fun createPrimitiveSchema(
    type: DataType,
    description: String,
    nullable: Boolean,
    enumValues: List<Any>? = null,
) = FunctionSchema(
    type = type,
    description = description,
    nullable = nullable,
    enum = enumValues ?: emptyList(),
)
