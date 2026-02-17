package org.autoharness.cartoolplayground.inference.api

data class LlmInferenceOptions(
    /** Path to the model for the task. */
    val modelPath: String = "",
    /** The total number of input + output tokens the model needs to handle. */
    val maxTokens: Int = 15000,
    /** Top-K number of tokens to be sampled from for each decoding step. */
    val topK: Int = 40,
    /** Top-P (nucleus) sampling parameter. */
    val topP: Float = 1.0f,
    /** Randomness when decoding the next token. A value of 0.0f means greedy decoding. */
    val temperature: Float = 0.8f,
    /** Turns on or disables the thinking process. */
    val thinkingMode: Boolean = true,
    /** Initial instruction given to the model to set its behavior, role, and constraints for the entire conversation. */
    val systemPrompt: String? = null,
    /** List of [FunctionDefinition]s that the model can call to perform actions or retrieve external information. */
    val tools: List<FunctionDefinition>? = null,
) {
    init {
        require(maxTokens > 0) { "maxTokens must be positive." }
        require(topK > 0) { "topK must be positive." }
        require(topP in 0.0f..1.0f) { "topP must be in the range [0.0, 1.0]." }
        require(temperature in 0.0f..2.0f) { "temperature must be in the range [0.0, 2.0]." }
    }
}
