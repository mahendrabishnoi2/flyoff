package io.github.mahendrabishnoi2.flyoff

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.mahendrabishnoi2.flyoff.ui.FlyoffScreen
import io.github.mahendrabishnoi2.flyoff.ui.theme.FlyoffTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            FlyoffTheme {
                FlyoffScreen()
            }
        }
    }
}
