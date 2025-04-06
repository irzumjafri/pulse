package com.solita.pulse.ui // Or your ViewModel package

import android.app.Application
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.solita.pulse.models.MessageType
import com.solita.pulse.network.* // Import your network classes
import com.solita.pulse.speech.SpeechEventListener
import com.solita.pulse.speech.SpeechManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*

// --- UI State Definitions ---

// Represents one-time events for the UI (e.g., showing toasts/dialogs)
sealed class UiEvent {
    data class ShowSnackbar(val message: String) : UiEvent()
    data class ShowLanguageChangeConfirmation(val locale: Locale) : UiEvent()
}

// Represents the main screen state
data class AssistantScreenState(
    val isLoading: Boolean = false, // General loading indicator
    val isListening: Boolean = false,
    val isRecordingMode: Boolean = false, // True if listening for recording
    val isSpeaking: Boolean = false,
    val isProcessingNetwork: Boolean = false, // Specifically for network request progress
    val currentTranscript: String = "", // Live transcript or final recognized text
    val patientName: String? = null,
    val maskedSsn: String? = null,
    val chatHistory: List<Pair<String, MessageType>> = emptyList(),
    val selectedLocale: Locale = Locale("en", "US"),
    val isSecurityModeActive: Boolean = false,
    val error: String? = null, // General error message for display
    val ttsInitialized: Boolean = false
)

class AssistantViewModel(application: Application) : AndroidViewModel(application), SpeechEventListener {

    private val _uiState = MutableStateFlow(AssistantScreenState())
    val uiState: StateFlow<AssistantScreenState> = _uiState.asStateFlow()

    // Flow for one-time events
    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    // Dependencies
    private val speechManager: SpeechManager = SpeechManager(application.applicationContext, this)
    // Session ID - How is this generated/obtained? Assume it's available.
    // TODO: Replace with actual Session ID logic
    private var sessionID: String = UUID.randomUUID().toString()

    // Job Management
    private var currentNetworkJob: Job? = null
    private var currentPollingJob: Job? = null
    private var currentRequestId: String? = null

    init {
        speechManager.initializeRecognizer()
        Log.d("AssistantViewModel", "ViewModel Initialized")
    }

    // --- Actions from UI ---

    fun startListening(isChatMode: Boolean) {
        if (_uiState.value.isListening || !_uiState.value.ttsInitialized) return // Prevent starting if already listening or TTS not ready

        stopCurrentJobs() // Stop any previous processing/polling
        speechManager.stopSpeaking() // Stop any TTS

        val targetModeIsRecording = !isChatMode
        // Update state to show correct listening mode *before* starting
        _uiState.update { it.copy(isListening = true, isRecordingMode = targetModeIsRecording, currentTranscript = "", error = null) }
        speechManager.startRecognition(_uiState.value.selectedLocale)
    }

    fun stopListening() {
        if (!_uiState.value.isListening) return

        speechManager.stopRecognition()
        // State update (isListening=false) will happen via onRecognitionStopped callback
        stopCurrentJobs() // Also cancel network processing if recognizer is stopped
        _uiState.update { it.copy(currentTranscript = "") } // Clear partial transcript
    }

    fun processHotword(isChatMode: Boolean) {
        Log.d("AssistantViewModel", "Hotword detected - starting listening (isChatMode: $isChatMode)")
        startListening(isChatMode)
    }

    fun sendTextMessage(message: String, isChatMode: Boolean) {
        if (message.isBlank()) return
        stopCurrentJobs()
        speechManager.stopSpeaking()

        // Add user message to history immediately
        val messageType = if (isChatMode) MessageType.Chat else MessageType.Record
        _uiState.update {
            it.copy(
                chatHistory = it.chatHistory + (message to messageType),
                isLoading = true, // Show loading while submitting
                error = null
            )
        }
        // Launch network request
        startNetworkJob(message, isChatMode)
    }

    fun setLocale(newLocale: Locale) {
        if (newLocale == _uiState.value.selectedLocale) return

        stopCurrentJobs()
        speechManager.stopSpeaking()
        speechManager.stopRecognition() // Stop listening if active

        _uiState.update {
            it.copy(
                selectedLocale = newLocale,
                isLoading = true, // Show loading while resetting context
                error = null,
                chatHistory = emptyList(), // Clear history immediately
                patientName = null, // Clear patient info
                maskedSsn = null
            )
        }

        currentNetworkJob = viewModelScope.launch {
            when (val result = NetworkUtils.resetUserContext(sessionID)) {
                is NetworkResult.Success -> {
                    Log.d("AssistantViewModel", "Context Reset Success: ${result.data.message}")
                    // Show confirmation via event
                    _uiEvents.emit(UiEvent.ShowLanguageChangeConfirmation(newLocale))
                    _uiState.update { it.copy(isLoading = false) }
                }
                is NetworkResult.Error -> {
                    Log.e("AssistantViewModel", "Context Reset Error: ${result.message}")
                    _uiState.update { it.copy(isLoading = false, error = "Failed to reset context: ${result.message}") }
                }
                is NetworkResult.NetworkError -> {
                    Log.e("AssistantViewModel", "Context Reset Network Error")
                    _uiState.update { it.copy(isLoading = false, error = "Network error during reset.") }
                }
            }
        }
    }

