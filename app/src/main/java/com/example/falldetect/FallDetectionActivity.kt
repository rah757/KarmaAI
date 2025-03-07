package com.example.falldetect

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import kotlin.math.sqrt

class FallDetectionActivity : ComponentActivity(), SensorEventListener, TextToSpeech.OnInitListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private lateinit var tts: TextToSpeech

    // Fall detection state variables
    private var isFalling = false
    private var fallStartTime: Long = 0

    // Sensitivity thresholds
    private val FREE_FALL_THRESHOLD = 4.2f      // Free-fall: below 4.2 m/s²
    private val IMPACT_THRESHOLD = 15.0f        // Impact: above 15 m/s² within the time window
    private val FALL_TIME_WINDOW = 1000L        // 1 second to detect impact after free fall

    // Shake detection threshold for canceling alert
    private val SHAKE_THRESHOLD = 25.0f

    // Flag to indicate if an emergency alert is pending
    private var alertPending = false

    // Timestamp when the alert was triggered.
    private var alertStartTime: Long = 0

    // Emergency contact details (replace with a real number)
    private val EMERGENCY_CONTACT_NUMBER = "5159169595"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Request permissions for CALL_PHONE and SEND_SMS
        requestPermissions()

        // Initialize sensor manager and accelerometer
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Initialize Text-to-Speech
        tts = TextToSpeech(this, this)

        // Minimal UI is sufficient (TTS is primary); you can set a basic layout.
        setContentView(android.R.layout.simple_list_item_1)
    }

    private fun requestPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS),
                1
            )
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Process accelerometer data for both fall detection and shake detection.
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            detectFall(event)
            detectShake(event)
        }
    }

    private fun detectFall(event: SensorEvent) {
        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]
        val accelerationMagnitude = sqrt(ax * ax + ay * ay + az * az)
        val currentTime = System.currentTimeMillis()

        // Check for free-fall condition (low acceleration)
        if (accelerationMagnitude < FREE_FALL_THRESHOLD && !isFalling) {
            isFalling = true
            fallStartTime = currentTime
            Log.d("FallDetection", "Free fall detected.")
        }

        // Once in free-fall, check for a high acceleration impact within the time window
        if (isFalling) {
            if (accelerationMagnitude > IMPACT_THRESHOLD && (currentTime - fallStartTime) < FALL_TIME_WINDOW) {
                Log.d("FallDetection", "Fall confirmed with impact: $accelerationMagnitude m/s²")
                // Change screen background to red when fall is detected.
                runOnUiThread {
                    window.decorView.setBackgroundColor(Color.RED)
                }
                // Updated TTS message with "KARMA AI"
                speak("You have fallen. KARMA AI is calling help within 10 seconds. If you are okay, please shake the phone to cancel.")
                alertPending = true
                alertStartTime = currentTime  // Record when alert was triggered

                // Wait for 10 seconds; if no cancel is detected, call emergency contact and then send an SMS.
                lifecycleScope.launch {
                    delay(10000)
                    if (alertPending) {
                        speak("Calling emergency contact now.")
                        makeEmergencyCall()
                        // Optionally, wait a bit and then send an SMS if needed.
                        delay(10000)
                        if (alertPending) {
                            sendSOS()
                        }
                        alertPending = false
                    }
                }
                isFalling = false
            } else if ((currentTime - fallStartTime) >= FALL_TIME_WINDOW) {
                isFalling = false
            }
        }
    }

    private fun detectShake(event: SensorEvent) {
        if (alertPending) {
            val currentTime = System.currentTimeMillis()
            // Only allow cancellation if at least 1 second has passed since the alert started
            if (currentTime - alertStartTime < 1000L) return

            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]
            val accelerationMagnitude = sqrt(ax * ax + ay * ay + az * az)
            if (accelerationMagnitude > SHAKE_THRESHOLD) {
                // If a shake is detected while an alert is pending, cancel the emergency alert.
                alertPending = false
                speak("Alert canceled.")
                Log.d("FallDetection", "Emergency alert canceled by shaking the phone.")
                // Change screen background to green when alert is canceled.
                runOnUiThread {
                    window.decorView.setBackgroundColor(Color.GREEN)
                }
            }
        }
    }

    // Speak a message using Text-to-Speech.
    private fun speak(message: String) {
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "")
    }

    // Make an emergency phone call. If a direct call fails, open the dialer.
    private fun makeEmergencyCall() {
        Log.d("Debug", "Attempting to call $EMERGENCY_CONTACT_NUMBER")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$EMERGENCY_CONTACT_NUMBER"))
            startActivity(callIntent)
        } else {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$EMERGENCY_CONTACT_NUMBER"))
            startActivity(dialIntent)
            Log.e("EmergencyCall", "CALL_PHONE permission not granted. Opened dialer instead.")
        }
    }

    // Send an emergency SMS using an intent so that the SMS app opens pre-filled.
    private fun sendSOS() {
        Log.d("Debug", "Attempting to send SMS to $EMERGENCY_CONTACT_NUMBER")
        val locationData = "Location: (latitude, longitude)"  // Replace with real GPS data if available.
        val message = "Emergency Alert: A fall was detected. $locationData"
        val smsIntent = Intent(Intent.ACTION_VIEW, Uri.parse("sms:$EMERGENCY_CONTACT_NUMBER"))
        smsIntent.putExtra("sms_body", message)
        try {
            startActivity(smsIntent)
            Log.d("SOS", "Opened SMS app with message pre-filled.")
        } catch (e: Exception) {
            Log.e("SOS", "Failed to open SMS app.", e)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used in this implementation.
    }

    // TextToSpeech initialization callback.
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        }
    }
}
