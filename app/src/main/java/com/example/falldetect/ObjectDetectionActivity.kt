package com.example.falldetect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
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
import java.util.concurrent.Executors
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.io.ByteArrayOutputStream
import android.util.Base64
import android.app.Activity
import android.speech.tts.TextToSpeech
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import com.google.gson.JsonParser
import com.google.gson.Gson
import okhttp3.logging.HttpLoggingInterceptor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

//import com.google.mlkit.vision.label.ImageLabelerOptions
//import com.google.mlkit.vision.label.defaults.ImageLabelerOptions

class ObjectDetectionActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var previewView: PreviewView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var captureButton: Button  // <-- ADDED: Button reference for capturing snapshot
    private var mImageCapture : ImageCapture? = null
    private lateinit var tts: TextToSpeech
    // Configure ML Kit's Object Detector in streaming mode
    private val objectDetector by lazy {
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()    // Detect more than one object at a time
            .enableClassification()     // Optional: to classify detected objects
            .build()
        ObjectDetection.getClient(options)
    }

    private val imageLabeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_object_detection)

        previewView = findViewById(R.id.previewView)
        tts = TextToSpeech(this, this)
        captureButton = findViewById(R.id.captureButton)  // <-- ADDED: Find the capture button in layout
        captureButton.setOnClickListener { capturePhoto() }
        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {
        // Preview use case: displays the camera feed
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        // Image analysis use case: processes each frame
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            processImageProxy(imageProxy)
        }

        mImageCapture = ImageCapture.Builder().build()

        // Select the back camera as the default
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            // Unbind any previous use cases and bind the new ones
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis, mImageCapture)
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, mImageCapture)
            Log.d("CameraBinding", "Camera use-cases bound successfully.")
        } catch (exc: Exception) {
            Log.e("ObjectDetection", "Camera binding failed", exc)
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            // Create an InputImage from the camera frame
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            // Process image using ML Kit's Object Detector
            val objectDetectionTask = objectDetector.process(image)
                .addOnSuccessListener { detectedObjects ->
                    for (detectedObject in detectedObjects) {
                        for (label in detectedObject.labels) {
                            if(label.confidence > 0.9) {
//                                Log.d(
//                                    "ObjectDetection",
//                                    "Detected: ${label.text} with confidence: ${label.confidence}"
//                                )
                                //add later
                            }
                        }
                    }
                }
                .addOnFailureListener { e ->
//                    Log.e("ObjectDetection", "Object detection failed", e)
                    //add later
                }

            // Process image using ML Kit's Image Labeler
            val imageLabelingTask = imageLabeler.process(image)
                .addOnSuccessListener { labels ->
                    for (label in labels) {
                        if(label.confidence>0.9) {
//                            Log.d(
//                                "ImageLabeling",
//                                "Label: ${label.text} with confidence: ${label.confidence}"
//                            )
                        }
                    }
                }
                .addOnFailureListener { e ->
//                    Log.e("ImageLabeling", "Image labeling failed", e)
                }

            // Wait for both tasks to complete before closing the imageProxy
            Tasks.whenAllComplete(objectDetectionTask, imageLabelingTask)
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }


    /**
     * Captures a snapshot from the current video preview,
     * converts it to a Bitmap, then to a Base64-encoded string,
     * and logs the Base64 string.
     */
    // Speak a message using Text-to-Speech.
    private fun speak(message: String) {
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "")
    }

    private fun capturePhoto() {
        // Ensure imageCapture is available.
        val imageCapture = mImageCapture ?: return

        // Capture the image using the ImageCapture use-case.
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    // Convert the captured ImageProxy to a Bitmap.
                    val bitmap = imageProxyToBitmap(imageProxy)
                    imageProxy.close() // Close the ImageProxy after processing.

                    bitmap?.let {
                        // Compress the Bitmap to JPEG and convert it to a byte array.
                        val byteArrayOutputStream = ByteArrayOutputStream()

                        it.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)
                        val imageBytes = byteArrayOutputStream.toByteArray()

                        // Convert the byte array to a Base64 string.
                        val base64String = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                        sendRequest(base64String)
                    }
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
                                            "text": "I am a blind person. I want to get a response which explains everything in the image as a glimpse.if there are too many items in the scene, only describe the most prominent things. This is supposed to be a tts input, so do it accordingly. (when u respond, do not refer as image, refer it as scene). Also, i am trying to walk right? so briefly suggest if there is a path for me. "
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
                                    "Bearer ya29.a0AeXRPp7asLjaDKIeYRQOWYGxzsTgSATI1-r8ok74KiDn8jMD84HjVZy9oM5e1fZsDKZjAfVBQmkjK-EhYoQoAiVu9w18upducLHy-lh-Vu6SmAycWZ1u5iOm1jbIfSgwMmsvlnMplD_HreUxLba0aezE3z4UdB6fA_5zjDE1aCgYKAWwSARMSFQHGX2Mi7rP71CRD5IdH508cGs55Uw0175"
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
                                                    Log.d("No text under candidate found","No text under candidate response")
                                                }
                                            } else {
                                                Log.d("No response found","No response")
                                            }
                                        }else{
                                            Log.d("Error forming response with candidates","error in candidates response")
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

//
//                    client.newCall(request).enqueue(object : Callback {
//                        override fun onFailure(call: Call, e: IOException) {
//                            Log.e("HTTP Request", "Error: ${e.message}", e)
//                        }
//
//                        override fun onResponse(call: Call, response: Response) {
//                            Log.d("Here reached","Hear reached")
//                            response.use {
//                                if (!response.isSuccessful) {
//                                    Log.e("HTTP Request $request", "Unexpected response code: ${response.code}")
//                                    return
//                                }
//                                val responseBody = response.body?.string()
//                                Log.d("HTTP Response", responseBody ?: "No response body")
//
//                                // Parse the JSON response and extract the text
//                                responseBody?.let {
//                                    try {
//                                        val jsonResponse = JSONObject(it)
//                                        val candidates = jsonResponse.getJSONArray("candidates")
//                                        if (candidates.length() > 0) {
//                                            val candidate = candidates.getJSONObject(0)
//                                            val content = candidate.getJSONObject("content")
//                                            val parts = content.getJSONArray("parts")
//                                            if (parts.length() > 0) {
//                                                val candidateText = parts.getJSONObject(0).getString("text")
//                                                Log.d("Candidate Text", candidateText)
//                                                // Speak the candidate text
//                                                // speakText(candidateText) Speak the response in bluetooth speaker
//                                            } else {
//                                                Log.d("No text under candidate found","No text under candidate response")
//                                            }
//                                        } else {
//                                            Log.d("No response found","No response")
//                                        }
//                                    } catch (e: Exception) {
//                                        Log.e("JSON Parsing", "Error parsing response: ${e.message}", e)
//                                    }
//                                }
//                            }
//                        }
//                    })


                }


                override fun onError(exception: ImageCaptureException) {
                    Log.e("Snapshot", "Photo capture failed: ${exception.message}", exception)
                }
            }
        )
    }

    /**
     * Helper function to convert an ImageProxy (in YUV format) into a Bitmap.
     */
    @OptIn(ExperimentalGetImage::class)
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val mediaImage = imageProxy.image ?: return null

        return if (mediaImage.format == ImageFormat.YUV_420_888 && mediaImage.planes.size == 3) {
            // ✅ YUV_420_888 Handling (3 planes)
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
            // ✅ JPEG Handling (1 plane)
            val jpegBuffer = mediaImage.planes[0].buffer
            val jpegBytes = ByteArray(jpegBuffer.remaining())
            jpegBuffer.get(jpegBytes)

            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } else {
            Log.e("ImageProcessing", "Unsupported image format: ${mediaImage.format} with ${mediaImage.planes.size} planes")
            null
        }
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