    fun toggleSecurityMode() {
        // If entering security mode, stop listening/speaking/processing
        if (!_uiState.value.isSecurityModeActive) {
            stopCurrentJobs()
            speechManager.stopSpeaking()
            speechManager.stopRecognition()
            // Optionally clear state? Depends on requirements.
        }
        _uiState.update { it.copy(isSecurityModeActive = !it.isSecurityModeActive, error = null) } // Toggle
    }

    fun clearChatAndContext() {
        stopCurrentJobs()
        speechManager.stopSpeaking()
        speechManager.stopRecognition()

        _uiState.update {
            it.copy(
                isLoading = true, // Show loading while resetting context
                error = null,
                chatHistory = emptyList(), // Clear history immediately
                patientName = null, // Clear patient info
                maskedSsn = null
            )
        }
        // Reset context on server
        currentNetworkJob = viewModelScope.launch {
            when (val result = NetworkUtils.resetUserContext(sessionID)) {
                is NetworkResult.Success -> {
                    Log.d("AssistantViewModel", "Context Reset Success: ${result.data.message}")
                    _uiEvents.emit(UiEvent.ShowSnackbar("Chat cleared and context reset."))
                    _uiState.update { it.copy(isLoading = false) }
                }
                is NetworkResult.Error -> {
                    Log.e("AssistantViewModel", "Context Reset Error: ${result.message}")
                    _uiState.update { it.copy(isLoading = false, error = "Failed to reset context: ${result.message}") }
                }
                is NetworkResult.NetworkError -> {
                    Log.e("AssistantViewModel", "Context Reset Network Error")
                    _uiState.update { it.copy(isLoading = false, error = "Network error during reset.") }
                }
            }
        }
    }


    // --- SpeechEventListener Implementation ---

    override fun onRecognitionStarted() {
        Log.d("AssistantViewModel", "Listener: onRecognitionStarted")
        // State already updated in startListening
    }

    override fun onRecognitionStopped() {
        Log.d("AssistantViewModel", "Listener: onRecognitionStopped")
        _uiState.update { it.copy(isListening = false) }
    }

    override fun onPartialResult(text: String) {
        _uiState.update { it.copy(currentTranscript = text) }
    }

    override fun onFinalResult(text: String) {
        Log.d("AssistantViewModel", "Listener: onFinalResult - $text")
        _uiState.update { it.copy(currentTranscript = text) } // Show final recognized text briefly
        // Trigger network request based on the mode active when listening started
        startNetworkJob(text, !_uiState.value.isRecordingMode)
    }

    override fun onRecognitionError(errorCode: Int) {
        val errorMsg = mapSpeechErrorToString(errorCode)
        Log.e("AssistantViewModel", "Listener: onRecognitionError - $errorMsg ($errorCode)")
        // Update state - ensure listening is false, show error
        _uiState.update { it.copy(isListening = false, error = "Speech Error: $errorMsg") }
    }

    override fun onTtsReady() {
        Log.i("AssistantViewModel", "Listener: onTtsReady")
        _uiState.update { it.copy(ttsInitialized = true) }
    }

    override fun onTtsInitializationError() {
        Log.e("AssistantViewModel", "Listener: onTtsInitializationError")
        _uiState.update { it.copy(ttsInitialized = false, error = "TTS failed to initialize.") }
    }

    override fun onTtsStart(utteranceId: String?) {
        Log.d("AssistantViewModel", "Listener: onTtsStart - $utteranceId")
        _uiState.update { it.copy(isSpeaking = true) }
    }

    override fun onTtsDone(utteranceId: String?) {
        Log.d("AssistantViewModel", "Listener: onTtsDone - $utteranceId")
        _uiState.update { it.copy(isSpeaking = false) }
        // Optionally trigger re-listening here if needed for specific workflows
        // startListening(isChatMode = true)
    }

