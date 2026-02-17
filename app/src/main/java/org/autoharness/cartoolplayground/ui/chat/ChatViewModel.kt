package org.autoharness.cartoolplayground.ui.chat

import android.app.Application
import android.util.Log
import androidx.appfunctions.AppFunctionManagerCompat
import androidx.appfunctions.AppFunctionSearchSpec
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.autoharness.cartoolplayground.appfunctions.GenericFunctionExecutor
import org.autoharness.cartoolplayground.data.property.CarPropertyProfile
import org.autoharness.cartoolplayground.inference.api.FunctionCall
import org.autoharness.cartoolplayground.inference.api.FunctionCallback
import org.autoharness.cartoolplayground.inference.api.FunctionDefinition
import org.autoharness.cartoolplayground.inference.api.FunctionResponse
import org.autoharness.cartoolplayground.inference.api.LlmInferenceEngine
import org.autoharness.cartoolplayground.inference.api.LlmInferenceOptions
import org.autoharness.cartoolplayground.inference.api.LlmResponse
import org.autoharness.cartoolplayground.inference.firebase.FirebaseInference
import org.autoharness.cartoolplayground.inference.litertlm.LiteRtLmInference
import org.autoharness.cartoolplayground.ui.chat.prompt.SYSTEM_PROMPT_TEMPLATE
import org.autoharness.cartoolplayground.ui.chat.prompt.VEHICLE_PROPERTY_LIST_PLACEHOLDER
import org.autoharness.cartoolplayground.ui.chat.prompt.VEHICLE_PROPERTY_SPECIFICATION_PLACEHOLDER
import org.autoharness.cartoolplayground.ui.chat.settings.InferenceEngine
import org.autoharness.cartoolplayground.ui.chat.settings.LlmSettings

/** Represents a single message in the chat. */
data class ChatMessage(
    val text: String,
    val type: MessageType,
) {
    enum class MessageType {
        MODEL,
        USER,
        DEBUG,
        WARNING,
    }
}

