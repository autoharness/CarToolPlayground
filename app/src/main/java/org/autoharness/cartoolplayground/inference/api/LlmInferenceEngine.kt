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

    /**
     * A response indicating the LLM wants to call one or more functions.
     *
     * @property calls The list of function calls requested by the LLM.
     */
    data class PendingFunctionCalls(val calls: List<FunctionCall>) : LlmResponse
}

/**
 * Defines the contract for an LLM inference engine.
 */
interface LlmInferenceEngine {
    /**
     * Loads and initializes the model with the given options.
     *
     * @param options The configuration for the LLM.
     */
    suspend fun load(options: LlmInferenceOptions)

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

    /**
     * Sends the result of function calls back to the model.
     *
     * @param results The result from the function calls.
     * @return The model's next response after receiving the function results.
     */
    suspend fun sendFunctionCallResults(results: List<FunctionResponse>): LlmResponse
}
