package com.solita.pulse.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.solita.pulse.BuildConfig
import com.solita.pulse.models.MessageType
import com.solita.pulse.network.*
import com.solita.pulse.speech.HotwordDetector
import com.solita.pulse.speech.SpeechEventListener
import com.solita.pulse.speech.SpeechManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

// --- UI State Definitions ---

sealed class UiEvent {
    data class ShowSnackbar(val message: String) : UiEvent()
    data class ShowLanguageChangeConfirmation(val locale: Locale) : UiEvent()
}

// Updated State: Removed isHandsFreeSession
data class AssistantScreenState(
    val isLoading: Boolean = false,
    val isListening: Boolean = false,
    val isRecordingMode: Boolean = false,
    val isSpeaking: Boolean = false,
    val isProcessingNetwork: Boolean = false,
    val currentTranscript: String = "",
    val patientName: String? = null,
    val maskedSsn: String? = null,
    val chatHistory: List<Pair<String, MessageType>> = emptyList(),
    val selectedLocale: Locale = Locale("en", "US"),
    val isSecurityModeActive: Boolean = false,
    val error: String? = null,
    val ttsInitialized: Boolean = false
)

class AssistantViewModel(application: Application) : AndroidViewModel(application), SpeechEventListener {

    private val _uiState = MutableStateFlow(AssistantScreenState())
    val uiState: StateFlow<AssistantScreenState> = _uiState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    // --- Tone Constants ---
    private val startListeningTone = ToneGenerator.TONE_PROP_ACK // A short confirmation tone
    private val stopListeningTone = ToneGenerator.TONE_PROP_BEEP // A standard beep
    private val toneDurationMs = 150 // Duration in milliseconds

    // Dependencies
    private val speechManager: SpeechManager = SpeechManager(application.applicationContext, this)
    private var sessionID: String = UUID.randomUUID().toString() // TODO: Session ID logic
    private var hotwordDetector: HotwordDetector? = null

    // Job Management
    private var currentNetworkJob: Job? = null
    private var currentPollingJob: Job? = null
    private var currentRequestId: String? = null


    // --- Flag to track if the last interaction cycle was hotword-initiated ---
    private var lastInteractionWasHotword: Boolean = false
    // ----------------------------------------------------------------------

    init {
        Log.d("AssistantViewModel", "ViewModel Initialized")
        speechManager.initializeRecognizer()
        initializeHotwordDetector()
    }

    // --- Hotword Initialization ---
    private fun initializeHotwordDetector() {
        if (hotwordDetector != null) {
            Log.d("AssistantViewModel", "HotwordDetector already initialized.")
            return
        }
        if (!hasRecordAudioPermission()) {
            Log.w("AssistantViewModel", "RECORD_AUDIO permission not granted at init. Hotword detection may fail or start later.")
        }

        val accessKey = BuildConfig.PORCUPINE_ACCESS_KEY
        if (accessKey.isBlank() || !accessKey.matches(Regex("^[a-zA-Z0-9/+=]+$"))) {
            Log.e("AssistantViewModel", "Porcupine Access Key missing/invalid. Hotword detection disabled.")
            _uiState.update { it.copy(error = it.error ?: "Hotword engine disabled: Access Key missing.") }
            return
        }

        try {
            hotwordDetector = HotwordDetector(
                context = getApplication<Application>().applicationContext,
                accessKey = accessKey,
                onHotwordDetected = this::handleHotwordDetected,
                onError = this::handleHotwordError
            )
            Log.i("AssistantViewModel", "HotwordDetector instantiated.")
            checkAndStartHotwordDetection()
        } catch (e: Exception) {
            Log.e("AssistantViewModel", "Failed to instantiate HotwordDetector", e)
            handleHotwordError("Failed to initialize hotword engine: ${e.message}")
            hotwordDetector = null
        }
    }

