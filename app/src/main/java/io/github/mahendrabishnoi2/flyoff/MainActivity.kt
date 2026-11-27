package io.github.mahendrabishnoi2.flyoff

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.mahendrabishnoi2.flyoff.ui.FlyoffScreen
import io.github.mahendrabishnoi2.flyoff.ui.theme.FlyoffTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The map style is always light, so the system bars always get dark icons,
        // even when the phone is in dark mode.
        val lightBars = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = lightBars, navigationBarStyle = lightBars)
        super.onCreate(savedInstanceState)
        setContent {
            FlyoffTheme {
                FlyoffScreen()
            }
        }
    }
}
