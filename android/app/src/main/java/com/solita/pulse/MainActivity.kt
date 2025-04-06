package com.solita.pulse

import android.Manifest
import android.os.Bundle
// Removed TextToSpeech imports as Activity no longer handles it directly
import android.util.Log
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
// Removed SpeechRecognizer import
import com.solita.pulse.ui.VoiceAssistantApp
import com.solita.pulse.ui.theme.PulseTheme
// Removed UUID import unless needed for something else

class MainActivity : ComponentActivity() { // Removed TextToSpeech.OnInitListener

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>
    // Removed speechRecognizer, textToSpeech, and sessionID variables
    // private lateinit var speechRecognizer: SpeechRecognizer -> Managed by ViewModel/SpeechManager
    // private lateinit var textToSpeech: TextToSpeech -> Managed by ViewModel/SpeechManager
    // private val sessionID: String = UUID.randomUUID().toString() -> Managed by ViewModel or passed differently

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hide the ActionBar (top bar) using Window
        // Consider using edge-to-edge APIs for modern Android look and feel
        window.requestFeature(Window.FEATURE_NO_TITLE)

        // Initialize the permission launcher
        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.i("MainActivity", "RECORD_AUDIO permission granted.")
                // You could potentially notify the ViewModel here if needed,
                // but the Composable/ViewModel can also check permission status itself.
            } else {
                Log.w("MainActivity", "RECORD_AUDIO permission denied.")
                // Handle permission denial gracefully (e.g., show explanation, disable voice features)
                // The Composable/ViewModel should react to the permission state.
            }
        }

        // Request the permission
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

        // Removed STT/TTS initialization - ViewModel handles this via SpeechManager
        // speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        // textToSpeech = TextToSpeech(this, this, "com.google.android.tts")

        setContent {
            PulseTheme {
                // VoiceAssistantApp now gets its ViewModel instance internally using viewModel()
                // No need to pass context, stt, tts, or sessionID anymore.
                VoiceAssistantApp()
            }
        }
    }

    // Removed onInit - Activity no longer listens for TTS initialization
    // override fun onInit(status: Int) { ... }

    // Removed onDestroy - ViewModel's onCleared handles resource release via SpeechManager.release()
    // override fun onDestroy() {
    //     super.onDestroy()
    //     speechRecognizer.destroy()
    //     textToSpeech.shutdown()
    // }
}