    // --- Hotword Callbacks ---
    private fun handleHotwordDetected(type: Int) {
        viewModelScope.launch {
            Log.i("AssistantViewModel", ">>> Hotword Detected! Type: $type <<<")
            if (_uiState.value.isListening || _uiState.value.isSpeaking || _uiState.value.isProcessingNetwork || _uiState.value.isSecurityModeActive) {
                Log.w("AssistantViewModel", "Hotword detected but ignored due to current app state.")
                return@launch
            }
            stopHotwordDetection() // Stop hotword before starting STT
            val isChatMode = type == 1
            processHotword(isChatMode) // This will set the flag and start listening
        }
    }

    private fun handleHotwordError(errorMsg: String) {
        viewModelScope.launch {
            Log.e("AssistantViewModel", "Hotword Engine Error: $errorMsg")
            _uiState.update { it.copy(error = "Hotword Error: $errorMsg") }
            stopHotwordDetection() // Stop on error
        }
    }

    // --- Hotword Lifecycle Control ---
    private fun shouldStartHotwordDetection(): Boolean {
        val state = _uiState.value
        val hasPermission = hasRecordAudioPermission()
        // Check if detector is initialized and conditions are met
        return hasPermission && state.ttsInitialized && hotwordDetector != null &&
                !state.isSecurityModeActive && !state.isListening &&
                !state.isSpeaking && !state.isProcessingNetwork
    }

    private fun checkAndStartHotwordDetection() {
        if (shouldStartHotwordDetection()) {
            startHotwordDetection()
        } else {
            stopHotwordDetection()
        }
    }

    private fun startHotwordDetection() {
        if (hotwordDetector == null) return
        try {
            Log.d("AssistantViewModel", "Requesting HotwordDetector.start()")
            hotwordDetector?.start()
        } catch (e: Exception) { handleHotwordError("Failed to start hotword engine: ${e.message}") }
    }

    private fun stopHotwordDetection() {
        if (hotwordDetector == null) return
        try {
            Log.d("AssistantViewModel", "Requesting HotwordDetector.stop()")
            hotwordDetector?.stop()
        } catch (e: Exception) { Log.e("AssistantViewModel", "Error stopping hotword engine: ${e.message}") }
    }


    // --- Actions from UI & Internal ---

