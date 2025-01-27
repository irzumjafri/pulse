package com.solita.pulse

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale

object NetworkUtils {
    //private const val SERVER_ADDRESS = "http://10.0.2.2:5000"
    private const val SERVER_ADDRESS = "https://fluent-macaw-suitably.ngrok-free.app"

    fun sendToChatAsync(client: OkHttpClient, userId: String, message: String, locale: Locale, callback: (String) -> Unit) {
        val url = "$SERVER_ADDRESS/chat"
        sendToServerAsync(client, userId, message, locale, url, callback)
    }

    fun sendToRecordAsync(client: OkHttpClient, userId: String, message: String, locale: Locale, callback: (String) -> Unit) {
        val url = "$SERVER_ADDRESS/record"
        sendToServerAsync(client, userId, message, locale, url, callback)
    }

    private fun sendToServerAsync(client: OkHttpClient, userId: String, message: String, locale: Locale, url: String, callback: (String) -> Unit) {
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