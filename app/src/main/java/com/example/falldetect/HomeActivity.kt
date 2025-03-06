package com.example.falldetect

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.falldetect.ui.theme.FallDetectTheme

class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FallDetectTheme {
                SwipeableHomeScreen(
                    onStartFallDetection = {
                        startActivity(Intent(this, FallDetectionActivity::class.java))
                    },
                    onStartObjectDetection = {
                        startActivity(Intent(this, ObjectDetectionActivity::class.java))
                    }
                )
            }
        }
    }
}