    // Helper to play tones safely
    private fun Int.playTone(toneType: Int) {
        try {
            // Use application context to get AudioManager
            val audioManager = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // Get current volume for the notification stream (or use STREAM_MUSIC, STREAM_RING)
            val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            // Max volume for ToneGenerator is 100
            val toneVolume = (volume * 100) / audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

            // Create ToneGenerator on the notification stream
            // Note: Requires MODIFY_AUDIO_SETTINGS permission for some streams/volume levels? Test needed.
            val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, toneVolume.coerceIn(0, 100))
            toneGenerator.startTone(toneType, this)

            // Release the tone generator shortly after starting the tone
            // The tone plays asynchronously. Releasing too early might cut it off?
            // Let's release after the duration + small buffer.
            // Alternatively, create ToneGenerator once and release in onCleared, but docs recommend short use.
            Handler(Looper.getMainLooper()).postDelayed({
                toneGenerator.release()
            }, (this + 50).toLong())

        } catch (e: Exception) {
            Log.e("AssistantViewModel", "Error playing tone $toneType: ${e.message}")
            // Handle error (e.g., log, ignore)
        }
    }

    /** Internal function to start STT. Called by processHotword or startListeningManually. */
    private fun startListening(isChatMode: Boolean) {
        // Condition check moved to callers (processHotword, startListeningManually)
        Log.d("AssistantViewModel", "startListening triggered (isChatMode: $isChatMode)")

        stopHotwordDetection() // Ensure hotword is stopped
        stopCurrentJobs()      // Stop network activity
        speechManager.stopSpeaking() // Stop TTS

        val targetModeIsRecording = !isChatMode
        _uiState.update { it.copy(
            isListening = true,
            isRecordingMode = targetModeIsRecording,
            currentTranscript = "",
            error = null
        )}
        speechManager.startRecognition(_uiState.value.selectedLocale)
    }

    /** Call this from UI button clicks for manual activation. */
    fun startListeningManually(isChatMode: Boolean) {
        if (_uiState.value.isListening || !_uiState.value.ttsInitialized) {
            Log.w("AssistantViewModel", "startListeningManually ignored (already listening or TTS not ready).")
            return
        }
        Log.d("AssistantViewModel", "startListeningManually called.")
        lastInteractionWasHotword = false // <<< Reset flag for manual start
        startListening(isChatMode) // Call the main listening function
    }

    /** Call this from UI stop button. */
    fun stopListening() {
        if (!_uiState.value.isListening) return
        Log.d("AssistantViewModel", "stopListening called (explicit stop)")
        lastInteractionWasHotword = false // <<< Reset flag on explicit stop
        speechManager.stopRecognition() // Request STT stop (callbacks handle state)
        stopCurrentJobs()               // Cancel network if active
        _uiState.update { it.copy(currentTranscript = "") } // Clear partial immediately
    }

    /** Internal action triggered by hotword detection callback */
    private fun processHotword(isChatMode: Boolean) {
        // Conditions already checked in handleHotwordDetected
        Log.d("AssistantViewModel", "Processing Hotword - setting flag and starting listening (isChatMode: $isChatMode)")
        lastInteractionWasHotword = true // <<< Set the flag HERE
        startListening(isChatMode = isChatMode)
    }

    /** Called by UI text input */
    fun sendTextMessage(message: String, isChatMode: Boolean) {
        if (message.isBlank()) return
        Log.d("AssistantViewModel", "sendTextMessage called (isChatMode: $isChatMode)")
        lastInteractionWasHotword = false // <<< Reset flag
        stopHotwordDetection()
        stopCurrentJobs()
        speechManager.stopSpeaking()
        speechManager.stopRecognition()

        val messageType = if (isChatMode) MessageType.Chat else MessageType.Record
        _uiState.update { it.copy(
            chatHistory = it.chatHistory + (message to messageType),
            isLoading = true, error = null
        )}
        startNetworkJob(message, isChatMode, isHandsFree = false) // <<< isHandsFree is false
    }

    /** Called by UI language selection */
    fun setLocale(newLocale: Locale) {
        if (newLocale == _uiState.value.selectedLocale) return
        Log.d("AssistantViewModel", "setLocale called: $newLocale")
        lastInteractionWasHotword = false // <<< Reset flag
        stopCurrentJobs(); speechManager.stopSpeaking(); speechManager.stopRecognition(); stopHotwordDetection()
        _uiState.update { it.copy(
            selectedLocale = newLocale, isLoading = true, error = null,
            chatHistory = emptyList(), patientName = null, maskedSsn = null
        )}
        currentNetworkJob = viewModelScope.launch {
            val errorUpdate = when (val result = NetworkUtils.resetUserContext(sessionID)) {
                is NetworkResult.Success -> { _uiEvents.emit(UiEvent.ShowLanguageChangeConfirmation(newLocale)); null }
                is NetworkResult.Error -> "Failed to reset context: ${result.message}"
                is NetworkResult.NetworkError -> "Network error during reset."
            }
            _uiState.update { it.copy(isLoading = false, error = errorUpdate) }
            // Try starting hotword after reset attempt
            checkAndStartHotwordDetection()
        }
    }

    /** Called by UI security mode toggle */
    fun toggleSecurityMode() {
        val enteringSecurityMode = !_uiState.value.isSecurityModeActive
        Log.d("AssistantViewModel", "toggleSecurityMode called (entering: $enteringSecurityMode)")
        if (enteringSecurityMode) {
            lastInteractionWasHotword = false // <<< Reset flag when entering secure mode
            stopCurrentJobs(); speechManager.stopSpeaking(); speechManager.stopRecognition(); stopHotwordDetection()
        }
        _uiState.update { it.copy(isSecurityModeActive = enteringSecurityMode, error = null) }
        if (!enteringSecurityMode) { checkAndStartHotwordDetection() }
    }

    /** Called by UI in secure mode to toggle text input type */
    fun toggleInputMode() {
        if (!_uiState.value.isSecurityModeActive) return
        _uiState.update { it.copy(isRecordingMode = !it.isRecordingMode) }
        Log.d("AssistantViewModel", "Secure Input mode toggled. isRecordingMode: ${_uiState.value.isRecordingMode}")
    }

    /** Called by UI clear button */
    fun clearChatAndContext() {
        Log.d("AssistantViewModel", "clearChatAndContext called")
        lastInteractionWasHotword = false // <<< Reset flag
        stopCurrentJobs(); speechManager.stopSpeaking(); speechManager.stopRecognition(); stopHotwordDetection()
        _uiState.update { it.copy(
            isLoading = true, error = null, // Clear error immediately
            chatHistory = emptyList(), patientName = null, maskedSsn = null
        )}
        currentNetworkJob = viewModelScope.launch {
            val errorUpdate = when (val result = NetworkUtils.resetUserContext(sessionID)) {
                is NetworkResult.Success -> { _uiEvents.emit(UiEvent.ShowSnackbar("Chat cleared and context reset.")); null }
                is NetworkResult.Error -> "Failed to reset context: ${result.message}"
                is NetworkResult.NetworkError -> "Network error during reset."
            }
            // Update final state, potentially showing a NEW error from the reset attempt
            _uiState.update { it.copy(isLoading = false, error = errorUpdate) }
            // Try starting hotword after reset attempt
            checkAndStartHotwordDetection()
        }
    }

    /** Called by UI cancel button */
    fun cancelCurrentNetworkJob() {
        val reqId = currentRequestId
        if (reqId != null && (_uiState.value.isProcessingNetwork || currentPollingJob?.isActive == true)) {
            Log.i("AssistantViewModel", "User requested cancellation for request ID: $reqId")
            lastInteractionWasHotword = false // <<< Reset flag on cancel
            currentPollingJob?.cancel("User requested cancellation")
            currentNetworkJob?.cancel("User requested cancellation")
            handleCancellationOrError("Request Cancelled.", reqId, true) // This now handles hotword check too
            viewModelScope.launch(Dispatchers.IO) { NetworkUtils.cancelRequest(reqId) }
        } else {
            Log.w("AssistantViewModel", "User requested cancel, but no active job/ID found.")
            if (_uiState.value.isProcessingNetwork) { _uiState.update { it.copy(isProcessingNetwork = false, error = null) }}
            checkAndStartHotwordDetection()
        }
    }


    // --- SpeechEventListener Implementation ---

    override fun onRecognitionStarted() { Log.d("SpeechManager Event", "onRecognitionStarted")
        toneDurationMs.playTone(startListeningTone)
    }

    override fun onPartialResult(text: String) { _uiState.update { it.copy(currentTranscript = text) }}

    override fun onFinalResult(text: String) {
        Log.d("SpeechManager Event", "onFinalResult: $text")
        if (text.isBlank()) {
            Log.w("AssistantViewModel", "onFinalResult received blank text.")
//            lastInteractionWasHotword = false // Reset flag
            _uiState.update { it.copy(isListening = false, currentTranscript = "") }
            checkAndStartHotwordDetection() // Check hotword possibility
            return
        }
        // --- Read the flag to determine if network call should be marked hands-free ---
        val isHandsFreeForNetwork = lastInteractionWasHotword
        // --------------------------------------------------------------------------
        _uiState.update { it.copy(currentTranscript = text) } // Show final transcript briefly

        startNetworkJob(
            message = text,
            isChatMode = !_uiState.value.isRecordingMode,
            isHandsFree = isHandsFreeForNetwork // Pass the flag value
        )
        // Do NOT reset lastInteractionWasHotword here yet, wait until after TTS in onTtsDone
    }

    override fun onRecognitionStopped() {
        Log.d("SpeechManager Event", "onRecognitionStopped")
        val wasProcessing = _uiState.value.isProcessingNetwork
        // Check if listening is still true - it might be false if onFinalResult already handled it
        if (_uiState.value.isListening) {
            Log.d("AssistantViewModel", "onRecognitionStopped: Resetting hotword flag as listening ended without final result processing.")
            _uiState.update { it.copy(isListening = false) }
        }
        toneDurationMs.playTone(stopListeningTone)
        if (!wasProcessing) checkAndStartHotwordDetection()
        else Log.d("AssistantViewModel", "STT stopped, network busy. Deferring hotword start.")
    }

    override fun onRecognitionError(errorCode: Int) {
        val errorMsg = mapSpeechErrorToString(errorCode)
        Log.e("SpeechManager Event", "onRecognitionError - $errorMsg ($errorCode)")
        val wasProcessing = _uiState.value.isProcessingNetwork
        lastInteractionWasHotword = false // Reset flag on error
        _uiState.update { it.copy(isListening = false, error = "Speech Error: $errorMsg") }
        toneDurationMs.playTone(stopListeningTone)
        if (!wasProcessing) checkAndStartHotwordDetection()
        else Log.d("AssistantViewModel", "STT error, network busy. Deferring hotword start.")
    }

    override fun onTtsReady() {
        Log.i("SpeechManager Event", "onTtsReady")
        _uiState.update { it.copy(ttsInitialized = true) }
        checkAndStartHotwordDetection()
    }

    override fun onTtsInitializationError() {
        Log.e("SpeechManager Event", "onTtsInitializationError")
        lastInteractionWasHotword = false // Can't relisten or use hotword if TTS fails
        _uiState.update { it.copy(ttsInitialized = false, error = "TTS failed to initialize.") }
        stopHotwordDetection()
    }

    override fun onTtsStart(utteranceId: String?) {
        Log.d("SpeechManager Event", "onTtsStart - $utteranceId")
        stopHotwordDetection() // Stop hotword when TTS starts
        _uiState.update { it.copy(isSpeaking = true) }
    }

    // --- Modified onTtsDone for Conditional Auto-Relisten ---
    override fun onTtsDone(utteranceId: String?) {
        Log.d("SpeechManager Event", "onTtsDone - $utteranceId")
        _uiState.update { it.copy(isSpeaking = false) }

        // --- Check conditions for auto-relisten ---

        // Store the flag's value for the interaction that just finished TTS
        val wasHotword = lastInteractionWasHotword
        // Reset the flag now for the *next* interaction cycle.
        // If we auto-relisten below, that new session isn't technically hotword-initiated.
//        lastInteractionWasHotword = false

        // Check all conditions needed to auto-relisten
        val hasPermission = hasRecordAudioPermission()
        val ttsReady = _uiState.value.ttsInitialized // Should be true if TTS just finished
        val notSecure = !_uiState.value.isSecurityModeActive
        val notListening = !_uiState.value.isListening // Should be true after STT finished
        val notProcessing = !_uiState.value.isProcessingNetwork // Check if network finished


        val shouldRelisten = wasHotword && hasPermission && ttsReady && notSecure && notListening && notProcessing

        if (shouldRelisten) {
            Log.i("AssistantViewModel", "[onTtsDone] Conditions met. Auto-relistening (Chat mode).")
            // Call the private startListening function directly
            Log.i("AssistantViewModel", "==> PRIVATE startListening(isChatMode=true) CALLED <==")
            startListening(isChatMode = true)
        } else {
            // If not auto-relistening
            Log.d("AssistantViewModel", "[onTtsDone] Conditions not met. Checking hotword detection possibility.")
            // Check if hotword detection should start (standard idle behavior)
            checkAndStartHotwordDetection()
        }
    }

    override fun onTtsError(utteranceId: String?, errorCode: Int) {
        Log.e("SpeechManager Event", "onTtsError - $utteranceId, Code: $errorCode")
        lastInteractionWasHotword = false // Reset flag on TTS error
        _uiState.update { it.copy(isSpeaking = false, error = "TTS Error (Code: $errorCode)") }
        // Do NOT auto-relisten on TTS error. Just check for hotword possibility.
        checkAndStartHotwordDetection()
    }


    // --- Network Request Handling ---

    /** Initiates the network request sequence (start + poll), including handsFree flag */
    private fun startNetworkJob(message: String, isChatMode: Boolean, isHandsFree: Boolean) {
        // Ensure NetworkUtils.startChat/startRecord accept isHandsFree
        stopCurrentJobs()
        // ... (update chat history with thinking message) ...
        val messageType = if (isChatMode) MessageType.Chat else MessageType.Record
        val lastMessageIsInput = _uiState.value.chatHistory.lastOrNull()?.let { it.first == message && (it.second == MessageType.Chat || it.second == MessageType.Record) } ?: false
        if (!lastMessageIsInput) { _uiState.update { it.copy(chatHistory = it.chatHistory + (message to messageType)) } }
        val thinkingMessage = if (_uiState.value.selectedLocale.language == "fi") "Pulse AI miettii..." else "Pulse AI is Thinking..."
        _uiState.update { it.copy(
            chatHistory = it.chatHistory + (thinkingMessage to MessageType.Server),
            isProcessingNetwork = true, isLoading = false, error = null, currentTranscript = ""
        )}

        currentNetworkJob = viewModelScope.launch {
            val startFunction = if (isChatMode) NetworkUtils::startChat else NetworkUtils::startRecord
            when (val result = startFunction(sessionID, message, _uiState.value.selectedLocale, isHandsFree)) { // Pass isHandsFree
                is NetworkResult.Success -> { currentRequestId = result.data; Log.i("AssistantViewModel", "Network request submitted (handsFree=$isHandsFree). ID: $currentRequestId"); pollStatus(result.data) }
                is NetworkResult.Error -> { Log.e("AssistantViewModel", "Network Start Error: ${result.message}"); handleCancellationOrError("Start Error: ${result.code}-${result.message}", null, false) }
                is NetworkResult.NetworkError -> { Log.e("AssistantViewModel", "Network Start Network Error"); handleCancellationOrError("Network error starting request.", null, false) }
            }
        }
    }

    /** Polls the server for the status of a given request ID. */
    private fun pollStatus(requestId: String) {
        if (currentPollingJob?.isActive == true && currentRequestId == requestId) return
        currentPollingJob?.cancel()

        currentPollingJob = viewModelScope.launch {
            var consecutiveErrors = 0; val maxErrors = 3
            Log.d("AssistantViewModel", "Polling started for $requestId")
            while (isActive && currentRequestId == requestId) {
                Log.v("AssistantViewModel", "Polling check for $requestId...")
                when (val statusResult = NetworkUtils.checkStatus(requestId)) {
                    is NetworkResult.Success -> { /* Handle success statuses */
                        consecutiveErrors = 0; val statusResponse = statusResult.data; Log.d("AssistantViewModel", "Status: ${statusResponse.status}")
                        when (statusResponse.status.lowercase()) {
                            "completed" -> { handleNetworkSuccess(statusResponse); cancel(); break }
                            "cancelled" -> { handleCancellationOrError("Request cancelled by server.", requestId, true); cancel(); break }
                            "error" -> { handleCancellationOrError("Server processing error: ${statusResponse.error?:"Unknown"}", requestId, false); cancel(); break }
                            "cancelling" -> _uiState.update { it.copy(error = "Cancellation requested...") }
                            "processing" -> { /* Wait */ }
                            else -> { handleCancellationOrError("Unknown status: ${statusResponse.status}", requestId, false); cancel(); break }
                        }
                    }
                    is NetworkResult.Error -> { /* Handle polling errors */
                        consecutiveErrors++; Log.e("AssistantViewModel", "Polling Error ($consecutiveErrors/$maxErrors): ${statusResult.message}")
                        if (consecutiveErrors >= maxErrors) { handleCancellationOrError("Status check failed: ${statusResult.message}", requestId, false); cancel(); break }
                    }
                    is NetworkResult.NetworkError -> { /* Handle network errors */
                        consecutiveErrors++; Log.e("AssistantViewModel", "Polling Network Error ($consecutiveErrors/$maxErrors)")
                        if (consecutiveErrors >= maxErrors) { handleCancellationOrError("Network error during status check.", requestId, false); cancel(); break }
                    }
                }
                if (!isActive || currentRequestId != requestId) break
                delay(2000)
            } // End while
            Log.d("AssistantViewModel", "Polling loop finished for $requestId (isActive=$isActive, currentRequestId=$currentRequestId)")
            if (!isActive || currentRequestId == null) { viewModelScope.launch { checkAndStartHotwordDetection() } } // Check hotword after loop finish
        }
    }

    /** Handles the successful completion ("completed" status) */
    private fun handleNetworkSuccess(statusResponse: StatusResponse) {
        val patientData = statusResponse.data
        currentRequestId = null // Clear ID first
        if (patientData != null) {
            _uiState.update { state ->
                // ... (update history, patient name, ssn) ...
                val lastIndex = state.chatHistory.lastIndex
                val updatedHistory = if (lastIndex >= 0 && state.chatHistory[lastIndex].second == MessageType.Server && state.chatHistory[lastIndex].first.contains("Thinking")) {
                    state.chatHistory.dropLast(1) + (patientData.response.trim() to MessageType.Server)
                } else { state.chatHistory + (patientData.response.trim() to MessageType.Server) }
                state.copy(
                    chatHistory = updatedHistory, patientName = patientData.patient_name ?: state.patientName,
                    maskedSsn = patientData.maskedSsn ?: state.maskedSsn, isProcessingNetwork = false, error = null
                )
            }
            speakResponse(patientData.response, _uiState.value.selectedLocale) // TTS events handle next step
        } else {
            Log.e("AssistantViewModel", "Network Success: Status 'completed' but data is null.")
            handleCancellationOrError("Completed but no data received.", statusResponse.request_id, false)
        }
    }

    /** Handles network errors, server errors, cancellations and updates UI */
    private fun handleCancellationOrError(message: String, requestId: String?, isCancellation: Boolean) {
        Log.w("AssistantViewModel", "Handling cancellation/error: $message (Req ID: $requestId, IsCancel: $isCancellation)")
        if (requestId != null && currentRequestId == requestId) { currentRequestId = null }
        stopCurrentJobs()
        lastInteractionWasHotword = false // <<< Reset flag on any network failure/cancel

        _uiState.update { state ->
            // ... (update history, clear processing, set error message) ...
            val lastIndex = state.chatHistory.lastIndex
            val updatedHistory = if (lastIndex >= 0 && state.chatHistory[lastIndex].second == MessageType.Server && state.chatHistory[lastIndex].first.contains("Thinking")) {
                val finalMessage = if (isCancellation) "Request Cancelled." else "Error: $message"
                state.chatHistory.dropLast(1) + (finalMessage.trim() to MessageType.Server)
            } else { state.chatHistory }
            state.copy(
                chatHistory = updatedHistory, isProcessingNetwork = false, isLoading = false,
                error = if (isCancellation) "Request Cancelled." else message
            )
        }
        checkAndStartHotwordDetection() // Try starting hotword detection after handling
    }

    // --- Utility Functions ---
    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    private fun mapSpeechErrorToString(error: Int): String { /* As before */
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio error"
            SpeechRecognizer.ERROR_CLIENT -> "Client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permissions missing"
            SpeechRecognizer.ERROR_NETWORK -> "Network error (STT)"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout (STT)"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error (STT)"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            else -> "Unknown speech error ($error)"
        }
    }
    private fun stopCurrentJobs() { currentNetworkJob?.cancel(); currentPollingJob?.cancel(); currentNetworkJob=null; currentPollingJob=null }
    private fun speakResponse(response: String, locale: Locale) {
        if (response.isNotBlank()) { speechManager.speak(response.trim(), locale, "pulse_resp_${System.currentTimeMillis()}") }
        else { Log.w("AssistantViewModel", "speakResponse empty."); lastInteractionWasHotword=false; checkAndStartHotwordDetection(); } // Reset flag and check hotword if no TTS
    }

    // --- ViewModel Lifecycle ---
    override fun onCleared() {
        super.onCleared()
        Log.d("AssistantViewModel", "ViewModel Cleared - Releasing Resources")
        stopCurrentJobs()
        speechManager.release()
        try { hotwordDetector?.release(); hotwordDetector = null }
        catch (e: Exception) { Log.e("AssistantViewModel", "Error releasing HotwordDetector", e) }
    }
} // End AssistantViewModel