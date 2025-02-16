package com.solita.pulse.speech

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.content.Context
import android.util.Log
import com.solita.pulse.BuildConfig
import com.solita.pulse.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

fun getAbsolutePathForRawResource(context: Context, resourceId: Int): String? {
    return try {
        // Create a temporary file
        val tempFile = File.createTempFile("temp_${resourceId}_", ".ppn", context.cacheDir)
        tempFile.deleteOnExit()

        // Copy the raw resource to the temporary file
        context.resources.openRawResource(resourceId).use { inputStream ->
            FileOutputStream(tempFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }

        // Return the absolute path of the temporary file
        tempFile.absolutePath
    } catch (e: IOException) {
        e.printStackTrace()
        null
    }
}

class HotwordDetector(
    private val context: Context,
    private val onHotwordDetected: (Int) -> Unit
) {
    private var porcupineManager: PorcupineManager? = null

    fun startHotwordDetection() {
        try {
            // Define the callback for hotword detection
            val wakeWordCallback = PorcupineManagerCallback { keywordIndex ->
                when (keywordIndex) {
                    0 -> { // First keyword detected
                        Log.d("HotwordDetector", "Hotword 'Hey Pulse' detected!")
                        onHotwordDetected(1)
                    }

                    1 -> { // Second keyword detected
                        Log.d("HotwordDetector", "Hotword 'Record Pulse' detected!")
                        onHotwordDetected(2)
                    }
                }
            }

            Log.d("Hotword Detector", "PORCUPINE_ACCESS_KEY: ${BuildConfig.PORCUPINE_ACCESS_KEY}")

            // Initialize PorcupineManager using the Builder pattern
            val heyPulsePath = getAbsolutePathForRawResource(context, R.raw.hey_pulse)
            val recordPulsePath = getAbsolutePathForRawResource(context, R.raw.record_pulse)
            Log.d("HotwordDetector", "heyPulsePath: $heyPulsePath")
            Log.d("HotwordDetector", "heyPulsePath: $recordPulsePath")

            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(BuildConfig.PORCUPINE_ACCESS_KEY) // Replace with your actual API Key
                .setKeywordPaths(arrayOf(heyPulsePath,recordPulsePath))
                .setSensitivities(floatArrayOf(0.7f, 0.7f)) // Sensitivity for each keyword
                .build(context, wakeWordCallback)

            // Start listening for hotwords
            porcupineManager?.start()
            Log.d("HotwordDetector", "Hotword detection started!")
        } catch (e: PorcupineException) {
            Log.e("HotwordDetector", "Error initializing Porcupine: ${e.message}")
            e.printStackTrace()
        }
    }

    fun stopHotwordDetection() {
        porcupineManager?.stop()
        porcupineManager?.delete()
    }
}