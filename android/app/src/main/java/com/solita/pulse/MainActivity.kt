package com.solita.pulse

import android.Manifest
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.solita.pulse.ui.VoiceAssistantApp
import com.solita.pulse.ui.theme.PulseTheme
import java.util.*

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
        textToSpeech = TextToSpeech(this, this, "com.google.android.tts")

        setContent {
            PulseTheme {
                VoiceAssistantApp(context = this, speechRecognizer = speechRecognizer, textToSpeech = textToSpeech, sessionID=sessionID)
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
