package com.solita.pulse.ui

import android.content.Context
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Text
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateColorAsState
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.graphicsLayer
import com.solita.pulse.models.MessageType
import com.solita.pulse.network.NetworkUtils
import com.solita.pulse.speech.HotwordDetector
import com.solita.pulse.speech.SpeechManager
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun VoiceAssistantApp(
    context: Context, speechRecognizer: SpeechRecognizer, textToSpeech: TextToSpeech, sessionID: String
) {

    var selectedLocale by remember { mutableStateOf(Locale("en", "US")) }
    val chatHistory = remember { mutableStateListOf<Pair<String, MessageType>>() }
    var currentUserMessage by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var isChatActive by remember { mutableStateOf(true) } // Default to chat mode
    var isRecordActive by remember { mutableStateOf(false) }
    var isSecurityModeActive by remember { mutableStateOf(false) }
    var isLanguageMenuExpanded by remember { mutableStateOf(false) }
    var isListening by remember { mutableIntStateOf(0) }
    var customMessage by remember { mutableStateOf("") }
    var serverMessagePopup by remember { mutableStateOf("") }
    var patientName by remember { mutableStateOf("") }
    var patientSSN by remember { mutableStateOf("") }

    var hotWordDetected by remember { mutableIntStateOf(0) }
    fun updateHotWordDetected(newValue: Int) {
        hotWordDetected = newValue
    }
    val hotwordDetector = remember {
        HotwordDetector(
            context = context,
            ::updateHotWordDetected
        )
    }


    fun updateIsListening(newValue: Int) {
        if (newValue in 0..2) {
            isListening = newValue
        } else {
            Log.e("VoiceAssistantApp", "Invalid value for isListening: $newValue")
        }
    }

    val animatedColor1 by animateColorAsState(
        targetValue = when (isListening) {
            1 -> colorScheme.primaryContainer.copy(alpha = 0.5f)
            2 -> Color(0xFF5A202F).copy(alpha = 0.5f)
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 1000, easing = FastOutLinearInEasing), label = ""
    )
    val animatedColor2 by animateColorAsState(
        targetValue = when (isListening) {
            1 -> colorScheme.primary.copy(alpha = 0.2f)
            2 -> Color(0xFFFF7A90).copy(alpha = 0.2f)
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 1000, easing = FastOutLinearInEasing), label = ""
    )

    val backgroundGradient =
        Brush.verticalGradient(
            listOf(Color.Transparent, animatedColor1, animatedColor2)
        )

    LaunchedEffect(chatHistory.size) {
        if (chatHistory.isNotEmpty()) {
            listState.animateScrollToItem(chatHistory.size - 1)
        }
    }

    LaunchedEffect(hotWordDetected) {
        when (hotWordDetected) {
            1 -> {
                textToSpeech.stop()
                isChatActive = true
                isRecordActive = false
                SpeechManager.startListening(
                    speechRecognizer,
                    selectedLocale,
                    sessionID,
                    chatHistory,
                    coroutineScope,
                    "/chat",
                    textToSpeech,
                    ::updateIsListening
                )
            }
            2 -> {
                textToSpeech.stop()
                isChatActive = false
                isRecordActive = true
                SpeechManager.startListening(
                    speechRecognizer,
                    selectedLocale,
                    sessionID,
                    chatHistory,
                    coroutineScope,
                    "/record",
                    textToSpeech,
                    ::updateIsListening
                )
            }
            else -> {
                Log.d("VoiceAssistantApp", "No Hotword Detected.")
            }
        }
    }



    // Start hotword detection when the app launches
//    LaunchedEffect(Unit) {
//        Log.d("VoiceAssistantApp", "Starting Hotword Detection")
//        hotwordDetector.startHotwordDetection()
//    }

    // Clean up hotword detection when the app is disposed
//    DisposableEffect(Unit) {
//        onDispose {
//            Log.d("VoiceAssistantApp", "Stopping Hotword Detection")
//            hotwordDetector.stopHotwordDetection()
//        }
//    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .background(backgroundGradient, alpha = 1f)
                .graphicsLayer { alpha = 0.99f }
                .fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween
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
                    style = MaterialTheme.typography.titleLarge,
                    color = colorScheme.onBackground
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val privateModeColor = if (isSecurityModeActive) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }

                    val privateModeIcon = if(isSecurityModeActive){
                        Icons.Default.LockOpen
                    } else{
                        Icons.Default.Lock
                    }
                    // Security Icon wrapped in a Box for consistency
                    Button(
                        onClick = {
                            // Toggle security mode
                            isSecurityModeActive = !isSecurityModeActive
                            Log.d(

                                "VoiceAssistantApp",
                                "Security mode toggled: $isSecurityModeActive"
                            )
                        },
                        modifier = Modifier.padding(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = privateModeIcon,
                                tint = privateModeColor,
                                contentDescription = "Private Mode"
                            )
                            Text(text = if(isSecurityModeActive) "Exit Private Mode" else "Enter Private Mode")
                        }
                    }

                    // Language Icon and Dropdown Menu
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = if (selectedLocale.language == "en") "ENG" else "FIN",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colorScheme.onBackground,
                            modifier = Modifier.clickable { isLanguageMenuExpanded = true })

                        DropdownMenu(modifier = Modifier.width(120.dp),
                            expanded = isLanguageMenuExpanded,
                            onDismissRequest = { isLanguageMenuExpanded = false }) {
                            DropdownMenuItem(text = { Text("English") }, onClick = {
                                //CALL CONTEXT RESET
                                NetworkUtils.resetUserContext(sessionID)
                                {serverResponse ->
                                    Log.d("VoiceAssistantApp", "Server Response: $serverResponse")
                                    if (serverResponse.isNotEmpty()) {
                                        patientName = serverResponse.split(",")[0]
                                        serverMessagePopup = serverResponse
                                    }
                                    chatHistory.clear()
                                }
                                if (serverMessagePopup.isNotEmpty()) {
                                    serverMessagePopup = ""
                                }

                                selectedLocale = Locale("en", "US")
                                isLanguageMenuExpanded = false
                            })
                            DropdownMenuItem(text = { Text("Finnish") }, onClick = {
                                selectedLocale = Locale("fi", "FI")
                                //CALL CONTEXT RESET
                                NetworkUtils.resetUserContext(sessionID)
                                {serverResponse ->
                                    Log.d("VoiceAssistantApp", "Server Response: $serverResponse")
                                    if (serverResponse.isNotEmpty()) {
                                        patientName = serverResponse.split(",")[0]
                                        serverMessagePopup = serverResponse
                                    }
                                    chatHistory.clear()
                                }
                                if (serverMessagePopup.isNotEmpty()) {
                                    serverMessagePopup = ""
                                }

                                isLanguageMenuExpanded = false
                            })
                        }
                    }
                }
            }

            // Chat Section or Instruction Section
            if (chatHistory.isEmpty() && currentUserMessage.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (selectedLocale.language == "fi") "Miten voin auttaa tänään?" else "How can I help you today?" ,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colorScheme.onBackground,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
//                    if (!isSecurityModeActive) {
//                        Text(
//                            text = "Say \"Hey Pulse\" or \"Record Pulse\" or press a button to get started.",
//                            style = MaterialTheme.typography.bodyMedium,
//                            color = colorScheme.onBackground,
//                            textAlign = TextAlign.Center
//                        )
//                    }
                    if (!isSecurityModeActive) {
                        Text(
                            text = if (selectedLocale.language == "fi") "Paina nappia ja aloita.?" else "Press a button to get started.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )
                    }

                }
            } else {
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(50.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    if (patientName.isNotEmpty()) {
                        Column {
                            Text(
                                text = "Patient: $patientName",
                                style = MaterialTheme.typography.titleSmall,
                                color = colorScheme.onBackground
                            )
                            Text(text = "SSN: $patientSSN",
                                style = MaterialTheme.typography.bodySmall, color = colorScheme.onBackground)
                        }
                    } else{
                        Text(text = "No Patient in Context",
                            style = MaterialTheme.typography.titleSmall,
                            color = colorScheme.onBackground)
                    }


                    Button(
                        modifier = Modifier.padding(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colorScheme.error,
                            contentColor = colorScheme.onError
                        ),
                        onClick = {
                            // Toggle security mode
                            chatHistory.clear()
                            //CALL CONTEXT RESET
                            NetworkUtils.resetUserContext(sessionID)
                            {serverResponse ->
                                Log.d("VoiceAssistantApp", "Server Response: $serverResponse")
                                if (serverResponse.isNotEmpty()) {
                                    patientName = ""
                                }
                            }
                        },
                    ) {
                        Text(text = "Clear Chat")
                    }
                }
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
                            ChatBubble(
                                message = currentUserMessage, messageType = MessageType.Chat
                            )
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
                    BottomMessageBar(isChatActive = isChatActive,
                        customMessage = customMessage,
                        onMessageChange = { customMessage = it },
                        onToggleChatMode = { isChatActive = !isChatActive },
                        onSendMessage = {
                            val messageToServer = customMessage
                            val route = if (isChatActive) "/chat" else "/record"
                            chatHistory.add(messageToServer to (if (isChatActive) MessageType.Chat else MessageType.Record))

                            coroutineScope.launch {
                                if (route === "/chat") {
                                    chatHistory.add("Pulse AI is Thinking..." to MessageType.Server)
                                    NetworkUtils.sendToChatAsync(
                                        sessionID, messageToServer, selectedLocale
                                    ) { serverResponse ->
                                        chatHistory[chatHistory.size-1] = serverResponse to MessageType.Server
                                        if (textToSpeech.isSpeaking) {
                                            textToSpeech.stop()
                                        }
                                        if (selectedLocale.language == "fi") {
                                            textToSpeech.setLanguage(Locale("fi", "FI"))
                                        } else {
                                            textToSpeech.setLanguage(Locale("en", "US"))
                                        }
                                        textToSpeech.speak(
                                            serverResponse, TextToSpeech.QUEUE_FLUSH, null, null
                                        )
                                    }
                                } else if (route === "/record") {
                                    chatHistory.add("Pulse AI is Thinking..." to MessageType.Server)
                                    NetworkUtils.sendToRecordAsync(
                                        sessionID, messageToServer, selectedLocale
                                    ) { serverResponse ->
                                        chatHistory[chatHistory.size-1] = serverResponse to MessageType.Server
                                        if (textToSpeech.isSpeaking) {
                                            textToSpeech.stop()
                                        }
                                        if (selectedLocale.language == "fi") {
                                            textToSpeech.setLanguage(Locale("fi", "FI"))
                                        } else {
                                            textToSpeech.setLanguage(Locale("en", "US"))
                                        }
                                        textToSpeech.speak(
                                            serverResponse, TextToSpeech.QUEUE_FLUSH, null, null
                                        )
                                    }
                                }
                            }
                            customMessage = "" // Clear the input
                        },
                        selectedLocale = selectedLocale.language)
                } else {
                    if (serverMessagePopup.isNotEmpty()) {
                        AlertDialog(
                            onDismissRequest = { serverMessagePopup = "" },
                            title = { Text("Langauge Changed Successfully.") },
                            text = { Text("Your language has been changed to ${if (selectedLocale.language == "fi") "Finnish" else "English"} and chat history has been reset.") },
                            confirmButton = {
                                Button(onClick = { serverMessagePopup = "" }) {
                                    Text("OK")
                                }
                            }
                        )
                    }
                    // Default Bottom Section with Chat and Record Icons
                    BottomAssistantBar(
                        isListening,
                        onChatClick = {
                        textToSpeech.stop()
                        isChatActive = true
                        isRecordActive = false
                        SpeechManager.startListening(
                            speechRecognizer,
                            selectedLocale,
                            sessionID,
                            chatHistory,
                            coroutineScope,
                            "/chat",
                            textToSpeech,
                            ::updateIsListening
                        )
                    }, onRecordClick = {
                        textToSpeech.stop()
                        isChatActive = false
                        isRecordActive = true
                        SpeechManager.startListening(
                            speechRecognizer,
                            selectedLocale,
                            sessionID,
                            chatHistory,
                            coroutineScope,
                            "/record",
                            textToSpeech,
                            ::updateIsListening
                        )

                    },
                        onStopClick = {
                            textToSpeech.stop()
                            isChatActive = true
                            isRecordActive = false
                            SpeechManager.stopListening(speechRecognizer, chatHistory, ::updateIsListening)
                        },
                        selectedLocale.language
                        )
                }
            }
        }
    }


}
