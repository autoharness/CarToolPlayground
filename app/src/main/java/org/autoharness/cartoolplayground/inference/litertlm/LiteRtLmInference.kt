package org.autoharness.cartoolplayground.inference.litertlm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.autoharness.cartoolplayground.inference.api.FunctionCall
import org.autoharness.cartoolplayground.inference.api.FunctionCallback
import org.autoharness.cartoolplayground.inference.api.FunctionDefinition
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
        val callback: FunctionCallback?,
    )

    private var instance: LlmModelInstance? = null

    override suspend fun load(options: LlmInferenceOptions, callback: FunctionCallback?) {
        withContext(Dispatchers.IO) {
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
                callback = callback,
            )
        }
    }

    override suspend fun unload() {
        withContext(Dispatchers.IO) {
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

        val tools = if (instance.options.tools != null && instance.callback != null) {
            instance.options.tools.map { def ->
                tool(LiteRtFunctionTool(def, instance.callback))
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
            )

        return instance.engine.createConversation(sessionConfig).also {
            instance.session = it
        }
    }

    override suspend fun sendMessage(prompt: String): LlmResponse = withContext(Dispatchers.IO) {
        val currentInstance = checkNotNull(instance) { "LiteRT-LM model not loaded." }
        val session = currentInstance.session ?: createNewSession(currentInstance)
        val response = session.sendMessage(prompt)
        LlmResponse.TextContent(response.toString())
    }

    private class LiteRtFunctionTool(
        private val definition: FunctionDefinition,
        private val callback: FunctionCallback,
    ) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = definition.toJsonString()

        override fun execute(paramsJsonString: String): String = runBlocking {
            try {
                val jsonElement = Json.parseToJsonElement(paramsJsonString)
                val args = jsonElement.jsonObject

                val functionCall = FunctionCall(
                    name = definition.shortName,
                    args = args,
                )

                val result = callback.execute(functionCall)
                result.response.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Error executing tool ${definition.shortName}", e)
                JsonObject(mapOf("error" to JsonPrimitive(e.message))).toString()
            }
        }
    }
}