/**
 * ViewModel for the chat screen, responsible for managing the state,
 * loading the LLM, and handling user interactions.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "ChatViewModel"
        private const val TARGET_PACKAGE = "org.autoharness.cartool"
        private const val TARGET_SCHEMA_CATEGORY = "car-property-full"
        private const val FUNCTION_GET_PROPERTY_LIST = "getPropertyList"
        private const val DATASET_PATH = "dataset/test_set.csv"
        private const val AUTO_PLAY_MESSAGE_INTERVAL = 2600L
    }

    /** Sealed class for representing UI states clearly. */
    sealed class State {
        data object Uninitialized : State()

        data object Loading : State()

        data object Loaded : State()

        data object Generating : State()

        data class Error(val message: String?) : State()
    }

    private val _carPropertyProfiles = MutableSharedFlow<List<CarPropertyProfile>>()
    val carPropertyProfiles: SharedFlow<List<CarPropertyProfile>> =
        _carPropertyProfiles.asSharedFlow()

    private var llmEngine: LlmInferenceEngine? = null

    private var generationJob: Job? = null
    private var autoPlayJob: Job? = null

    private val _isAutoPlaying = MutableStateFlow(false)
    val isAutoPlaying: StateFlow<Boolean> = _isAutoPlaying.asStateFlow()

    // Holds the list of messages.
    private val _messages = mutableStateListOf<ChatMessage>()
    val messages: List<ChatMessage> = _messages

    private val _uiState = MutableStateFlow<State>(State.Uninitialized)
    val uiState: StateFlow<State> = _uiState.asStateFlow()

    private val _currentModelTag = MutableStateFlow("ChatBot")
    val currentModelTag: StateFlow<String> = _currentModelTag.asStateFlow()

    private val appFunctionManagerCompat: AppFunctionManagerCompat =
        AppFunctionManagerCompat.getInstance(application)
            ?: throw UnsupportedOperationException("App functions not supported on this device.")

    private val functionExecutor = GenericFunctionExecutor(appFunctionManagerCompat)

    private var availableFunctions: Map<String, Pair<FunctionDefinition, AppFunctionMetadata>> =
        emptyMap()

    private var llmSettings: LlmSettings = LlmSettings()
    private var systemPrompt: String = ""
    private var tools: Map<FunctionDefinition, AppFunctionMetadata> = emptyMap()
    private val functionCallback = object : FunctionCallback {
        override suspend fun execute(call: FunctionCall): FunctionResponse = handleFunctionCall(call)
    }

    fun startChat() {
        startInternal()
    }

    fun startAutoPlay() {
        autoPlayJob = viewModelScope.launch {
            try {
                startInternal()
                uiState.first { it is State.Loaded || it is State.Error }
                if (uiState.value == State.Loaded) {
                    val autoPlayQuestions = loadDataset()
                    _isAutoPlaying.value = true
                    for (question in autoPlayQuestions) {
                        sendMessage(question)
                        // Suspend here until the response has been generated.
                        uiState.first { it is State.Loaded }
                        delay(AUTO_PLAY_MESSAGE_INTERVAL)
                    }
                    _messages.add(ChatMessage("Auto-play finished.", ChatMessage.MessageType.DEBUG))
                }
            } finally {
                Log.v(TAG, "Auto play job quited")
                _isAutoPlaying.value = false
                if (_uiState.value is State.Generating) {
                    generationJob?.cancel()
                    _uiState.value = State.Loaded
                }
            }
        }
    }

    fun stopAutoPlay() {
        autoPlayJob?.cancel()
    }

    fun updateSettings(settings: LlmSettings) {
        val onlyShownDebugInfoChanged = llmSettings.onlyShowDebugInfoChanged(settings)
        llmSettings = settings
        if (_uiState.value != State.Uninitialized && systemPrompt.isNotBlank() && tools.isNotEmpty() && !onlyShownDebugInfoChanged) {
            viewModelScope.launch {
                generationJob?.cancel()
                autoPlayJob?.cancel()
                releaseEngine()
                loadModel(llmSettings, systemPrompt, tools)
            }
        }
    }

    private fun LlmSettings.onlyShowDebugInfoChanged(other: LlmSettings): Boolean = this.copy(showDebugInfo = other.showDebugInfo) == other

    private fun loadDataset(): List<String> {
        val questions = mutableListOf<String>()
        val assetManager = getApplication<Application>().assets
        val inputStream = assetManager.open(DATASET_PATH)

        inputStream.bufferedReader().useLines { lines ->
            lines.drop(1) // Skip header
                .filter { it.isNotBlank() }
                .map { it.trim().removeSurrounding("\"") }
                .forEach { q ->
                    questions.add(q)
                }
        }
        return questions
    }

    private suspend fun loadModel(
        settings: LlmSettings,
        systemPrompt: String?,
        tools: Map<FunctionDefinition, AppFunctionMetadata>?,
    ) {
        _uiState.value = State.Loading
        try {
            val engine = when (settings.engine) {
                InferenceEngine.FIREBASE -> FirebaseInference()
                InferenceEngine.LITE_RT_LM -> LiteRtLmInference(getApplication())
            }

            llmEngine = engine.apply {
                load(
                    LlmInferenceOptions(
                        modelPath = settings.modelPath,
                        maxTokens = settings.maxTokens,
                        topK = settings.topK,
                        topP = settings.topP,
                        temperature = settings.temperature,
                        systemPrompt = systemPrompt,
                        tools = tools?.keys?.toList(),
                    ),
                    functionCallback,
                )
                availableFunctions = tools?.entries?.associate {
                    it.key.shortName to (it.key to it.value)
                } ?: emptyMap()

                _currentModelTag.value = when (settings.engine) {
                    InferenceEngine.FIREBASE -> "firebase-ai"
                    InferenceEngine.LITE_RT_LM -> "litert-lm"
                }
            }
            _uiState.value = State.Loaded
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            _uiState.value = State.Error(e.message)
        }
    }

    fun sendMessage(userInput: String) {
        generationJob?.cancel()

        // Start generating a response.
        _uiState.value = State.Generating
        generationJob = viewModelScope.launch {
            _messages.add(ChatMessage(userInput, type = ChatMessage.MessageType.USER))

            runLlmInteractionLoop(userInput)
            _uiState.value = State.Loaded
        }
    }

    private suspend fun runLlmInteractionLoop(initialPrompt: String) {
        val currentInstance = checkNotNull(llmEngine) { "Engine not loaded." }

        try {
            when (val currentResponse = currentInstance.sendMessage(initialPrompt)) {
                is LlmResponse.TextContent -> {
                    _messages.add(
                        ChatMessage(
                            currentResponse.text,
                            type = ChatMessage.MessageType.MODEL,
                        ),
                    )
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during LLM loop", e)
            _messages.add(
                ChatMessage(
                    e.message ?: "error happen",
                    type = ChatMessage.MessageType.WARNING,
                ),
            )
        }
    }

    private suspend fun handleFunctionCall(functionCall: FunctionCall): FunctionResponse {
        _messages.add(
            ChatMessage(
                "Function: ${functionCall.name} args: ${functionCall.args}",
                type = ChatMessage.MessageType.DEBUG,
            ),
        )
        val response = availableFunctions[functionCall.name]?.let { functionData ->
            val (definition, metadata) = functionData
            executeAppFunction(
                function = definition,
                metadata = metadata,
                arguments = functionCall.args,
            ).fold(
                onSuccess = { result ->
                    FunctionResponse(functionCall.name, createSuccessResponse(result))
                },
                onFailure = { error ->
                    Log.w(TAG, "Function '${functionCall.name}' failed", error)
                    val errorJson =
                        createErrorResponse(error.message ?: "Unknown execution error")
                    FunctionResponse(functionCall.name, errorJson)
                },
            )
        } ?: run {
            Log.w(TAG, "Model responded with unknown function call: ${functionCall.name}")
            val errorJson =
                createErrorResponse("Function '${functionCall.name}' is not defined.")
            FunctionResponse(functionCall.name, errorJson)
        }
        _messages.add(
            ChatMessage(
                response.toString(),
                type = ChatMessage.MessageType.DEBUG,
            ),
        )
        return response
    }

    private fun createSuccessResponse(result: JsonElement): JsonObject = if (result is JsonObject) {
        result
    } else {
        buildJsonObject { put("result", result) }
    }

    private fun createErrorResponse(message: String): JsonObject = buildJsonObject {
        put("error", JsonPrimitive(message))
    }

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
        stopAutoPlay()
        viewModelScope.launch {
            releaseEngine()
        }
    }

    private suspend fun releaseEngine() {
        llmEngine?.unload()
        llmEngine = null
        _messages.clear()
        _currentModelTag.value = ""
        _uiState.value = State.Uninitialized
    }

    private fun startInternal() {
        viewModelScope.launch {
            val searchSpec = AppFunctionSearchSpec(
                packageNames = setOf(TARGET_PACKAGE),
                schemaCategory = TARGET_SCHEMA_CATEGORY,
            )
            appFunctionManagerCompat
                .observeAppFunctions(searchSpec)
                .collect { packageList ->
                    packageList.firstOrNull()?.let { metadata ->
                        runCatching {
                            Log.i(TAG, "Received ${metadata.appFunctions.size} functions")
                            val toolDefinitionsMap = metadata.appFunctions.toFunctionDefinitions()

                            val getPropertyListToolEntry =
                                toolDefinitionsMap.entries.find { it.key.shortName == FUNCTION_GET_PROPERTY_LIST }
                                    ?: throw IllegalStateException("Required tool '$FUNCTION_GET_PROPERTY_LIST' not found.")

                            val getPropertyListDef = getPropertyListToolEntry.key
                            val getPropertyListMetadata = getPropertyListToolEntry.value

                            val propertiesJson =
                                executeGetPropertyList(getPropertyListDef, getPropertyListMetadata)
                                    ?: throw IllegalStateException("Execution of '$FUNCTION_GET_PROPERTY_LIST' failed or returned null.")

                            _carPropertyProfiles.emit(
                                Json.decodeFromString<List<CarPropertyProfile>>(
                                    propertiesJson,
                                ),
                            )
                            systemPrompt =
                                createSystemPrompt(propertiesJson, getPropertyListDef.description)
                            tools = toolDefinitionsMap.filterKeys {
                                it.shortName != FUNCTION_GET_PROPERTY_LIST
                            }

                            loadModel(llmSettings, systemPrompt, tools)
                        }.onFailure { exception ->
                            Log.e(TAG, "Failed to initialize model.", exception)
                            _uiState.value = State.Error(exception.message)
                        }
                    } ?: Log.e(TAG, "Unable to find functions for '$TARGET_PACKAGE'")
                }
        }
    }

    private suspend fun executeGetPropertyList(
        getPropertyListTool: FunctionDefinition,
        metadata: AppFunctionMetadata,
    ): String? {
        val propertyListResult = executeAppFunction(getPropertyListTool, metadata, emptyMap())

        return propertyListResult.getOrNull()
            .let { it as? JsonPrimitive }
            ?.takeIf { it.isString }
            ?.content
            ?: run {
                propertyListResult.exceptionOrNull()?.let { Log.e(TAG, "Execution failed", it) }
                null
            }
    }

    private fun createSystemPrompt(
        properties: String,
        functionDescription: String,
    ): String {
        val propertySpec =
            functionDescription.lines().drop(1).dropWhile { it.isBlank() }
                .joinToString("\n")
        val systemPrompt = SYSTEM_PROMPT_TEMPLATE
            .replace(VEHICLE_PROPERTY_SPECIFICATION_PLACEHOLDER, propertySpec)
            .replace(VEHICLE_PROPERTY_LIST_PLACEHOLDER, properties)

        return systemPrompt
    }

    private suspend fun executeAppFunction(
        function: FunctionDefinition,
        metadata: AppFunctionMetadata,
        arguments: Map<String, JsonElement>,
    ): Result<JsonElement> = functionExecutor.executeAppFunction(
        targetPackageName = TARGET_PACKAGE,
        appFunctionMetadata = metadata,
        functionDefinition = function,
        arguments = arguments,
    )
}
