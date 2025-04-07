package com.solita.pulse.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject // Keep for now if you don't switch resetUserContext body
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerializationException // Import for catching parsing errors

// --- Data Classes for API Responses (using kotlinx.serialization) ---

@Serializable
data class RequestIdResponse(
    val request_id: String // Match the JSON key from the server
)

@Serializable
data class PatientResponse(
    val response: String,
    val patient_name: String? = null, // Match JSON key, allow null
    val SSN: String? = null // Match JSON key, allow null
) {
    // Helper to get masked SSN
    val maskedSsn: String?
        get() = SSN?.takeLast(4)?.let { "*******$it" }
}

@Serializable
data class StatusResponse(
    val status: String, // "processing", "cancelling", "completed", "cancelled", "error"
    val request_id: String, // Match JSON key
    val data: PatientResponse? = null, // Only present when status is "completed"
    val error: String? = null // Only present when status is "error"
)

@Serializable
data class CancelResponse(
    val message: String
)

@Serializable
data class ResetResponse(
    val message: String
)

// --- Sealed Class for representing API call results ---

sealed class NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>()
    data class Error(val code: Int?, val message: String) : NetworkResult<Nothing>()
    data object NetworkError : NetworkResult<Nothing>() // For connectivity issues etc.
}


object NetworkUtils {
    // Use your ngrok URL or local IP when testing
    // private const val SERVER_ADDRESS = "http://10.0.2.2:5000"
    private const val SERVER_ADDRESS = "https://fluent-macaw-suitably.ngrok-free.app"

    // Configure OkHttpClient (consider tweaking timeouts if needed)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS) // Shorter connect timeout
        .readTimeout(90, TimeUnit.SECONDS)    // Longer read timeout for potentially slow AI
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // Configure JSON parser (ignore keys not defined in data classes)
    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    // --- Public API Functions (using Coroutines) ---

    /**
     * Starts a chat request. Returns the unique request ID on success.
     */
    suspend fun startChat(userId: String, message: String, locale: Locale, isHandsFree: Boolean): NetworkResult<String> {
        val url = "$SERVER_ADDRESS/chat"
        val requestBody = createJsonRequestBody(
            "user_id" to userId,
            "message" to message,
            "language" to locale.language,
            "handsfree" to isHandsFree
        )
        return makePostRequestForId(url, requestBody)
    }

    /**
     * Starts a record request. Returns the unique request ID on success.
     */
    suspend fun startRecord(userId: String, message: String, locale: Locale, isHandsFree: Boolean): NetworkResult<String> {
        val url = "$SERVER_ADDRESS/record"
        val requestBody = createJsonRequestBody(
            "user_id" to userId,
            "message" to message,
            "language" to locale,
            "handsfree" to isHandsFree
        )
        return makePostRequestForId(url, requestBody)
    }

    /**
     * Checks the status of an ongoing request.
     */
    suspend fun checkStatus(requestId: String): NetworkResult<StatusResponse> {
        val url = "$SERVER_ADDRESS/status/$requestId"
        val request = Request.Builder().url(url).get().build()
        return makeRequest(request) { responseBody ->
            jsonParser.decodeFromString<StatusResponse>(responseBody)
        }
    }

    /**
     * Sends a cancellation request for the given request ID.
     */
    suspend fun cancelRequest(requestId: String): NetworkResult<CancelResponse> {
        val url = "$SERVER_ADDRESS/cancel/$requestId"
        // Cancellation often uses POST without a body, adjust if server requires one
        val request = Request.Builder().url(url).post("".toRequestBody(null)).build() // Empty POST body
        return makeRequest(request) { responseBody ->
            jsonParser.decodeFromString<CancelResponse>(responseBody)
        }
    }

    /**
     * Resets the user context on the server.
     */
    suspend fun resetUserContext(userId: String): NetworkResult<ResetResponse> {
        val url = "$SERVER_ADDRESS/reset_user_context"
        // Server expects user_id in the body for reset endpoint
        val requestBody = createJsonRequestBody("user_id" to userId)
        val request = Request.Builder().url(url).post(requestBody).build()
        return makeRequest(request) { responseBody ->
            jsonParser.decodeFromString<ResetResponse>(responseBody)
        }
    }


    // --- Private Helper Functions ---

    /**
     * Creates a JSON RequestBody from key-value pairs.
     */
    private fun createJsonRequestBody(vararg pairs: Pair<String, Any?>): RequestBody {
        // Using kotlinx.serialization for consistency if possible, else fallback JSONObject
        val jsonObject = JSONObject()
        pairs.forEach { (key, value) -> jsonObject.put(key, value) }
        return jsonObject.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        /* // Example using kotlinx.serialization (requires a @Serializable data class)
           @Serializable data class GenericRequest(val user_id: String, val message: String? = null, ...)
           val requestData = GenericRequest(...)
           val jsonString = jsonParser.encodeToString(requestData)
           return jsonString.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
         */
    }

    /**
     * Helper for making POST requests that expect a RequestIdResponse.
     */
    private suspend fun makePostRequestForId(url: String, body: RequestBody): NetworkResult<String> {
        val request = Request.Builder().url(url).post(body).build()
        // Use the generic makeRequest helper
        return when (val result = makeRequest(request) { jsonParser.decodeFromString<RequestIdResponse>(it) }) {
            is NetworkResult.Success -> NetworkResult.Success(result.data.request_id) // Extract the ID
            is NetworkResult.Error -> result // Propagate error
            is NetworkResult.NetworkError -> result // Propagate error
        }
    }

    /**
     * Generic helper function to make network requests using OkHttp and coroutines.
     * Parses the response body using the provided parser function.
     */
    private suspend inline fun <T> makeRequest(
        request: Request,
        crossinline parser: (String) -> T // Function to parse the successful response body
    ): NetworkResult<T> {
        return withContext(Dispatchers.IO) { // Perform network call on IO thread
            try {
                client.newCall(request).execute().use { response -> // Use ensures resource closure
                    val responseBody = response.body?.string()

                    if (response.isSuccessful && responseBody != null) {
                        try {
                            val parsedData = parser(responseBody)
                            NetworkResult.Success(parsedData)
                        } catch (e: SerializationException) {
                            NetworkResult.Error(response.code, "Failed to parse response: ${e.message}")
                        } catch (e: Exception) { // Catch other potential parsing errors
                            NetworkResult.Error(response.code, "Error processing response: ${e.message}")
                        }
                    } else {
                        // Provide more specific error messages based on code if desired
                        val errorMsg = responseBody ?: response.message
                        NetworkResult.Error(response.code, "Server error: ${response.code} - $errorMsg")
                    }
                }
            } catch (e: Exception) { // Catch IOExceptions, UnknownHostException, etc.
                NetworkResult.NetworkError // Indicate a network-level failure
            }
        }
    }
}