    override fun onTtsError(utteranceId: String?, errorCode: Int) {
        Log.e("AssistantViewModel", "Listener: onTtsError - $utteranceId, Code: $errorCode")
        _uiState.update { it.copy(isSpeaking = false, error = "TTS Error (Code: $errorCode)") }
    }

    // --- Network Request Handling ---

    private fun startNetworkJob(message: String, isChatMode: Boolean) {
        stopCurrentJobs() // Cancel previous network/polling jobs

        val messageType = if (isChatMode) MessageType.Chat else MessageType.Record
        // Update chat history with user message (if from speech) or thinking indicator
        if (_uiState.value.chatHistory.lastOrNull()?.first != message) {
            _uiState.update {
                it.copy(chatHistory = it.chatHistory + (message to messageType))
            }
        }
        // Add thinking message
        val thinkingMessage = if (_uiState.value.selectedLocale.language == "fi") "Pulse AI miettii..." else "Pulse AI is Thinking..."
        _uiState.update {
            it.copy(
                chatHistory = it.chatHistory + (thinkingMessage to MessageType.Server),
                isProcessingNetwork = true,
                isLoading = false, // Turn off general loading if it was on
                error = null,
                currentTranscript = "" // Clear transcript display
            )
        }


        currentNetworkJob = viewModelScope.launch {
            val startFunction = if (isChatMode) NetworkUtils::startChat else NetworkUtils::startRecord
            when (val result = startFunction(sessionID, message, _uiState.value.selectedLocale)) {
                is NetworkResult.Success -> {
                    currentRequestId = result.data
                    Log.d("AssistantViewModel", "Request submitted. ID: $currentRequestId")
                    // Update state - still processing network, waiting for status
                    _uiState.update { it.copy(isProcessingNetwork = true) }
                    pollStatus(result.data) // Start polling
                }
                is NetworkResult.Error -> {
                    Log.e("AssistantViewModel", "Network Start Error: ${result.message}")
                    handleNetworkError("Error starting request: ${result.code} - ${result.message}")
                }
                is NetworkResult.NetworkError -> {
                    Log.e("AssistantViewModel", "Network Start Network Error")
                    handleNetworkError("Network error. Please check connection.")
                }
            }
        }
    }

    private fun pollStatus(requestId: String) {
        currentPollingJob?.cancel() // Cancel previous polling if any
        currentPollingJob = viewModelScope.launch {
            var consecutiveErrors = 0
            while (isActive && currentRequestId == requestId) { // Ensure still polling for the correct request
                Log.d("AssistantViewModel", "Polling status for $requestId...")
                when (val statusResult = NetworkUtils.checkStatus(requestId)) {
                    is NetworkResult.Success -> {
                        consecutiveErrors = 0 // Reset error count on success
                        val statusResponse = statusResult.data
                        Log.d("AssistantViewModel", "Status for $requestId: ${statusResponse.status}")
                        when (statusResponse.status.lowercase()) {
                            "completed" -> {
                                val patientData = statusResponse.data
                                if (patientData != null) {
                                    // Update the "Thinking..." message with the actual response
                                    _uiState.update { state ->
                                        val lastIndex = state.chatHistory.lastIndex
                                        val updatedHistory = if (lastIndex >= 0 && state.chatHistory[lastIndex].second == MessageType.Server) {
                                            state.chatHistory.dropLast(1) + (patientData.response.trim() to MessageType.Server)
                                        } else {
                                            // Should not happen if thinking message was added, but fallback
                                            state.chatHistory + (patientData.response.trim() to MessageType.Server)
                                        }
                                        state.copy(
                                            chatHistory = updatedHistory,
                                            patientName = patientData.patient_name ?: state.patientName, // Update patient info
                                            maskedSsn = patientData.maskedSsn ?: state.maskedSsn,
                                            isProcessingNetwork = false,
                                            error = null
                                        )
                                    }
                                    // Speak the response
                                    speakResponse(patientData.response, _uiState.value.selectedLocale)
                                } else {
                                    handleNetworkError("Completed but no data received.", requestId)
                                }
                                cancel() // Stop polling loop for this request
                            }
                            "cancelled" -> {
                                handleNetworkError("Request was cancelled.", requestId, isCancellation = true)
                                cancel() // Stop polling loop
                            }
                            "error" -> {
                                handleNetworkError("Server error during processing: ${statusResponse.error ?: "Unknown error"}", requestId)
                                cancel() // Stop polling loop
                            }
                            "processing", "cancelling" -> {
                                // Continue polling
                            }
                            else -> {
                                handleNetworkError("Unknown status received: ${statusResponse.status}", requestId)
                                cancel() // Stop polling loop
                            }
                        }
                    }
                    is NetworkResult.Error -> {
                        consecutiveErrors++
                        Log.e("AssistantViewModel", "Polling Error: ${statusResult.message}")
                        // Stop polling after multiple errors?
                        if (consecutiveErrors >= 3) {
                            handleNetworkError("Error checking status: ${statusResult.code} - ${statusResult.message}", requestId)
                            cancel()
                        }
                    }
                    is NetworkResult.NetworkError -> {
                        consecutiveErrors++
                        Log.e("AssistantViewModel", "Polling Network Error")
                        if (consecutiveErrors >= 3) {
                            handleNetworkError("Network error during status check.", requestId)
                            cancel()
                        }
                    }
                }
                if (!isActive) break
                delay(2000) // Poll every 2 seconds
            }
            Log.d("AssistantViewModel", "Polling loop finished for $requestId (isActive=$isActive)")
            // Final check to ensure processing state is cleared if loop exits unexpectedly
            if (currentRequestId == requestId) { // If this request was indeed the last one being polled
                _uiState.update { if (it.isProcessingNetwork) it.copy(isProcessingNetwork = false) else it }
                currentRequestId = null
            }
        }
    }

