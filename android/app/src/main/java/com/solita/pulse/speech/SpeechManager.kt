package com.solita.pulse.speech

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.solita.pulse.models.MessageType
import com.solita.pulse.network.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import android.os.Handler
import android.os.Looper

object SpeechManager {

    fun startListening(
        speechRecognizer: SpeechRecognizer,
        selectedLocale: Locale,
        sessionID: String,
        chatHistory: MutableList<Pair<String, MessageType>>,
        coroutineScope: CoroutineScope,
        route:String,
        textToSpeech: TextToSpeech,
        isListening: (Int) -> Unit
    ) {

        Log.d("Recognizing Speech :", "$selectedLocale")
        val speechLanguage: String = if (selectedLocale.toString() == "en_US") {
            "en-UK"
        } else {
            "fi-FI"
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLanguage)
        }

        // Ensure the speechRecognizer is set on the main thread
        Handler(Looper.getMainLooper()).post {
            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (route === "/chat") {
                        chatHistory.add("Listening..." to MessageType.Chat)
                        isListening(1)
                    } else {
                        chatHistory.add("Recording..." to MessageType.Record)
                        isListening(2)
                    }

                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val recognizedText = matches?.firstOrNull().orEmpty()
                    if (recognizedText.isNotEmpty()) {
                        if (route === "/chat") {
                            chatHistory[chatHistory.size - 1] = (recognizedText to MessageType.Chat)
                        } else {
                            chatHistory[chatHistory.size - 1] = (recognizedText to MessageType.Record)
                        }
                        coroutineScope.launch {
                            chatHistory.add("Pulse AI is Thinking" to MessageType.Server)
                            if (route === "/chat") {
                                NetworkUtils.sendToChatAsync(
                                    sessionID, recognizedText, selectedLocale
                                ) { serverResponse ->
                                    chatHistory[chatHistory.size - 1] =
                                        (serverResponse.trim() to MessageType.Server)
                                    if (textToSpeech.isSpeaking) {
                                        textToSpeech.stop()
                                    }
                                    if (selectedLocale.language == "fi") {
                                        textToSpeech.setLanguage(Locale("fi-FI"))
                                    } else {
                                        textToSpeech.setLanguage(Locale("en-US"))
                                    }
                                    textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                        override fun onStart(utteranceId: String) {
                                            Log.d("TTS", "onStart: $utteranceId")
                                        }

                                        override fun onDone(utteranceId: String) {
                                            startListening(speechRecognizer, selectedLocale, sessionID, chatHistory, coroutineScope, route, textToSpeech, isListening)
                                        }

                                        override fun onError(utteranceId: String?) {
                                            Log.e("TTS", "Error occurred during speech: $utteranceId")
                                        }
                                    })
                                    textToSpeech.speak(
                                        serverResponse, TextToSpeech.QUEUE_FLUSH, null, "CHAT_TTS"
                                    )

                                }

                            } else if (route === "/record") {
                                NetworkUtils.sendToRecordAsync(
                                    sessionID, recognizedText, selectedLocale
                                ) { serverResponse ->
                                    chatHistory[chatHistory.size - 1] =
                                        (serverResponse.trim() to MessageType.Server)
                                    if (textToSpeech.isSpeaking) {
                                        textToSpeech.stop()
                                    }
                                    if (selectedLocale.language == "fi") {
                                        textToSpeech.setLanguage(Locale("fi-FI"))
                                    } else {
                                        textToSpeech.setLanguage(Locale("en-US"))
                                    }
                                    textToSpeech.speak(
                                        serverResponse, TextToSpeech.QUEUE_FLUSH, null, "RECORD_TTS"
                                    )

                                }

                            }
                        }
                    }
                }

                override fun onError(error: Int) {
                    isListening(0)
                    if(chatHistory[chatHistory.size -1].first == "Listening..." || chatHistory[chatHistory.size -1].first == "Recording...") {
                        chatHistory.removeAt(chatHistory.size - 1)
                    }

                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partialText =
                        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                    if (route === "/chat") {
                        chatHistory.add(partialText.orEmpty() to MessageType.Chat)
                    } else {
                        chatHistory.add(partialText.orEmpty() to MessageType.Record)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onEndOfSpeech() { isListening(0) }
                override fun onBeginningOfSpeech() { isListening(if (route == "/chat") 1 else 2) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onRmsChanged(rmsdB: Float) {}
            })

            speechRecognizer.startListening(intent)
        }
    }
}