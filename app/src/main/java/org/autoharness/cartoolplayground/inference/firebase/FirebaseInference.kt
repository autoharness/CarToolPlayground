package org.autoharness.cartoolplayground.inference.firebase

import com.google.firebase.Firebase
import com.google.firebase.ai.Chat
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerateContentResponse
import com.google.firebase.ai.type.GenerationConfig
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.thinkingConfig
import org.autoharness.cartoolplayground.inference.api.DataType
import org.autoharness.cartoolplayground.inference.api.FunctionCall
import org.autoharness.cartoolplayground.inference.api.FunctionDefinition
import org.autoharness.cartoolplayground.inference.api.FunctionResponse
import org.autoharness.cartoolplayground.inference.api.FunctionSchema
import org.autoharness.cartoolplayground.inference.api.LlmInferenceEngine
import org.autoharness.cartoolplayground.inference.api.LlmInferenceOptions
import org.autoharness.cartoolplayground.inference.api.LlmResponse

class FirebaseInference : LlmInferenceEngine {
    companion object {
        // Supported models and rate limits:
        // https://ai.google.dev/gemini-api/docs/models
        // https://firebase.google.com/docs/ai-logic/models
        // https://firebase.google.com/docs/ai-logic/quotas
        private const val MODEL_NAME = "gemini-2.5-flash-lite-preview-09-2025"
    }

    private var chatSession: Chat? = null

    override suspend fun load(options: LlmInferenceOptions) {
        val generativeModel = buildGenerativeModel(options)
        chatSession = generativeModel.startChat()
    }

    private fun buildGenerativeModel(options: LlmInferenceOptions): GenerativeModel {
        val generationConfig = GenerationConfig.builder().apply {
            maxOutputTokens = options.maxTokens
            topK = options.topK
            topP = options.topP
            temperature = options.temperature
            thinkingConfig = thinkingConfig {
                thinkingBudget = if (options.thinkingMode) -1 else 0
                includeThoughts = false
            }
        }.build()

        val firebaseTools = options.tools
            ?.takeIf { it.isNotEmpty() }
            ?.let { ToolMapper.mapToFirebaseTools(it) }

        return Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = MODEL_NAME,
            systemInstruction = options.systemPrompt?.let { content { text(it) } },
            generationConfig = generationConfig,
            tools = firebaseTools?.let { listOf(Tool.functionDeclarations(it)) },
        )
    }

    override suspend fun unload() {
        chatSession = null
    }

    override suspend fun sendMessage(prompt: String): LlmResponse = sendChatMessage(content { text(prompt) })

    override suspend fun sendFunctionCallResults(results: List<FunctionResponse>): LlmResponse {
        val message = content("function") {
            results.forEach { result ->
                part(FunctionResponsePart(result.name, result.response))
            }
        }
        return sendChatMessage(message)
    }

    private suspend fun sendChatMessage(message: Content): LlmResponse = requireNotNull(chatSession) { "Model is not loaded." }
        .sendMessage(message)
        .toLlmResponse()

    private fun GenerateContentResponse.toLlmResponse(): LlmResponse = if (functionCalls.isNotEmpty()) {
        LlmResponse.PendingFunctionCalls(
            functionCalls.map { FunctionCall(name = it.name, args = it.args) },
        )
    } else {
        LlmResponse.TextContent(text ?: "")
    }
}

private object ToolMapper {

    fun mapToFirebaseTools(definitions: List<FunctionDefinition>): List<FunctionDeclaration> = definitions.map { definition ->
        val params = definition.parameters
        require(params != null && params.type == DataType.OBJECT) {
            "Tool '${definition.shortName}' parameters must be a non-null schema of type OBJECT."
        }

        val firebaseParameters = params.properties.mapValues { (_, schema) ->
            mapToFirebaseSchema(schema)
        }

        val optionalParameters = params.properties.keys
            .minus(params.required.toSet())
            .toList()

        FunctionDeclaration(
            name = definition.shortName,
            description = definition.description,
            parameters = firebaseParameters,
            optionalParameters = optionalParameters,
        )
    }

    private fun mapToFirebaseSchema(functionSchema: FunctionSchema): Schema {
        val description = functionSchema.description.takeIf { it.isNotBlank() }
        val isNullable = functionSchema.nullable

        return when (functionSchema.type) {
            DataType.STRING -> if (functionSchema.enum.isNotEmpty()) {
                Schema.enumeration(
                    values = functionSchema.enum.map { it.toString() },
                    description = description,
                    nullable = isNullable,
                )
            } else {
                Schema.string(description = description, nullable = isNullable)
            }

            DataType.INT -> Schema.integer(description = description, nullable = isNullable)
            DataType.LONG -> Schema.long(description = description, nullable = isNullable)
            DataType.DOUBLE -> Schema.double(description = description, nullable = isNullable)
            DataType.FLOAT -> Schema.float(description = description, nullable = isNullable)
            DataType.BOOLEAN -> Schema.boolean(description = description, nullable = isNullable)
            DataType.OBJECT -> {
                val mappedProperties = functionSchema.properties.mapValues { (_, schema) ->
                    mapToFirebaseSchema(schema)
                }
                val optional = functionSchema.properties.keys
                    .minus(functionSchema.required.toSet())
                    .toList()
                Schema.obj(
                    properties = mappedProperties,
                    optionalProperties = optional,
                    description = description,
                    nullable = isNullable,
                )
            }

            DataType.ARRAY -> {
                val itemSchema = requireNotNull(functionSchema.items) {
                    "Array type must have an 'items' schema defined."
                }
                Schema.array(
                    items = mapToFirebaseSchema(itemSchema),
                    description = description,
                    nullable = isNullable,
                )
            }

            DataType.UNSPECIFIED, DataType.UNIT ->
                throw IllegalArgumentException("Unsupported data type for schema conversion: ${functionSchema.type}")
        }
    }
}
