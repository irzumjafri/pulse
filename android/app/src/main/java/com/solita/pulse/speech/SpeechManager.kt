package com.solita.pulse.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

interface SpeechEventListener {
    // Recognition Events
    fun onRecognitionStarted() // Indicates recognizer is ready and listening
    fun onRecognitionStopped() // Indicates recognizer has stopped listening (end of speech, explicit stop, or error)
    fun onPartialResult(text: String)
    fun onFinalResult(text: String)
    fun onRecognitionError(errorCode: Int) // Pass the Android SpeechRecognizer error code

    // TTS Events
    fun onTtsReady() // Called when TTS is successfully initialized
    fun onTtsInitializationError()
    fun onTtsStart(utteranceId: String?)
    fun onTtsDone(utteranceId: String?)
    fun onTtsError(utteranceId: String?, errorCode: Int)
}

// Use 'object' for singleton if appropriate, or make it a class if multiple instances needed
// If using as a singleton object, ensure context is ApplicationContext to avoid leaks
class SpeechManager(
    private val context: Context, // Use ApplicationContext if SpeechManager lifecycle > Activity/Fragment
    private var listener: SpeechEventListener? // Listener to report events back
) : TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isRecognizerListening = false
    private var isTtsInitialized = false
    private var currentLocale: Locale = Locale.US // Default locale

    private val mainThreadHandler = Handler(Looper.getMainLooper())

    init {
        initializeTextToSpeech()
        // Initialize recognizer immediately or lazily? Let's do it lazily or on demand.
    }

    // --- Public Methods ---

    fun setListener(listener: SpeechEventListener?) {
        this.listener = listener
        // If TTS is already ready, notify the new listener
        if (isTtsInitialized && listener != null) {
            mainThreadHandler.post { listener.onTtsReady() }
        }
    }

    fun initializeRecognizer() {
        mainThreadHandler.post {
            if (speechRecognizer == null) {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    Log.e("SpeechManager", "Speech recognition not available on this device.")
                    // Optionally notify listener about this permanent error
                    return@post
                }
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                speechRecognizer?.setRecognitionListener(recognitionListener)
                Log.d("SpeechManager", "SpeechRecognizer initialized.")
            }
        }
    }

    /**
     * Starts the speech recognition process.
     * Ensure initializeRecognizer was called first.
     * @param locale The language to listen in.
     */
    fun startRecognition(locale: Locale) {
        currentLocale = locale // Store locale for intent
        mainThreadHandler.post {
            if (speechRecognizer == null) {
                Log.e("SpeechManager", "Recognizer not initialized. Call initializeRecognizer first.")
                listener?.onRecognitionError(SpeechRecognizer.ERROR_CLIENT) // Indicate client error
                return@post
            }
            if (isRecognizerListening) {
                Log.w("SpeechManager", "Recognizer already listening. Ignoring start request.")
                return@post
            }

            val speechLanguage = when (locale.language) {
                "fi" -> "fi-FI"
                else -> "en-US" // Default or map more languages
            }

            Log.d("SpeechManager", "Starting recognition for locale: $locale, intent language: $speechLanguage")

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLanguage)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Adjust silence detection if needed, or remove if default is okay
                // putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10000)
                // putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000) // Shorter?
                // --- Adjust Silence Detection Parameters ---

                // 1. COMPLETE Silence Length:
                // The amount of silence *after* speech stops before ending input.
                // Increase this value significantly (default is often ~1.5-2s).
                // Let's try 5 seconds (5000 milliseconds). Use 'L' for Long.
                val completeSilenceMillis = 5000L
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, completeSilenceMillis)
                Log.d("SpeechManager", "Setting COMPLETE_SILENCE_LENGTH_MILLIS to $completeSilenceMillis")

                // 2. POSSIBLY COMPLETE Silence Length:
                // Time before recognizer *might* consider input complete (often used for pauses *during* speech).
                // Should generally be <= COMPLETE silence length.
                // Increasing this might help prevent cutoff during thinking pauses.
                // Let's try 4 seconds (4000 milliseconds).
                val possiblyCompleteSilenceMillis = 4000L
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, possiblyCompleteSilenceMillis)
                Log.d("SpeechManager", "Setting POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS to $possiblyCompleteSilenceMillis")

                // ------------------------------------------
            }
            try {
                speechRecognizer?.startListening(intent)
                // Note: onReadyForSpeech will confirm when it's actually started
            } catch (e: Exception) {
                Log.e("SpeechManager", "Error starting listening", e)
                listener?.onRecognitionError(SpeechRecognizer.ERROR_CLIENT) // Or a more specific error if identifiable
            }
        }
    }

    /**
     * Stops the speech recognition process explicitly.
     */
    fun stopRecognition() {
        mainThreadHandler.post {
            if (!isRecognizerListening) return@post // Avoid stopping if not started
            Log.d("SpeechManager", "Explicitly stopping recognition.")
            speechRecognizer?.stopListening()
            // The listener callbacks (onEndOfSpeech/onError) will handle state changes.
            // We set isRecognizerListening = false there.
        }
    }

    /**
     * Speaks the given text using TTS.
     * @param text The text to speak.
     * @param locale The language/voice to use.
     * @param utteranceId A unique ID for this speech request (optional, defaults to random UUID).
     */
    // Inside NEW SpeechManager.kt
    fun speak(text: String, locale: Locale, utteranceId: String = UUID.randomUUID().toString()) {
        if (!isTtsInitialized || textToSpeech == null) {
            Log.e("SpeechManager", "TTS not initialized or failed to initialize.")
            listener?.onTtsError(utteranceId, TextToSpeech.ERROR)
            return
        }

        val ttsLocale = when (locale.language) {
            "fi" -> Locale("fi", "FI")
            else -> Locale.US // Default or map more languages
        }

        val result = textToSpeech?.setLanguage(ttsLocale)

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // ... (language error handling) ...
            listener?.onTtsError(utteranceId, TextToSpeech.ERROR_INVALID_REQUEST) // More specific error?
            return
        }

        // --- >> ADD LISTENER SETTING HERE (before speak) << ---
        val listenerResult = textToSpeech?.setOnUtteranceProgressListener(ttsProgressListener)
        if (listenerResult == TextToSpeech.ERROR) {
            // Log error if setting listener fails right before speak
            Log.e("SpeechManager", "!!! Failed to set UtteranceProgressListener immediately before speak call !!!")
            // Optionally report TTS error immediately, as callbacks likely won't fire
            mainThreadHandler.post { listener?.onTtsError(utteranceId, TextToSpeech.ERROR_INVALID_REQUEST) }
            // Maybe return early? Or let speak try anyway? Let's let it try for now.
        }
        // -------------------------------------------------------


        Log.i("SpeechManager", "Calling textToSpeech.speak() with utteranceId: $utteranceId, text length: ${text.length}")
        val speakResult = textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId) // Keep Bundle()

        // Log speak result (as added before)
        Log.d("SpeechManager", "textToSpeech.speak() returned: $speakResult (0=SUCCESS, -1=ERROR)")
        if (speakResult == TextToSpeech.ERROR) {
            Log.e("SpeechManager", "!!! textToSpeech.speak() call FAILED !!!")
            // If speak fails immediately, call onError manually as listener won't fire
            mainThreadHandler.post { listener?.onTtsError(utteranceId, TextToSpeech.ERROR_SYNTHESIS) }
        }
    }

    /**
     * Stops any ongoing TTS playback.
     */
    fun stopSpeaking() {
        if (isTtsInitialized && textToSpeech?.isSpeaking == true) {
            Log.d("SpeechManager", "Stopping TTS playback.")
            textToSpeech?.stop()
        }
    }


    /**
     * Releases resources used by SpeechRecognizer and TextToSpeech.
     * Call this when the manager is no longer needed (e.g., in ViewModel's onCleared).
     */
    fun release() {
        Log.d("SpeechManager", "Releasing resources.")
        mainThreadHandler.post {
            speechRecognizer?.destroy()
            speechRecognizer = null
            isRecognizerListening = false // Reset state
        }
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isTtsInitialized = false
        listener = null // Remove listener reference
    }

    // --- Private Helpers & Listeners ---

    private fun initializeTextToSpeech() {
        try {
            textToSpeech = TextToSpeech(context, this) // 'this' is the OnInitListener
            // --------------------------
            Log.d("SpeechManager", "TTS initialization requested.")
        } catch (e: Exception) {
            Log.e("SpeechManager", "Exception initializing TTS", e)
            isTtsInitialized = false
            listener?.onTtsInitializationError() // Notify ViewModel
        }
    }

    // TextToSpeech.OnInitListener implementation
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsInitialized = true
            Log.d("SpeechManager", "TTS Initialized successfully.")
            mainThreadHandler.post { listener?.onTtsReady() }
            // You might want to set a default language here if needed
            // textToSpeech?.language = Locale.US
        } else {
            isTtsInitialized = false
            Log.e("SpeechManager", "TTS Initialization failed with status: $status")
            mainThreadHandler.post { listener?.onTtsInitializationError() }
        }
    }

    // Internal listener for TTS progress
    private val ttsProgressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            Log.d("SpeechManager", "TTS onStart: $utteranceId")
            mainThreadHandler.post { listener?.onTtsStart(utteranceId) }
        }

        override fun onDone(utteranceId: String?) {
            Log.d("SpeechManager", "TTS onDone: $utteranceId")
            mainThreadHandler.post { listener?.onTtsDone(utteranceId) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            // This older version might still be called on some devices
            Log.e("SpeechManager", "TTS onError (deprecated): $utteranceId")
            mainThreadHandler.post { listener?.onTtsError(utteranceId, TextToSpeech.ERROR) } // Generic error code
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            // Prefer this method with error code
            Log.e("SpeechManager", "TTS onError: $utteranceId, Code: $errorCode")
            mainThreadHandler.post { listener?.onTtsError(utteranceId, errorCode) }
        }
    }

    // Internal listener for Speech Recognition events
    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d("SpeechManager", "onReadyForSpeech")
            isRecognizerListening = true // Now truly listening
            mainThreadHandler.post { listener?.onRecognitionStarted() }
        }

        override fun onBeginningOfSpeech() {
            Log.d("SpeechManager", "onBeginningOfSpeech")
            // Listener already notified in onReadyForSpeech
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Usually not needed unless displaying audio level
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // Usually not needed
        }

        override fun onEndOfSpeech() {
            Log.d("SpeechManager", "onEndOfSpeech")
            isRecognizerListening = false // Stopped receiving audio
            // Don't call listener?.onRecognitionStopped() here yet,
            // wait for onResults or onError.
        }

        override fun onError(error: Int) {
            // Check if it's a "no match" or "speech timeout" which might be expected vs actual errors
            val isExpectedError = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            Log.w("SpeechManager", "onError: $error (Expected: $isExpectedError)")
            isRecognizerListening = false // Recognition definitively stopped
            mainThreadHandler.post {
                listener?.onRecognitionError(error)
                listener?.onRecognitionStopped() // Also signal stop on error
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val recognizedText = matches?.firstOrNull()?.trim().orEmpty()
            Log.d("SpeechManager", "onResults: $recognizedText")
            isRecognizerListening = false // Recognition finished successfully
            mainThreadHandler.post {
                if (recognizedText.isNotEmpty()) {
                    listener?.onFinalResult(recognizedText)
                } else {
                    // Treat empty result as an error? Or just ignore?
                    // Let's report ERROR_NO_MATCH for consistency if text is empty.
                    Log.w("SpeechManager", "onResults received empty text.")
                    listener?.onRecognitionError(SpeechRecognizer.ERROR_NO_MATCH)
                }
                listener?.onRecognitionStopped() // Also signal stop on successful result
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partialText = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            // Only report if text is not empty and recognizer should still be listening
            if (partialText.isNotEmpty() && isRecognizerListening) {
//                Log.d("SpeechManager", "onPartialResults: $partialText")
                mainThreadHandler.post { listener?.onPartialResult(partialText) }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            // Can be used for specific vendor events if needed
        }
    }
}