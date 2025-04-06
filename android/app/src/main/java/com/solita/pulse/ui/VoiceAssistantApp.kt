package com.solita.pulse.ui // Or your UI package

import android.Manifest
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel // Required dependency
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.solita.pulse.models.MessageType // Adjust import if needed
// Removed direct NetworkUtils/SpeechManager imports
// import com.solita.pulse.speech.HotwordDetector // Keep if using hotword (ViewModel handles now)
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale

@OptIn(ExperimentalPermissionsApi::class) // For Accompanist Permissions
@Composable
fun VoiceAssistantApp(
    // Inject ViewModel
    viewModel: AssistantViewModel = viewModel()
) {
    // --- State Collection ---
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current // Get context if needed

    // State for text input in secure mode
    var customMessage by remember { mutableStateOf("") }
    // State for language dropdown
    var isLanguageMenuExpanded by remember { mutableStateOf(false) }
    // State for one-time popups/snackbars
    var serverMessagePopupText by remember { mutableStateOf<String?>(null) }
    // State for Help Dialog
    var showHelpDialog by rememberSaveable { mutableStateOf(false) }


    // --- Permissions Handling ---
    val recordAudioPermissionState = rememberPermissionState(
        Manifest.permission.RECORD_AUDIO
    )
    // Request permission if not granted (can be triggered multiple times if needed)
    LaunchedEffect(recordAudioPermissionState.status) {
        if (!recordAudioPermissionState.status.isGranted) {
            Log.i("VoiceAssistantApp", "Launching permission request...")
            recordAudioPermissionState.launchPermissionRequest()
        }
    }

    // --- UI Effects ---

    // Scroll to bottom when chat history changes
    LaunchedEffect(uiState.chatHistory.size) {
        if (uiState.chatHistory.isNotEmpty()) {
            // Scrolls when history grows
            listState.animateScrollToItem(uiState.chatHistory.size - 1)
            Log.d("VoiceAssistantApp", "Scrolling to index ${uiState.chatHistory.size - 1} due to history change.")
        }
    }

    // Scroll to bottom when partial transcript appears/updates
    LaunchedEffect(uiState.currentTranscript, uiState.isListening) {
        val shouldShowPartial = uiState.isListening && uiState.currentTranscript.isNotEmpty()
        if (shouldShowPartial) {
            // Calculate index based on history size (partial item conceptually comes after)
            val targetIndex = uiState.chatHistory.size
            listState.animateScrollToItem(targetIndex)
            Log.d("VoiceAssistantApp", "Scrolling to index $targetIndex for partial transcript.")
        }
    }


    // Handle one-time UI Events from ViewModel
    LaunchedEffect(Unit) {
        viewModel.uiEvents.collectLatest { event ->
            when (event) {
                is UiEvent.ShowSnackbar -> {
                    // TODO: Implement Snackbar display logic using a SnackbarHostState
                    Log.i("VoiceAssistantApp", "Snackbar Event: ${event.message}")
                    serverMessagePopupText = "Info: ${event.message}" // Temporary display via dialog
                }
                is UiEvent.ShowLanguageChangeConfirmation -> {
                    serverMessagePopupText = "Language changed to ${if (event.locale.language == "fi") "Finnish" else "English"}. Chat reset."
                }
            }
        }
    }

    // --- UI Animations ---
    val isActuallyListening = uiState.isListening
    val isRecordingModeActive = uiState.isRecordingMode

    val animatedColor1 by animateColorAsState(
        targetValue = when {
            isActuallyListening && !isRecordingModeActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) // Chat listening
            isActuallyListening && isRecordingModeActive -> Color(0xFF5A202F).copy(alpha = 0.5f) // Record listening
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 1000, easing = FastOutLinearInEasing), label = "animColor1"
    )
    val animatedColor2 by animateColorAsState(
        targetValue = when {
            isActuallyListening && !isRecordingModeActive -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) // Chat listening
            isActuallyListening && isRecordingModeActive -> Color(0xFFFF7A90).copy(alpha = 0.2f) // Record listening
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 1000, easing = FastOutLinearInEasing), label = "animColor2"
    )

    val backgroundGradient = Brush.verticalGradient(listOf(Color.Transparent, animatedColor1, animatedColor2))


    // --- Composable UI Structure ---
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .background(backgroundGradient, alpha = 1f)
                .graphicsLayer { alpha = 0.99f } // Workaround for potential gradient rendering issues
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
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Security Icon Button
                    Button(
                        onClick = { viewModel.toggleSecurityMode() },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (uiState.isSecurityModeActive) Icons.Default.LockOpen else Icons.Default.Lock,
                                tint = if (uiState.isSecurityModeActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                contentDescription = if (uiState.isSecurityModeActive) "Exit Private Mode" else "Enter Private Mode"
                            )
                            Text(text = if (uiState.isSecurityModeActive) "Exit Private" else "Enter Private") // Shorter Text
                        }
                    }

                    // Language Icon and Dropdown Menu
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = if (uiState.selectedLocale.language == "en") "ENG" else "FIN",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.clickable { isLanguageMenuExpanded = true }
                        )
                        DropdownMenu(
                            modifier = Modifier.width(120.dp),
                            expanded = isLanguageMenuExpanded,
                            onDismissRequest = { isLanguageMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("English") },
                                onClick = {
                                    viewModel.setLocale(Locale("en", "US"))
                                    isLanguageMenuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Finnish") },
                                onClick = {
                                    viewModel.setLocale(Locale("fi", "FI"))
                                    isLanguageMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            } // End Top Row


            // --- Main Content Area (Instructions or Chat Area) ---
            // Determine which main content to show based on state
            val showInstructions = uiState.chatHistory.isEmpty() && !uiState.isListening && !uiState.isLoading
            val showChatArea = !showInstructions && !uiState.isLoading

            // Use Box to occupy the space, switching content inside
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {

                if (uiState.isLoading) {
                    // Loading indicator centered in the Box
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                } else if (showInstructions) {
                    // Instructions Section
                    Column(
                        modifier = Modifier
                            .fillMaxSize() // Fill the Box
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (uiState.selectedLocale.language == "fi") "Miten voin auttaa tänään?" else "How can I help you today?",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // --- Updated Instruction Hint Logic ---
                        val canUseVoice = recordAudioPermissionState.status.isGranted && uiState.ttsInitialized
                        val showHotwordHint = canUseVoice && !uiState.isSecurityModeActive

                        if (showHotwordHint) {
                            Text(
                                text = if (uiState.selectedLocale.language == "fi") {
                                    "Sano \"Hey Pulse\" tai \"Record Pulse\" tai paina nappia."
                                } else {
                                    "Say \"Hey Pulse\" or \"Record Pulse\", or press a button."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                textAlign = TextAlign.Center
                            )
                        } else if (canUseVoice && uiState.isSecurityModeActive) {
                            Text(
                                text = if (uiState.selectedLocale.language == "fi") {
                                    "Turvatila: Käytä tekstinsyöttöä."
                                } else {
                                    "Secure Mode: Type below to avoid giving out information to anyone around."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                textAlign = TextAlign.Center
                            )
                        } else if (!recordAudioPermissionState.status.isGranted) {
                            Text(
                                "Please grant microphone permission.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        } else if (!uiState.ttsInitialized) {
                            Text(
                                "Initializing speech services...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                        // --- End Instruction Hint Logic ---


                        // Display Error messages in instruction view
                        uiState.error?.let { errorMsg ->
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = errorMsg,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        }
                        // --- >> ADD HELP BUTTON HERE << ---
                        Spacer(modifier = Modifier.height(24.dp)) // Add some space before the button
                        Button(onClick = { showHelpDialog = true }) { // Set state to true on click
                            Text("Show Help / Instructions")
                        }
                        // ----------------------------------
                    } // End Instructions Column

                } else if (showChatArea) {
                    // Chat Area (Contains LazyColumn)
                    // Displayed if history is non-empty OR if listening has started
                    Column(modifier = Modifier.fillMaxSize()) { // Fill the Box

                        // Patient Info and Clear Button Row (Only show if history has content)
                        if (uiState.chatHistory.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 5.dp) // Reduced padding
                                    .height(IntrinsicSize.Min), // Adjust height dynamically
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Display Patient Name/SSN if available
                                if (!uiState.patientName.isNullOrEmpty()) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(
                                            text = "Patient: ${uiState.patientName}",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (!uiState.maskedSsn.isNullOrEmpty()) {
                                            Text(
                                                text = "SSN: ${uiState.maskedSsn}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onBackground
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        text = "No Patient in Context",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                                    )
                                }

                                // Clear Chat Button
                                Button(
                                    modifier = Modifier.padding(start = 8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    ),
                                    enabled = !uiState.isLoading && !uiState.isProcessingNetwork,
                                    onClick = { viewModel.clearChatAndContext() },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(text = "Clear", style = MaterialTheme.typography.labelSmall)
                                }
                            } // End Patient Info Row
                        } else {
                            // Optional: Add a placeholder or Spacer if history is empty but listening
                            Spacer(modifier = Modifier.height(40.dp)) // Adjust height as needed, roughly matches the Row height
                        }


                        // Chat History List (Always composed when showChatArea is true)
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f) // Takes remaining space in this inner Column
                                .padding(horizontal = 16.dp),
                            reverseLayout = false
                        ) {
                            // History items (renders nothing if history is empty)
                            items(uiState.chatHistory) { (message, messageType) ->
                                ChatBubble(message = message, messageType = messageType)
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            // Partial transcript (Now works on first message too)
                            if (uiState.isListening && uiState.currentTranscript.isNotEmpty()) {
                                item {
                                    // Log for debugging if needed
                                    // Log.d("VoiceAssistantApp UI", "RENDERING partial transcript item.")
                                    ChatBubble(
                                        message = uiState.currentTranscript,
                                        messageType = if (uiState.isRecordingMode) MessageType.Record else MessageType.Chat,
                                        isPartial = true // You'll need to handle this style in ChatBubble
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }

                            // Thinking indicator
                            if (uiState.isProcessingNetwork && uiState.chatHistory.lastOrNull()?.second != MessageType.Server) {
                                item {
                                    ChatBubble(
                                        message = if (uiState.selectedLocale.language == "fi") "Pulse AI miettii..." else "Pulse AI is Thinking...",
                                        messageType = MessageType.Server,
                                        isLoading = true // Handle this style in ChatBubble
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        } // End LazyColumn

                        // Display Error messages if chat area is visible
                        uiState.error?.let { errorMsg ->
                            Text(
                                text = errorMsg,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }

                    } // End Chat Area Column
                } // End if/else for main content
            } // End Box


            // --- Bottom Bar --- (Remains the same logic)
            Row(modifier = Modifier.fillMaxWidth()) {
                if (uiState.isProcessingNetwork) {
                    // Show Cancel button when network is processing
                    CancelRequestBar(
                        onCancelClick = viewModel::cancelCurrentNetworkJob,
                        selectedLocale = uiState.selectedLocale.language
                    )
                } else if (uiState.isSecurityModeActive) {
                    // Show Text Input Bar in Security Mode
                    BottomMessageBar(
                        isChatActive = !uiState.isRecordingMode,
                        customMessage = customMessage,
                        onMessageChange = { customMessage = it },
                        onToggleChatMode = { viewModel.toggleInputMode() },
                        onSendMessage = {
                            viewModel.sendTextMessage(customMessage, !uiState.isRecordingMode)
                            customMessage = ""
                        },
                        selectedLocale = uiState.selectedLocale.language
                    )
                } else {
                    // Show Voice Input Bar in Normal Mode
                    BottomAssistantBar(
                        listeningState = when {
                            uiState.isListening && !uiState.isRecordingMode -> 1 // Chat listening
                            uiState.isListening && uiState.isRecordingMode -> 2 // Record listening
                            else -> 0 // Idle
                        },
                        enabled = recordAudioPermissionState.status.isGranted && uiState.ttsInitialized && !uiState.isLoading,
                        onChatClick = {
                            if (recordAudioPermissionState.status.isGranted && uiState.ttsInitialized) {
                                viewModel.startListeningManually(isChatMode = true)
                            } else { Log.w("VoiceAssistantApp", "Chat click ignored: Permissions or TTS not ready.") }
                        },
                        onRecordClick = {
                            if (recordAudioPermissionState.status.isGranted && uiState.ttsInitialized) {
                                viewModel.startListeningManually(isChatMode = false)
                            } else { Log.w("VoiceAssistantApp", "Record click ignored: Permissions or TTS not ready.") }
                        },
                        onStopClick = { viewModel.stopListening() },
                        selectedLocale = uiState.selectedLocale.language
                    )
                }
            } // End Bottom Bar Row


        } // End Main Column (in Scaffold)

        if (showHelpDialog) {
            AlertDialog(
                onDismissRequest = { showHelpDialog = false }, // Close on outside click or back press
                title = { Text("Help / Instructions") },
                text = {
                    // Use a Column with scrolling for potentially long text
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        // Section: Buttons
                        Text("Buttons:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(" • Chat Button: Press to ask questions about the current patient (mention name/room/SSN to switch).")
                        Text(" • Record Button: Press to dictate a note for the current patient.")
                        Text(" • Stop Listening: Appears while listening; press to stop.")
                        Text(" • Private Mode: Toggle Private Mode (see below).")
                        Text(" • Language (ENG/FIN): Switch language (resets chat).")
                        Text(" • Clear (in chat): Clears chat history and patient context.")
                        Text(" • Cancel (during processing): Stops the current AI request.")
                        Text(" • Secure Mode Toggle (Chat/Record): Sets mode for typed input.")
                        Spacer(modifier = Modifier.height(12.dp))

                        // Section: Private Mode
                        Text("Private Mode:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(" • What it is: Disables microphone (voice input, hotwords) and speaker (voice output). Active when Lock Open icon is shown.")
                        Text(" • How to use: Type messages/notes using the text bar. Use the adjacent toggle to set Chat/Record mode for typed input.")
                        Text(" • Why use it: For privacy or when voice is not desired.")
                        Text(" • Exiting: Tap 'Exit Private Mode'.")
                        Spacer(modifier = Modifier.height(12.dp))

                        // Section: Hotwords
                        Text("Hotwords (Voice Activation):", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(" • Availability: Only work when Private Mode is OFF & mic permission granted.")
                        Text(" • \"Hey Pulse\": Activates listening in Chat Mode (for questions).")
                        Text(" • \"Record Pulse\": Activates listening in Record Mode (for notes).")
                    }
                },
                confirmButton = {
                    Button(onClick = { showHelpDialog = false }) { // Close button
                        Text("Close")
                    }
                }
            )
        } // --- >> END HELP DIALOG << ---

        // --- Popups ---
        serverMessagePopupText?.let { message ->
            AlertDialog(
                onDismissRequest = { serverMessagePopupText = null },
                title = { Text("Information") }, // Generic title
                text = { Text(message) },
                confirmButton = {
                    Button(onClick = { serverMessagePopupText = null }) {
                        Text("OK")
                    }
                }
            )
        } // End Popups

    } // End Scaffold
}