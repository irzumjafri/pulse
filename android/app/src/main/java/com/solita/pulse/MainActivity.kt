package com.solita.pulse

import android.Manifest
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Log
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var textToSpeech: TextToSpeech

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                VoiceAssistantApp(speechRecognizer = speechRecognizer, textToSpeech = textToSpeech)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val langResult = textToSpeech.setLanguage(Locale.getDefault())
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TextToSpeech", "Language is not supported or missing data.")
            } else {
                Log.i("TextToSpeech", "Text-to-Speech initialized successfully.")
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
    textToSpeech: TextToSpeech
) {
    var inputText by remember { mutableStateOf("Tap the microphone and start speaking...") }
    var isLoading by remember { mutableStateOf(false) }
    val chatHistory = remember { mutableStateListOf<Pair<String, String>>() }
    val userId = remember { UUID.randomUUID().toString() }

    // Custom OkHttpClient with increased timeout
    val httpClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Scroll to the bottom when chat history changes
    LaunchedEffect(chatHistory.size) {
        listState.animateScrollToItem(chatHistory.size)
    }

    val speechRecognitionListener = object : android.speech.RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            inputText = "Error occurred while recognizing speech. Please try again."
        }

        override fun onResults(results: Bundle?) {
            val spokenText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0)
            inputText = spokenText ?: ""
            chatHistory.add("You" to (spokenText ?: ""))
            isLoading = true

            sendToServerAsync(httpClient, userId, spokenText ?: "") { response ->
                coroutineScope.launch {
                    chatHistory.add("Assistant" to response)
                    isLoading = false
                    // Speak the response using Text-to-Speech
                    textToSpeech.speak(response, TextToSpeech.QUEUE_FLUSH, null, null)
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    speechRecognizer.setRecognitionListener(speechRecognitionListener)

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        if (chatHistory.isEmpty()) {
            // Initial screen with centered microphone button and branding
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground), // Replace with your logo resource ID
                    contentDescription = "Pulse Logo",
                    modifier = Modifier
                        .size(128.dp)
                        .padding(bottom = 16.dp)
                )

                IconButton(
                    onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                            )
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                        }
                        speechRecognizer.startListening(intent)
                    },
                    modifier = Modifier
                        .size(64.dp)
                        .padding(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = "Start Speaking",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
        } else {
            // Chat UI with history
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(chatHistory) { message ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (message.first == "You") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${message.first}: ${message.second}",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(16.dp),
                                textAlign = TextAlign.Start
                            )
                        }
                    }
                }

                if (isLoading) {
                    CircularProgressIndicator()
                }

                IconButton(
                    onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                            )
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                        }
                        speechRecognizer.startListening(intent)
                    },
                    modifier = Modifier
                        .size(64.dp)
                        .padding(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = "Start Speaking",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
        }
    }
}

fun sendToServerAsync(client: OkHttpClient, userId: String, message: String, callback: (String) -> Unit) {
    val url = "http://10.0.2.2:5000/chat"
    val json = JSONObject().apply {
        put("user_id", userId)
        put("message", message)
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
        VoiceAssistantApp(speechRecognizer = SpeechRecognizer.createSpeechRecognizer(null), textToSpeech = TextToSpeech(null, null))
    }
}
