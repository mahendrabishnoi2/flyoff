package io.github.mahendrabishnoi2.flyoff.map

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** A style with a single background layer. It has no sources, so it loads nothing. */
const val BLANK_STYLE_JSON = """
{
  "version": 8,
  "name": "Blank",
  "sources": {},
  "layers": [
    { "id": "background", "type": "background", "paint": { "background-color": "#dce4e8" } }
  ]
}
"""

/**
 * A MapLibre [MapView] in Compose. The view follows the host lifecycle and keeps
 * its camera across configuration changes and process death.
 */
@Composable
fun MapLibreMap(styleJson: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = rememberSaveable(saver = mapViewSaver(context)) {
        createMapView(context, savedState = null)
    }

    // Compose forgets effects in reverse order, so the observer below is removed
    // (and the map paused and stopped) before this effect destroys the map.
    DisposableEffect(mapView) {
        val memoryCallbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) mapView.onLowMemory()
            }

            @Deprecated("Deprecated in Android; still called on low memory.")
            override fun onLowMemory() = mapView.onLowMemory()

            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        }
        context.registerComponentCallbacks(memoryCallbacks)
        onDispose {
            context.unregisterComponentCallbacks(memoryCallbacks)
            mapView.onDestroy()
        }
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        // Adding the observer replays the events up to the current state.
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            val state = lifecycle.currentState
            if (state.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (state.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
        }
    }

    LaunchedEffect(mapView, styleJson) {
        mapView.getMapAsync { map -> map.setStyle(Style.Builder().fromJson(styleJson)) }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun createMapView(context: Context, savedState: Bundle?): MapView {
    MapLibre.getInstance(context)
    return MapView(context).apply { onCreate(savedState) }
}

private fun mapViewSaver(context: Context) = Saver<MapView, Bundle>(
    save = { mapView -> Bundle().also(mapView::onSaveInstanceState) },
    restore = { savedState -> createMapView(context, savedState) },
)
