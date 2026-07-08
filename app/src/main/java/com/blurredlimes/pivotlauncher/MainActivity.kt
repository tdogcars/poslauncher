package com.blurredlimes.pivotlauncher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    // Incremented on every HOME press that re-delivers the intent (singleTask),
    // so the UI snaps back from settings to the icon screen.
    private val homeIntentTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind transparent system bars; the black root surface makes the
        // whole panel one continuous field. dark() keeps the bar icons light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )
        setContent { LauncherApp(homeIntentTick.intValue) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homeIntentTick.intValue++
    }
}

private val LauncherColorScheme = darkColorScheme(
    background = Color.Black,
    surface = Color.Black,
)

@Composable
private fun LauncherApp(homeIntentTick: Int) {
    val context = LocalContext.current
    val config by remember(context) { LauncherPrefs.configFlow(context) }
        .collectAsStateWithLifecycle(initialValue = null)
    var showSettings by remember { mutableStateOf(false) }

    // A HOME press always lands on the icon screen, even if the device was
    // left sitting in configuration.
    LaunchedEffect(homeIntentTick) { showSettings = false }

    MaterialTheme(colorScheme = LauncherColorScheme) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // Until DataStore emits, render nothing: the frame is already black.
            val loaded = config ?: return@Box
            if (showSettings) {
                SettingsScreen(config = loaded, onDone = { showSettings = false })
            } else {
                HomeScreen(config = loaded, onOpenSettings = { showSettings = true })
            }
        }
    }
}
