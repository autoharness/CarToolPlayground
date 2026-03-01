package org.autoharness.cartoolplayground.inference.litertlm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.autoharness.cartoolplayground.inference.api.FunctionCall
import org.autoharness.cartoolplayground.inference.api.FunctionDefinition
import org.autoharness.cartoolplayground.inference.api.FunctionResponse
import org.autoharness.cartoolplayground.inference.api.LlmInferenceEngine
import org.autoharness.cartoolplayground.inference.api.LlmInferenceOptions
import org.autoharness.cartoolplayground.inference.api.LlmResponse
import kotlin.random.Random

class LiteRtLmInference(private val context: Context) : LlmInferenceEngine {
    companion object {
        private const val TAG = "LiteRtLmInference"
    }

    private data class LlmModelInstance(
        val engine: Engine,
        var session: Conversation?,
        val options: LlmInferenceOptions,
    )

    private val singleThreadDispatcher = Dispatchers.IO.limitedParallelism(1)

    private var instance: LlmModelInstance? = null

    override suspend fun load(options: LlmInferenceOptions) {
        withContext(singleThreadDispatcher) {
            cleanUp()
            val preferredBackend = Backend.CPU

            val engine = Engine(
                EngineConfig(
                    modelPath = options.modelPath,
                    backend = preferredBackend,
                    visionBackend = null,
                    audioBackend = null,
                    maxNumTokens = options.maxTokens,
                    cacheDir = context.externalCacheDir?.path,
                ),
            )
            engine.initialize()
            instance = LlmModelInstance(
                engine = engine,
                session = null,
                options = options,
            )
        }
    }

    override suspend fun unload() {
        withContext(singleThreadDispatcher) {
            cleanUp()
        }
    }

    private fun cleanUp() {
        instance?.let {
            try {
                it.session?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to close the LLM Inference session", e)
            }
            try {
                it.engine.close()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to close the LLM Inference engine", e)
            }
            instance = null
        }
    }

    private fun createNewSession(instance: LlmModelInstance): Conversation {
        instance.session?.close()

        val tools = if (instance.options.tools != null) {
            instance.options.tools.map { def ->
                tool(LiteRtFunctionTool(def))
            }
        } else {
            emptyList()
        }

        val sessionConfig =
            ConversationConfig(
                systemInstruction = instance.options.systemPrompt?.let { Contents.of(it) },
                tools = tools,
                samplerConfig = SamplerConfig(
                    topK = instance.options.topK,
                    topP = instance.options.topP.toDouble(),
                    temperature = instance.options.temperature.toDouble(),
                    // Make the random sampling non-deterministic.
                    seed = if (instance.options.temperature != 0.0f) Random.nextInt() else 0,
                ),
                automaticToolCalling = false,
            )

        return instance.engine.createConversation(sessionConfig).also {
            instance.session = it
        }
    }

    override suspend fun sendMessage(prompt: String): LlmResponse = sendChatMessage(Message.user(Contents.of(prompt)))

    override suspend fun sendFunctionCallResults(results: List<FunctionResponse>): LlmResponse {
        val toolResponses = results.map { result ->
            Content.ToolResponse(result.name, result.response.toString())
        }
        return sendChatMessage(Message.tool(Contents.of(toolResponses)))
    }

    private suspend fun sendChatMessage(message: Message): LlmResponse = withContext(singleThreadDispatcher) {
        val currentInstance = checkNotNull(instance) { "LiteRT-LM model not loaded." }
        val session = currentInstance.session ?: createNewSession(currentInstance)
        session.sendMessage(message).toLlmResponse()
    }

    private fun Message.toLlmResponse(): LlmResponse = if (this.toolCalls.isNotEmpty()) {
        val pendingCalls = this.toolCalls.map { toolCall ->
            val argsMap = toolCall.arguments.mapValues { it.value.toJsonElement() }
            FunctionCall(name = toolCall.name, args = argsMap)
        }
        LlmResponse.PendingFunctionCalls(pendingCalls)
    } else {
        LlmResponse.TextContent(this.toString())
    }

    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is String -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Map<*, *> -> JsonObject(this.entries.associate { it.key.toString() to it.value.toJsonElement() })
        is Iterable<*> -> JsonArray(this.map { it.toJsonElement() })
        is Array<*> -> JsonArray(this.map { it.toJsonElement() })
        else -> JsonPrimitive(this.toString())
    }

    private class LiteRtFunctionTool(
        private val definition: FunctionDefinition,
    ) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = definition.toJsonString()

        override fun execute(paramsJsonString: String): String = throw UnsupportedOperationException("execute shall not be invoked when automaticToolCalling=false")
    }
}