    fun cancelCurrentNetworkJob() {
        val reqId = currentRequestId
        if (reqId != null && (_uiState.value.isProcessingNetwork || currentPollingJob?.isActive == true)) {
            Log.i("AssistantViewModel", "Attempting to cancel request ID: $reqId")
            stopCurrentJobs() // Cancels networkJob and pollingJob
            _uiState.update { it.copy(isProcessingNetwork = true, error = "Requesting cancellation...") } // Optimistic UI update

            viewModelScope.launch {
                when (val cancelResult = NetworkUtils.cancelRequest(reqId)) {
                    is NetworkResult.Success -> {
                        Log.i("AssistantViewModel", "Cancellation acknowledged by server: ${cancelResult.data.message}")
                        // Final state (Cancelled/Error) will be determined by the *next* status poll if it runs,
                        // or we can force it here. Let's force it for immediate feedback.
                        handleNetworkError("Request cancelled by user.", reqId, isCancellation = true)
                    }
                    is NetworkResult.Error -> {
                        Log.w("AssistantViewModel", "Failed to send cancel request: ${cancelResult.message}")
                        _uiState.update { it.copy(error = "Failed to cancel: ${cancelResult.message}", isProcessingNetwork = false) } // Revert processing state?
                    }
                    is NetworkResult.NetworkError -> {
                        Log.w("AssistantViewModel", "Network error sending cancellation.")
                        _uiState.update { it.copy(error = "Network error during cancellation.", isProcessingNetwork = false) }
                    }
                }
                currentRequestId = null // Clear the ID after attempting cancel
            }
        } else {
            Log.w("AssistantViewModel", "No active network job/request ID to cancel.")
        }
    }

    // --- Utility Functions ---

    private fun stopCurrentJobs() {
        currentNetworkJob?.cancel()
        currentNetworkJob = null
        currentPollingJob?.cancel()
        currentPollingJob = null
        // Don't clear currentRequestId here, cancellation needs it
        Log.d("AssistantViewModel", "Cancelled ongoing network/polling jobs.")
    }

    private fun handleNetworkError(message: String, requestId: String? = null, isCancellation: Boolean = false) {
        // If this error pertains to the currently tracked request ID, clear it
        if (requestId != null && currentRequestId == requestId) {
            currentRequestId = null
        }
        // Update UI state
        _uiState.update { state ->
            // Remove "Thinking..." message if it's the last one
            val lastIndex = state.chatHistory.lastIndex
            val updatedHistory = if (lastIndex >= 0 && state.chatHistory[lastIndex].second == MessageType.Server && state.chatHistory[lastIndex].first.contains("Thinking")) {
                state.chatHistory.dropLast(1)
            } else {
                state.chatHistory
            }
            state.copy(
                chatHistory = updatedHistory,
                isProcessingNetwork = false,
                isLoading = false,
                // Show specific message for cancellation, otherwise the error
                error = if (isCancellation) "Request Cancelled." else message
            )
        }
        stopCurrentJobs() // Ensure related jobs are stopped
    }


    private fun speakResponse(response: String, locale: Locale) {
        val utteranceId = "pulse_response_${System.currentTimeMillis()}"
        speechManager.speak(response.trim(), locale, utteranceId)
    }

    private fun mapSpeechErrorToString(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            else -> "Unknown speech error"
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d("AssistantViewModel", "ViewModel Cleared - Releasing SpeechManager")
        stopCurrentJobs()
        speechManager.release() // Release STT/TTS resources
    }
}