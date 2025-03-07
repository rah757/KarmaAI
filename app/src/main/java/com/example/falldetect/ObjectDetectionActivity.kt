package com.example.falldetect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.android.gms.tasks.Tasks
import android.speech.tts.TextToSpeech
import androidx.lifecycle.lifecycleScope
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import com.google.gson.JsonParser
import com.google.gson.Gson
import okhttp3.logging.HttpLoggingInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.Executors

class ObjectDetectionActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var previewView: PreviewView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var captureButton: Button
    private var mImageCapture: ImageCapture? = null
    private lateinit var tts: TextToSpeech

    // ML Kit object detection and image labeling clients.
    private val objectDetector by lazy {
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()    // Detect more than one object at a time
            .enableClassification()     // Enable classification
            .build()
        ObjectDetection.getClient(options)
    }

    private val imageLabeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
    }

    // --- New Properties for Periodic TTS ---
    @Volatile
    private var currentDetectedLabel: String? = null
    @Volatile
    private var currentDetectedConfidence: Float = 0f

    // Time until which periodic TTS announcements are paused.
    private var ttsPausedUntil: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_object_detection)

        previewView = findViewById(R.id.previewView)
        tts = TextToSpeech(this, this)
        captureButton = findViewById(R.id.captureButton)
        captureButton.setOnClickListener {
            // Pause periodic TTS announcements for 15 seconds when a capture is triggered.
            ttsPausedUntil = System.currentTimeMillis() + 15000
            capturePhoto()
            Log.d("CaptureButton", "Describe scene button clicked")
        }
        startCamera()

        // Launch a coroutine that runs every second and speaks the highest-confidence label.
        lifecycleScope.launch {
            while (true) {
                delay(2200) // Run every 2.2 second
                if (System.currentTimeMillis() >= ttsPausedUntil) {
                    currentDetectedLabel?.let { label ->
                        speak(label)
                    }
                }
                // Reset for the next window.
                currentDetectedLabel = null
                currentDetectedConfidence = 0f
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            processImageProxy(imageProxy)
        }

        mImageCapture = ImageCapture.Builder().build()
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis, mImageCapture)
            Log.d("CameraBinding", "Camera use-cases bound successfully.")
        } catch (exc: Exception) {
            Log.e("ObjectDetection", "Camera binding failed", exc)
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            // Process with object detector.
            val objectDetectionTask = objectDetector.process(image)
                .addOnSuccessListener { detectedObjects ->
                    for (detectedObject in detectedObjects) {
                        for (label in detectedObject.labels) {
                            // Filter out the words "good" and "home" from the label.
                            val rawLabel = label.text
                            val filteredLabel = rawLabel
                                .replace("good", "", ignoreCase = true)
                                .replace("home", "", ignoreCase = true)
                                .trim()
                            // Update if this label has higher confidence than the current one.
                            if (label.confidence > currentDetectedConfidence && filteredLabel.isNotEmpty()) {
                                currentDetectedConfidence = label.confidence
                                currentDetectedLabel = filteredLabel
                            }
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("ObjectDetection", "Object detection failed", e)
                }

            // Process with image labeler (optional; not used for TTS here).
            val imageLabelingTask = imageLabeler.process(image)
                .addOnSuccessListener { labels ->
                    for (label in labels) {
                        val filteredLabel = label.text
                            .replace("good", "", ignoreCase = true)
                            .replace("home", "", ignoreCase = true)
                            .trim()

                        if (label.confidence > currentDetectedConfidence && filteredLabel.isNotEmpty()) {
                            currentDetectedConfidence = label.confidence
                            currentDetectedLabel = filteredLabel
                        }
                    }
                }

            // Wait for both tasks to complete before closing the imageProxy.
            Tasks.whenAllComplete(objectDetectionTask, imageLabelingTask)
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    /**
     * Helper function to convert an ImageProxy (in YUV or JPEG) into a Bitmap.
     */
    @OptIn(ExperimentalGetImage::class)
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val mediaImage = imageProxy.image ?: return null

        return if (mediaImage.format == ImageFormat.YUV_420_888 && mediaImage.planes.size == 3) {
            val yBuffer = mediaImage.planes[0].buffer
            val uBuffer = mediaImage.planes[1].buffer
            val vBuffer = mediaImage.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, mediaImage.width, mediaImage.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, mediaImage.width, mediaImage.height), 100, out)
            val imageBytes = out.toByteArray()

            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } else if (mediaImage.format == ImageFormat.JPEG && mediaImage.planes.size == 1) {
            val jpegBuffer = mediaImage.planes[0].buffer
            val jpegBytes = ByteArray(jpegBuffer.remaining())
            jpegBuffer.get(jpegBytes)
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } else {
            Log.e("ImageProcessing", "Unsupported image format: ${mediaImage.format} with ${mediaImage.planes.size} planes")
            null
        }
    }

    /**
     * Captures a snapshot from the current video preview,
     * converts it to a Bitmap, then to a Base64-encoded string,
     * and sends it via an API call.
     * Also pauses periodic TTS announcements for 15 seconds.
     */
    private fun capturePhoto() {
        val imageCapture = mImageCapture ?: return
        Log.d("CapturePhoto", "Capture photo initiated")
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val bitmap = imageProxyToBitmap(imageProxy)
                    imageProxy.close()

                    bitmap?.let {
                        val byteArrayOutputStream = ByteArrayOutputStream()
                        it.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)
                        val imageBytes = byteArrayOutputStream.toByteArray()
                        val base64String = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                        sendRequest(base64String)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("Snapshot", "Photo capture failed: ${exception.message}", exception)
                }
            }
        )
    }

    fun sendRequest(base64String:String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val logging = HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                }
                val client = OkHttpClient.Builder()
                    .addInterceptor(logging)
                    .connectTimeout(6000, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(6000, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(6000, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                // Replace with your endpoint URL
                val url =
                    "https://us-central1-aiplatform.googleapis.com/v1/projects/primal-insight-452907-v7/locations/us-central1/publishers/google/models/gemini-1.0-pro-vision-001:generateContent"

                // The JSON payload as a raw string (formatted for readability)

                val jsonBodyBefore = """
                        {
                            "contents": [
                                {
                                    "role": "user",
                                    "parts": [
                                        {
                                            "text": "I am a blind person. I want to get a response which explains everything in the image as a glimpse. if there are too many items in the scene, only describe the most prominent things. This is supposed to be a tts input, so do it accordingly. (when u respond, do not refer as image, refer it as scene). Also, i am trying to walk right? so briefly suggest if there is a path for me. if there is something specific like cash or so, you can say the denomination and so on.  "
                                        },
                                        {
                                            "inlineData": {
                                                "data": "
                                                """.trim()
                val jsonBodyBase64String = base64String.trim()
                val jsonBodyAfter = """ ",
                                            "mimeType": "image/jpeg"
                                        }
                                    }
                                ]
                            }
                        ]
                    }
                    """.trim()
                var jsonBody = jsonBodyBefore + jsonBodyBase64String
                jsonBody += jsonBodyAfter
                val jsonElement = JsonParser.parseString(jsonBody)
                jsonBody = Gson().toJson(jsonElement)
                // Build the request
                Log.d("Request", jsonBody)
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = jsonBody.toRequestBody(mediaType)
                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .addHeader(
                        "Authorization",
                        "Bearer ${BuildConfig.GOOGLE_OAUTH2_TOKEN}"
                    )
                    //.addHeader("Content-Type", "application/json")
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseString = response.body?.string()
                    println("Success: $responseString")
                    try {
                        val jsonResponse = responseString?.let { JSONObject(it) }
                        val candidates = jsonResponse?.getJSONArray("candidates")
                        if (candidates != null) {
                            if (candidates.length() > 0) {
                                val candidate = candidates.getJSONObject(0)
                                val content = candidate.getJSONObject("content")
                                val parts = content.getJSONArray("parts")
                                if (parts.length() > 0) {
                                    val candidateText = parts.getJSONObject(0).getString("text")
                                    Log.d("Candidate Text", candidateText)
                                    // Speak the candidate text
                                    speak(candidateText)
                                    // speakText(candidateText) Speak the response in bluetooth speaker
                                } else {
                                    Log.d(
                                        "No text under candidate found",
                                        "No text under candidate response"
                                    )
                                }
                            } else {
                                Log.d("No response found", "No response")
                            }
                        } else {
                            Log.d(
                                "Error forming response with candidates",
                                "error in candidates response"
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("JSON Parsing", "Error parsing response: ${e.message}", e)
                    }

                } else {
                    println("Error: ${response.code} - ${response.message}")
                }
            } catch (e: Exception) {
                println("Network Error: ${e.message}")
            }
        }
    }

    // Speak a message using TTS.
    private fun speak(message: String) {
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "")
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        }
    }
}
