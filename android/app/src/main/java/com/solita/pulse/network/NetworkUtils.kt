package com.solita.pulse.network

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

object NetworkUtils {
   private const val SERVER_ADDRESS = "http://10.0.2.2:5000"
    // private const val SERVER_ADDRESS = "https://fluent-macaw-suitably.ngrok-free.app"

    val client =  OkHttpClient.Builder().connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS).build()

    fun sendToChatAsync(userId: String, message: String, locale: Locale, callback: (String) -> Unit) {
        val url = "$SERVER_ADDRESS/chat"
        sendToServerAsync(userId, message, locale, url, callback)
    }

    fun sendToRecordAsync(userId: String, message: String, locale: Locale, callback: (String) -> Unit) {
        val url = "$SERVER_ADDRESS/record"
        sendToServerAsync(userId, message, locale, url, callback)
    }

    private fun sendToServerAsync(userId: String, message: String, locale: Locale, url: String, callback: (String) -> Unit) {
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

}