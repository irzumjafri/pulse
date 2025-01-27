package com.solita.pulse.ui

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.solita.pulse.models.MessageType
import com.solita.pulse.network.NetworkUtils
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun VoiceAssistantApp(
    speechRecognizer: SpeechRecognizer,
    textToSpeech: TextToSpeech,
    sessionID: String
) {


    val client = remember { OkHttpClient.Builder().connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS).build() }


    var isListening by remember { mutableStateOf(false) }
    var selectedLocale by remember { mutableStateOf(Locale("en", "FI")) }
    val chatHistory = remember { mutableStateListOf<Pair<String, MessageType>>() }
    var currentUserMessage by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    val listState = rememberLazyListState()

    var isChatActive by remember { mutableStateOf(true) } // Default to chat mode
    var isRecordActive by remember { mutableStateOf(false) }

    // New state for security toggle
    var isSecurityModeActive by remember { mutableStateOf(false) }

    // State to manage the visibility of the language dropdown menu
    var isLanguageMenuExpanded by remember { mutableStateOf(false) }

    // Text input state for custom messages
    var customMessage by remember { mutableStateOf("") }

    fun startListening(route: String) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, selectedLocale.toString())
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                currentUserMessage = "Listening..."
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val recognizedText = matches?.firstOrNull().orEmpty()
                if (recognizedText.isNotEmpty()) {
                    currentUserMessage = ""
                    chatHistory.add(recognizedText to (if (isChatActive) MessageType.Chat else MessageType.Record))

                    coroutineScope.launch {
                        if (route == "/chat") {
                            NetworkUtils.sendToChatAsync(
                                client,
                                sessionID,
                                recognizedText,
                                selectedLocale
                            ) { serverResponse ->
                                chatHistory.add(serverResponse to MessageType.Server)
                            }
                        } else if (route == "/record") {
                            NetworkUtils.sendToRecordAsync(
                                client,
                                sessionID,
                                recognizedText,
                                selectedLocale
                            ) { serverResponse ->
                                chatHistory.add(serverResponse to MessageType.Server)
                            }
                        }
                    }
                }

            }

            override fun onError(error: Int) {
                isListening = false
                currentUserMessage = ""

                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> {
                        if (selectedLocale.language == "fi") {
                            "Anteeksi, en ymmärtänyt. Voisitko toistaa pyyntösi?"
                        } else {
                            "Sorry, I couldn't understand that. Could you please repeat the request?"
                        }
                    }
                    SpeechRecognizer.ERROR_NETWORK -> {
                        if (selectedLocale.language == "fi") {
                            "Verkkovirhe. Tarkista internetyhteytesi."
                        } else {
                            "Network error. Please check your internet connection."
                        }
                    }
                    SpeechRecognizer.ERROR_AUDIO -> {
                        if (selectedLocale.language == "fi") {
                            "Ääniongelma havaittu. Yritä uudelleen."
                        } else {
                            "Audio error detected. Please try again."
                        }
                    }
                    else -> {
                        if (selectedLocale.language == "fi") {
                            "Tapahtui virhe. Yritä uudelleen."
                        } else {
                            "An error occurred. Please try again."
                        }
                    }
                }

                chatHistory.add(errorMessage to MessageType.Server)

            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partialText = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                currentUserMessage = partialText.orEmpty()
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onEndOfSpeech() {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onRmsChanged(rmsdB: Float) {}
        })


        speechRecognizer.startListening(intent)
        Log.d("SpeechRecognition", "Selected Locale: ${selectedLocale.language}-${selectedLocale.country}")

    }


    // Hotword Detection Function
    fun startHotwordDetection(speechRecognizer: SpeechRecognizer) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale("fi", "FI").toString()) // You can dynamically switch locale if needed
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("HotWord", "Listening for Hotword")
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                matches?.forEach { recognizedText ->
                    Log.d("HotWord", "Recognized: $recognizedText")
                    if (recognizedText.contains("Hey Pulse", ignoreCase = true)) {
                        startListening("/chat")
                    } else if (recognizedText.contains("Record Pulse", ignoreCase = true)) {
                        startListening("/record")
                    }
                    else{
                        speechRecognizer.startListening(intent)
                    }
                }
            }

            override fun onError(error: Int) {
                // Handle errors (as in your existing code)
                Log.d("HotWord", "Error: $error")
                speechRecognizer.startListening(intent)

            }

            override fun onPartialResults(partialResults: Bundle?) {
                // Handle partial results (as in your existing code)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onEndOfSpeech() {
                speechRecognizer.startListening(intent)
            }
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onRmsChanged(rmsdB: Float) {}
        })

        // Start listening immediately for hotwords
        speechRecognizer.startListening(intent)
    }

    // Start listening for hotwords as soon as the composable is loaded
    LaunchedEffect(Unit) {
        startHotwordDetection(speechRecognizer)
    }


    LaunchedEffect(chatHistory.size) {
        if (chatHistory.isNotEmpty()) {
            listState.animateScrollToItem(chatHistory.size - 1)
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top section with Logo and Icons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Pulse",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Security Icon wrapped in a Box for consistency
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Private Mode",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.clickable {
                                // Toggle security mode
                                isSecurityModeActive = !isSecurityModeActive
                            }
                        )
                    }

                    // Language Icon and Dropdown Menu
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = if (selectedLocale.language == "en") "ENG" else "FIN",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.clickable { isLanguageMenuExpanded = true }
                        )

                        DropdownMenu(
                            expanded = isLanguageMenuExpanded,
                            onDismissRequest = { isLanguageMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("English") },
                                onClick = {
                                    selectedLocale = Locale("en", "FI")
                                    isLanguageMenuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Finnish") },
                                onClick = {
                                    selectedLocale = Locale("fi", "FI")
                                    isLanguageMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // Chat Section or Instruction Section
            if (chatHistory.isEmpty() && currentUserMessage.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "How can I help you today?",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (!isSecurityModeActive) {
                        Text(
                            text = "Say \"Hey Pulse\" or \"Record Pulse\" or press a button to get started.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )
                    }

                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    reverseLayout = false
                ) {
                    items(chatHistory) { (message, messageType) ->
                        ChatBubble(message = message, messageType = messageType)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (currentUserMessage.isNotEmpty()) {
                        item {
                            ChatBubble(message = currentUserMessage, messageType = MessageType.Chat)
                        }
                    }
                }
            }

            // Bottom Navigation Section with Toggle Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Security Mode Section
                if (isSecurityModeActive) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Toggle Button to Switch Between /chat and /record
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(if (isChatActive) "Chat Mode" else "Record Mode")
                            Spacer(modifier = Modifier.width(16.dp))
                            Switch(
                                checked = isChatActive,
                                onCheckedChange = { isChatActive = it }
                            )
                        }

                        // Text Box and Send Button
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextField(
                                value = customMessage,
                                onValueChange = { customMessage = it },
                                label = { Text("Enter Message") },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    val messageToServer = customMessage
                                    val route = if (isChatActive) "/chat" else "/record"
                                    chatHistory.add(messageToServer to (if (isChatActive) MessageType.Chat else MessageType.Record))


                                    coroutineScope.launch {
                                        if (route === "/chat") {
                                            NetworkUtils.sendToChatAsync(
                                                client,
                                                sessionID,
                                                messageToServer,
                                                selectedLocale
                                            ) { serverResponse ->
                                                chatHistory.add(serverResponse to MessageType.Server)
                                                if (textToSpeech.isSpeaking) {
                                                    textToSpeech.stop()
                                                }
                                                if (selectedLocale.language == "fi") {
                                                    textToSpeech.setLanguage(Locale("fi", "FI"))
                                                } else {
                                                    textToSpeech.setLanguage(Locale("en", "US"))
                                                }
                                                textToSpeech.speak(
                                                    serverResponse,
                                                    TextToSpeech.QUEUE_FLUSH,
                                                    null,
                                                    null
                                                )
                                            }
                                        } else if (route === "/record") {
                                            NetworkUtils.sendToRecordAsync(
                                                client,
                                                sessionID,
                                                messageToServer,
                                                selectedLocale
                                            ) { serverResponse ->
                                                chatHistory.add(serverResponse to MessageType.Server)
                                                if (textToSpeech.isSpeaking) {
                                                    textToSpeech.stop()
                                                }
                                                if (selectedLocale.language == "fi") {
                                                    textToSpeech.setLanguage(Locale("fi", "FI"))
                                                } else {
                                                    textToSpeech.setLanguage(Locale("en", "US"))
                                                }
                                                textToSpeech.speak(
                                                    serverResponse,
                                                    TextToSpeech.QUEUE_FLUSH,
                                                    null,
                                                    null
                                                )
                                            }
                                        }
                                    }
                                    customMessage = "" // Clear the input
                                }
                            ) {
                                Text("Send")
                            }
                        }
                    }
                } else {
                    // Default Bottom Section with Chat and Record Icons
                    IconButton(
                        onClick = {
                            isChatActive = true
                            isRecordActive = false
                            startListening("/chat")
                        },
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChatBubble,
                            contentDescription = "Chat",
                            tint = if (isChatActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                        )
                    }
                    IconButton(
                        onClick = {
                            isRecordActive = true
                            isChatActive = false
                            startListening("/record")
                        },
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.NoteAlt,
                            contentDescription = "Record",
                            tint = if (isRecordActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }
    }
}