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

class MainActivity : ComponentActivity() { // Removed TextToSpeech.OnInitListener

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>

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


        setContent {
            PulseTheme {
                VoiceAssistantApp()
            }
        }
    }

}