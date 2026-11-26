package io.github.mahendrabishnoi2.flyoff

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.mahendrabishnoi2.flyoff.map.BLANK_STYLE_JSON
import io.github.mahendrabishnoi2.flyoff.map.MapLibreMap
import io.github.mahendrabishnoi2.flyoff.ui.theme.FlyoffTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            FlyoffTheme {
                MapLibreMap(styleJson = BLANK_STYLE_JSON, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
