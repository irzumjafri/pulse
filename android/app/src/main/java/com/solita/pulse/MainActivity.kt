package com.solita.pulse

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.android.volley.AuthFailureError
import com.android.volley.DefaultRetryPolicy
import com.android.volley.NetworkResponse
import com.android.volley.Response
import com.android.volley.RetryPolicy
import com.android.volley.VolleyError
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale


//import android.Manifest.permission.RECORD_AUDIO

class MainActivity : AppCompatActivity() {

    private lateinit var textView: TextView
    private lateinit var editText: EditText
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var intent: Intent
    private val stringURLEndPoint = "https://api.openai.com/v1/chat/completions"
    private val stringAPIKey = "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"
    private var stringOutput = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)

        textView = findViewById(R.id.textView)
        editText = findViewById(R.id.editText)

        textToSpeech = TextToSpeech(applicationContext) {
            textToSpeech!!.setLanguage(Locale.US)
            textToSpeech!!.setSpeechRate(2.5.toFloat())
        }

        intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                // Implement this method
            }

            override fun onBeginningOfSpeech() {
                // Implement this method
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Implement this method
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Implement this method
            }

            override fun onEndOfSpeech() {
                // Implement this method
            }

            override fun onError(error: Int) {
                // Implement this method
            }

            override fun onResults(bundle: Bundle?) {
                val matches: ArrayList<String>? = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                var string = ""

                if (matches != null) {
                    string = matches[0]
                    editText.setText(string)
                    chatGPTModel(string)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                // Implement this method
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                // Implement this method
            }
        })


        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }


    }

    fun buttonAssist(view: View) {

        if (textToSpeech?.isSpeaking == true) {
            textToSpeech?.stop()
            return
        }

        stringOutput = ""
        Log.d("ChatGPT", "Calling GPT")
        Log.d("ChatGPT", stringOutput)
        speechRecognizer.startListening(intent);


    }


    fun chatGPTModel(stringInput: String) {
        // Show a "loading" message
        textView.text = "In Progress..."

        // Text-to-Speech initiation
        textToSpeech?.speak(editText.text.toString(), TextToSpeech.QUEUE_FLUSH, null, null)

        // Prepare JSON payload
        val jsonObject = JSONObject()
        try {
            jsonObject.put("model", "gpt-3.5-turbo")

            val jsonArrayMessage = JSONArray()
            val jsonObjectMessage = JSONObject()
            jsonObjectMessage.put("role", "user")
            jsonObjectMessage.put("content", stringInput)

            jsonArrayMessage.put(jsonObjectMessage)
            jsonObject.put("messages", jsonArrayMessage)
        } catch (e: JSONException) {
            e.printStackTrace()
        }

        // Log the complete API request details
        Log.d("ChatGPT Request", "URL: $stringURLEndPoint")
        Log.d("ChatGPT Request", "Headers: Authorization=Bearer $stringAPIKey, Content-Type=application/json")
        Log.d("ChatGPT Request", "Body: ${jsonObject.toString()}")

        // Set up the network request
        val jsonObjectRequest = object : JsonObjectRequest(Method.POST, stringURLEndPoint, jsonObject,
            Response.Listener { response ->
                try {
                    // Parse the response
                    val stringText = response.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")

                    // Append and display response text
                    stringOutput += stringText
                    Log.d("ChatGPT", stringOutput)

                    textView.text = stringOutput
                    textToSpeech?.speak(stringOutput, TextToSpeech.QUEUE_FLUSH, null, null)

                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            },
            Response.ErrorListener { error ->
                // Enhanced error logging
                val errorMsg = error.message ?: "Unknown error"
                val statusCode = error.networkResponse?.statusCode
                val data = error.networkResponse?.data?.let { String(it) } // Convert error data to string if available

                Log.e("ChatGPT", "Error: $errorMsg, Status Code: $statusCode, Response Data: $data")
                textView.text = "Error occurred: $errorMsg, Status Code: $statusCode"
            }) {

            @Throws(AuthFailureError::class)
            override fun getHeaders(): Map<String, String> {
                val headers = HashMap<String, String>()
                headers["Authorization"] = "Bearer $stringAPIKey"
                headers["Content-Type"] = "application/json"
                return headers
            }

            override fun parseNetworkError(volleyError: VolleyError): VolleyError {
                val responseData = volleyError.networkResponse?.data?.let { String(it) }
                Log.e("ChatGPT", "Network error: ${volleyError.message}, Response data: $responseData")
                return super.parseNetworkError(volleyError)
            }
        }

        // Set retry policy
        jsonObjectRequest.retryPolicy = DefaultRetryPolicy(
            60000, // 60 seconds
            DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
            DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
        )

        // Add the request to the request queue
        Volley.newRequestQueue(applicationContext).add(jsonObjectRequest)
    }

}