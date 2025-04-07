package com.solita.pulse.speech

import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.content.Context
import android.util.Log
import com.solita.pulse.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class HotwordDetector(
    private val context: Context,
    private val accessKey: String, // Pass access key during construction
    private val onHotwordDetected: (Int) -> Unit, // Callback for detection (1 for Hey Pulse, 2 for Record Pulse)
    private val onError: (String) -> Unit // Callback for errors
) {
    private var porcupineManager: PorcupineManager? = null
    private var isRunning = false

    // Copy keyword files lazily on first access (or during init)
    // Using lazy ensures it's done only once and only if needed.
    private val heyPulsePath: String? by lazy {
        getAbsolutePathForRawResource(context, R.raw.hey_pulse, "hey_pulse")
    }
    private val recordPulsePath: String? by lazy {
        getAbsolutePathForRawResource(context, R.raw.record_pulse, "record_pulse")
    }

    private val wakeWordCallback = PorcupineManagerCallback { keywordIndex ->
        Log.d("HotwordDetector", "Detected keyword index: $keywordIndex")
        if (isRunning) { // Only trigger if started and not stopped/released
            when (keywordIndex) {
                0 -> onHotwordDetected(1) // Map index 0 ("Hey Pulse") to type 1
                1 -> onHotwordDetected(2) // Map index 1 ("Record Pulse") to type 2
                // Add more cases if you have more keywords
            }
        }
    }

    // Initialize and start detection
    fun start() {
        if (isRunning) {
            Log.w("HotwordDetector", "Already running.")
            return
        }
        if (porcupineManager != null) {
            Log.w("HotwordDetector", "PorcupineManager already initialized? Attempting to start.")
            // If already initialized but stopped, just try starting
            try {
                porcupineManager?.start()
                isRunning = true
                Log.i("HotwordDetector", "Hotword detection restarted.")
                return
            } catch (e: Exception) {
                Log.e("HotwordDetector", "Error restarting Porcupine", e)
                // Proceed to re-initialize if restart failed
                release() // Clean up existing instance first
            }
        }


        Log.d("HotwordDetector", "Attempting to initialize and start Porcupine...")

        // Validate Access Key
        if (accessKey.isBlank() || accessKey == "YOUR_ACCESS_KEY_HERE" || !accessKey.matches(Regex("^[a-zA-Z0-9/+=]+$"))) {
            val errorMsg = "Porcupine Access Key is missing or invalid!"
            Log.e("HotwordDetector", errorMsg)
            onError(errorMsg)
            return
        }

        // Validate keyword file paths
        val keywordPaths = listOfNotNull(heyPulsePath, recordPulsePath) // Filter out null paths
        if (keywordPaths.size < 2) { // Check if both paths were resolved
            val errorMsg = "Failed to load one or more keyword files. HeyPulse: $heyPulsePath, RecordPulse: $recordPulsePath"
            Log.e("HotwordDetector", errorMsg)
            onError(errorMsg)
            return
        }
        // Ensure the array matches the expected order if sensitivities are position-dependent
        val finalKeywordPaths = arrayOf(heyPulsePath!!, recordPulsePath!!)
        val sensitivities = floatArrayOf(0.7f, 0.7f) // Adjust sensitivities as needed

        // Log paths being used
        Log.d("HotwordDetector", "Using Keyword Path 1: ${finalKeywordPaths[0]}")
        Log.d("HotwordDetector", "Using Keyword Path 2: ${finalKeywordPaths[1]}")

        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywordPaths(finalKeywordPaths) // Use the validated paths array
                .setSensitivities(sensitivities)
                // Add error listener directly to the builder if the SDK supports it
                // .setErrorCallback { e -> onError(e.message ?: "Unknown Porcupine Error") }
                .build(context, wakeWordCallback)

            porcupineManager?.start() // Start listening
            isRunning = true
            Log.i("HotwordDetector", "Hotword detection started successfully!")
        } catch (e: PorcupineException) {
            val errorMsg = "Porcupine Initialization/Start Error: ${e.message}"
            Log.e("HotwordDetector", errorMsg, e) // Log exception too
            onError(errorMsg)
            release() // Clean up if failed
        } catch (e: Exception) { // Catch other potential errors during build/start
            val errorMsg = "Generic error starting Porcupine: ${e.message}"
            Log.e("HotwordDetector", errorMsg, e)
            onError(errorMsg)
            release()
        }
    }

    // Stop detection temporarily
    fun stop() {
        if (!isRunning) return
        try {
            porcupineManager?.stop()
            isRunning = false
            Log.i("HotwordDetector", "Hotword detection stopped.")
        } catch (e: PorcupineException) {
            Log.e("HotwordDetector", "Error stopping Porcupine: ${e.message}")
            // Don't necessarily call onError here, as stopping might be intentional
        } catch (e: Exception) {
            Log.e("HotwordDetector", "Generic error stopping Porcupine", e)
        }
    }

    // Release resources permanently
    fun release() {
        stop() // Ensure stopped before deleting
        try {
            porcupineManager?.delete()
            Log.d("HotwordDetector", "PorcupineManager released.")
        } catch (e: PorcupineException) {
            Log.e("HotwordDetector", "Error deleting Porcupine: ${e.message}")
        } catch (e: Exception) {
            Log.e("HotwordDetector", "Generic error deleting Porcupine", e)
        } finally {
            porcupineManager = null // Ensure reference is cleared
            isRunning = false
        }
    }

    // Utility function to copy resource to cache and get path
    private fun getAbsolutePathForRawResource(context: Context, resourceId: Int, tempPrefix: String): String? {
        val cacheDir = context.cacheDir
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        // Use a consistent filename in the cache based on prefix
        val tempFile = File(cacheDir, "$tempPrefix.ppn")

        try {

            context.resources.openRawResource(resourceId).use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                    Log.d("HotwordDetector", "Copied raw resource $resourceId to ${tempFile.absolutePath}")
                }
            }
            return tempFile.absolutePath
        } catch (e: IOException) {
            Log.e("HotwordDetector", "Error copying raw resource $resourceId: ${e.message}", e)
            return null
        } catch (e: Exception) {
            Log.e("HotwordDetector", "Unexpected error getting path for resource $resourceId: ${e.message}", e)
            return null
        }
    }
}