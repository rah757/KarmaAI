package com.example.falldetect

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.pager.ExperimentalPagerApi
import com.google.accompanist.pager.HorizontalPager
import com.google.accompanist.pager.rememberPagerState
import kotlinx.coroutines.delay
import java.util.*

@OptIn(ExperimentalPagerApi::class)
@Composable
fun SwipeableHomeScreen(
    onStartFallDetection: () -> Unit,
    onStartObjectDetection: () -> Unit
) {
    // Create a pager state for 4 pages.
    val pagerState = rememberPagerState(initialPage = 0)
    val context = LocalContext.current

    // Create and initialize TextToSpeech with an American voice.
    val tts = remember {
        var ttsInstance: TextToSpeech? = null
        ttsInstance = TextToSpeech(context, object : TextToSpeech.OnInitListener {
            override fun onInit(status: Int) {
                if (status == TextToSpeech.SUCCESS) {
                    ttsInstance?.apply {
                        language = Locale.US
                        val availableVoices = voices?.filter { it.locale == Locale.US }
                        if (!availableVoices.isNullOrEmpty()) {
                            voice = availableVoices.first()
                        }
                    }
                }
            }
        })
        ttsInstance!!
    }

    DisposableEffect(Unit) {
        onDispose { tts.shutdown() }
    }

    // Trigger an initial speech immediately after composition.
    LaunchedEffect(Unit) {
        // Wait a little to ensure TTS is ready.
        delay(500)
        tts.speak(
            "Welcome to KARMA AI. Swipe left to proceed",
            TextToSpeech.QUEUE_FLUSH, null, "init"
        )
    }

    // Speak out the page content when the current page changes.
    LaunchedEffect(key1 = pagerState.currentPage) {
        when (pagerState.currentPage) {
            0 -> tts.speak(
                "Welcome to KARMA AI. Swipe left to proceed",
                TextToSpeech.QUEUE_FLUSH, null, "page0"
            )
            1 -> tts.speak(
                "To Start Emergency Detection, tap on the screen. To navigate to the next button, swipe left.",
                TextToSpeech.QUEUE_FLUSH, null, "page1"
            )
            2 -> tts.speak(
                "To Start Navigation. Tap on the screen. To return to emergency detection, swipe left.",
                TextToSpeech.QUEUE_FLUSH, null, "page2"
            )
            3 -> tts.speak(
                "KARMA AI here, how may I assist you?",
                TextToSpeech.QUEUE_FLUSH, null, "page3"
            )
        }
    }

    HorizontalPager(
        count = 4,
        state = pagerState,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        when (page) {
            0 -> {
                // First screen: App description.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .clickable { /* Optionally trigger TTS here */ },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Welcome to KARMA AI\n\nSwipe left to proceed",
                        style = MaterialTheme.typography.headlineLarge,
                        textAlign = TextAlign.Center
                    )
                }
            }
            1 -> {
                // Second screen: Emergency Detection button.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onStartFallDetection() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Start Emergency Detection",
                        style = MaterialTheme.typography.headlineLarge,
                        textAlign = TextAlign.Center
                    )
                }
            }
            2 -> {
                // Third screen: Navigation button.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onStartObjectDetection() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Start Navigation",
                        style = MaterialTheme.typography.headlineLarge,
                        textAlign = TextAlign.Center
                    )
                }
            }
            3 -> {
                // Fourth screen: Assistant screen.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .clickable { /* Optionally trigger TTS again or add an action */ },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Hey there, I'm KARMA AI! \nhow may I assist you?",
                        style = MaterialTheme.typography.headlineLarge,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
