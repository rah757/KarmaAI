package com.example.falldetect

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.QUEUE_FLUSH
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.widget.Button
import android.widget.TextView
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale

import android.content.Intent


class ChatGPTAssistantActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var assistantOutput: TextView
    private lateinit var micButton: Button

    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chatgpt_assistant)

        assistantOutput = findViewById(R.id.assistant_output)
        micButton = findViewById(R.id.mic_button)

        // Initialize TTS
        tts = TextToSpeech(this, this)

        // Initialize SpeechRecognizer
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        // Request mic permission if needed
        checkAudioPermission()

        micButton.setOnClickListener {
            startListening()
        }
    }

    // Check RECORD_AUDIO permission
    private fun checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), 123
            )
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { }
            override fun onBeginningOfSpeech() { }
            override fun onRmsChanged(rmsdB: Float) { }
            override fun onBufferReceived(buffer: ByteArray?) { }
            override fun onEndOfSpeech() { }
            override fun onPartialResults(partialResults: Bundle?) { }
            override fun onEvent(eventType: Int, params: Bundle?) { }
            override fun onError(error: Int) {
                speak("I didn't catch that. Please try again.")
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val userText = matches?.firstOrNull().orEmpty()
                assistantOutput.text = "You: $userText"
                callChatGPT(userText)
            }
        })

        speechRecognizer.startListening(intent)
    }


    // Step: Call ChatGPT
    private fun callChatGPT(userQuery: String) {
        if (userQuery.isBlank()) {
            speak("Please say something!")
            return
        }

        // Here we do a network call to ChatGPT. This is a minimal example using GPT-3.5-Turbo
        val url = "https://api.openai.com/v1/chat/completions"
        val requestBodyJson = """
        {
          "model": "gpt-4o-mini",
          "messages": [
            {"role": "system", "content": "You are a helpful assistant assisting a blind person. Be nice and help him out. keep responses short."},
            {"role": "user", "content": "$userQuery"}
          ]
        }
        """.trimIndent()

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = requestBodyJson.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
            .header("Authorization", "Bearer ")
            .post(body)
            .build()

        // Make network call asynchronously
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val reply = parseChatGPTResponse(responseBody)
                    withContext(Dispatchers.Main) {
                        assistantOutput.text = assistantOutput.text.toString() + "\nKarmaBot: $reply"
                        speak(reply)
                    }
                } else {
                    Log.e("ChatGPT", "Error: ${response.code} ${response.message}")
                    withContext(Dispatchers.Main) {
                        speak("An error occurred while communicating with ChatGPT.")
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatGPT", "Exception: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    speak("Failed to connect to ChatGPT.")
                }
            }
        }
    }

    private fun parseChatGPTResponse(json: String?): String {
        return try {
            val jsonObj = JSONObject(json ?: "")
            val choices = jsonObj.getJSONArray("choices")
            val firstChoice = choices.getJSONObject(0)
            val message = firstChoice.getJSONObject("message")
            message.getString("content").trim()
        } catch (e: Exception) {
            "Sorry, I couldn't parse the response."
        }
    }

    // TTS
    private fun speak(text: String) {
        tts.speak(text, QUEUE_FLUSH, null, "ChatGPT_Reply")
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
        speechRecognizer.destroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        }
    }
}
