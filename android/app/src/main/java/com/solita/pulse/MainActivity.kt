package com.solita.pulse

import android.Manifest
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Log
import android.content.Intent
import android.speech.RecognitionListener
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.solita.pulse.ui.theme.PulseTheme
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.*
import java.util.concurrent.TimeUnit

private const val serverIP = "153.1.146.170"

enum class MessageType {
    Chat, Record, Server
}

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var textToSpeech: TextToSpeech
    private val sessionID: String = UUID.randomUUID().toString()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hide the ActionBar (top bar) using Window
        window.requestFeature(Window.FEATURE_NO_TITLE)

        // Initialize the permission launcher
        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                println("Permission granted")
            } else {
                println("Permission for audio recording is required.")
            }
        }

        // Request the permission
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        textToSpeech = TextToSpeech(this, this)

        setContent {
            PulseTheme {
                VoiceAssistantApp(speechRecognizer = speechRecognizer, textToSpeech = textToSpeech, sessionID=sessionID)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val langResult = textToSpeech.setLanguage(Locale("fi", "FI"))
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TextToSpeech", "Finnish is not supported or missing data.")
            } else {
                Log.i("TextToSpeech", "Text-to-Speech initialized successfully for Finnish.")
            }
        } else {
            Log.e("TextToSpeech", "Initialization failed.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
        textToSpeech.shutdown()
    }
}

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
                            sendToChatAsync(client, sessionID, recognizedText, selectedLocale) { serverResponse ->
                                chatHistory.add(serverResponse to MessageType.Server)
                            }
                        } else if (route == "/record") {
                            sendToRecordAsync(client, sessionID, recognizedText, selectedLocale) { serverResponse ->
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
                    SpeechRecognizer.ERROR_NO_MATCH, 7 -> {
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
                            Text(if (isChatActive) "Data Mode" else "Document Mode")
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
                                            sendToChatAsync(client, sessionID, messageToServer, selectedLocale) { serverResponse ->
                                                chatHistory.add(serverResponse to MessageType.Server)
                                            }
                                        } else if (route === "/record") {
                                            sendToRecordAsync(client, sessionID, messageToServer, selectedLocale) { serverResponse ->
                                                chatHistory.add(serverResponse to MessageType.Server)
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

@Composable
fun ChatBubble(message: String, messageType: MessageType) {
    // Determine the bubble color based on the message type
    val bubbleColor = when (messageType) {
        MessageType.Chat -> MaterialTheme.colorScheme.primary // Blue for Chat
        MessageType.Record -> MaterialTheme.colorScheme.error // Red for Record
        MessageType.Server -> MaterialTheme.colorScheme.surfaceVariant // Grey for Server
    }

    // Determine alignment based on message type
    val alignment = when (messageType) {
        MessageType.Chat, MessageType.Record -> Alignment.CenterEnd // Align chat and record messages to the right
        MessageType.Server -> Alignment.CenterStart // Align server messages to the left
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .wrapContentSize(alignment)
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .padding(8.dp),
            shape = RoundedCornerShape(12.dp),
            color = bubbleColor
        ) {
            Text(
                text = message,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = if (messageType == MessageType.Chat || messageType == MessageType.Record)
                    MaterialTheme.colorScheme.onPrimary
                else
                    MaterialTheme.colorScheme.onSurface
            )
        }
    }
}


fun sendToChatAsync(client: OkHttpClient, userId: String, message: String, locale: Locale, callback: (String) -> Unit) {
    val url = "http://$serverIP:5000/chat"
    sendToServerAsync(client, userId, message, locale, url, callback)
}

fun sendToRecordAsync(client: OkHttpClient, userId: String, message: String, locale: Locale, callback: (String) -> Unit) {
    val url = "http://$serverIP:5000/record"
    sendToServerAsync(client, userId, message, locale, url, callback)
}

fun sendToServerAsync(client: OkHttpClient, userId: String, message: String, locale: Locale, url: String, callback: (String) -> Unit) {
    val json = JSONObject().apply {
        put("user_id", userId)
        put("message", message)
        put("language", locale.language)  // Add the language parameter here
    }

    val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
    val request = Request.Builder()
        .url(url)
        .post(body)
        .build()

    Thread {
        try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (response.isSuccessful && !responseBody.isNullOrBlank()) {
                    val jsonResponse = JSONObject(responseBody)
                    val serverResponse = jsonResponse.optString("response", "No response")
                    callback(serverResponse)
                } else {
                    callback("Error: ${response.code} - ${response.message}")
                }
            }
        } catch (e: Exception) {
            callback("Error: ${e.message}")
        }
    }.start()
}

@Preview(showBackground = true)
@Composable
fun VoiceInputAppPreview() {
    PulseTheme {
        VoiceAssistantApp(speechRecognizer = SpeechRecognizer.createSpeechRecognizer(null), textToSpeech = TextToSpeech(null, null), sessionID="user" )
    }
}