package com.solita.pulse.speech

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.solita.pulse.models.MessageType
import com.solita.pulse.network.NetworkUtils
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object SpeechManager {

    fun startListening(
        speechRecognizer: SpeechRecognizer,
        selectedLocale: Locale,
        sessionID: String,
        chatHistory: MutableList<Pair<String, MessageType>>,
        coroutineScope: CoroutineScope,
        route: String
    ) {
        Log.d("Recognizing Speech :", "$selectedLocale")
        var speechLanguage = ""
        speechLanguage = if (selectedLocale.toString() == "en_US"){
            "en-US"
        } else{
            "fi-FI"
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE,speechLanguage)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                chatHistory.add("Listening..." to MessageType.Chat)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val recognizedText = matches?.firstOrNull().orEmpty()
                if (recognizedText.isNotEmpty()) {
                    chatHistory[chatHistory.size - 1] = (recognizedText to MessageType.Chat)

                    coroutineScope.launch {
                        chatHistory.add("Pulse AI is Thinking" to MessageType.Server)
                        if (route == "/chat") {
                            NetworkUtils.sendToChatAsync(sessionID, recognizedText, selectedLocale) { serverResponse ->
                                chatHistory[chatHistory.size - 1] = (serverResponse.trim() to MessageType.Server)
                            }
                        } else if (route == "/record") {
                            NetworkUtils.sendToRecordAsync(sessionID, recognizedText, selectedLocale) { serverResponse ->
                                chatHistory[chatHistory.size - 1] = (serverResponse.trim() to MessageType.Server)
                            }
                        }
                    }
                }
            }

            override fun onError(error: Int) {
                chatHistory[chatHistory.size - 1] = ("Error occurred. Please try again." to MessageType.Server)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partialText = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                chatHistory.add(partialText.orEmpty() to MessageType.Chat)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onEndOfSpeech() {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onRmsChanged(rmsdB: Float) {}
        })

        speechRecognizer.startListening(intent)
    }

//    fun startHotwordDetection(
//        speechRecognizer: SpeechRecognizer,
//        selectedLocale: Locale,
//        sessionID: String,
//        chatHistory: MutableList<Pair<String, MessageType>>,
//        coroutineScope: CoroutineScope
//    ) {
//        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
//            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
//            putExtra(RecognizerIntent.EXTRA_LANGUAGE, selectedLocale.toString())
//        }
//
//        speechRecognizer.setRecognitionListener(object : RecognitionListener {
//            override fun onReadyForSpeech(params: Bundle?) {}
//
//            override fun onResults(results: Bundle?) {
//                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
//                matches?.forEach { recognizedText ->
//                    if (recognizedText.contains("Hey Pulse", ignoreCase = true)) {
//                        startListening(speechRecognizer, selectedLocale, sessionID, chatHistory, coroutineScope, "/chat")
//                    } else if (recognizedText.contains("Record Pulse", ignoreCase = true)) {
//                        startListening(speechRecognizer, selectedLocale, sessionID, chatHistory, coroutineScope, "/record")
//                    } else {
//                        speechRecognizer.startListening(intent)
//                    }
//                }
//            }
//
//            override fun onError(error: Int) {
//                speechRecognizer.startListening(intent)
//            }
//
//            override fun onPartialResults(partialResults: Bundle?) {}
//            override fun onEvent(eventType: Int, params: Bundle?) {}
//            override fun onEndOfSpeech() {
//                speechRecognizer.startListening(intent)
//            }
//
//            override fun onBeginningOfSpeech() {}
//            override fun onBufferReceived(buffer: ByteArray?) {}
//            override fun onRmsChanged(rmsdB: Float) {}
//        })
//
//        speechRecognizer.startListening(intent)
//    }
}
