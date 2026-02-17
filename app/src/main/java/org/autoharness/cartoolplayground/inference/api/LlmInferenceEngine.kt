package org.autoharness.cartoolplayground.inference.api

/**
 * Represents a response from a Large Language Model (LLM).
 */
sealed interface LlmResponse {
    /**
     * A simple text response from the LLM.
     *
     * @property text The content of the text response.
     */
    data class TextContent(val text: String) : LlmResponse
}

/**
 * Interface for handling tool/function calls triggered by the LLM.
 */
interface FunctionCallback {
    /**
     * Processes a [FunctionCall] and returns the corresponding [FunctionResponse].
     *
     * @param call The function name and arguments provided by the LLM.
     * @return The result of the function execution to be sent back to the LLM.
     */
    suspend fun execute(call: FunctionCall): FunctionResponse
}

/**
 * Defines the contract for an LLM inference engine.
 */
interface LlmInferenceEngine {
    /**
     * Loads and initializes the model with the given options.
     *
     * @param options The configuration for the LLM.
     * @param callback The handler of the function calling.
     */
    suspend fun load(options: LlmInferenceOptions, callback: FunctionCallback?)

    /**
     * Unloads the model and releases its resources.
     */
    suspend fun unload()

    /**
     * Sends a user prompt to the loaded model.
     *
     * @param prompt The user's text message.
     * @return The model's response.
     */
    suspend fun sendMessage(prompt: String): LlmResponse
}
