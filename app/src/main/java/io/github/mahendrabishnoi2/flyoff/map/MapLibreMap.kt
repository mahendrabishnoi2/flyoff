package io.github.mahendrabishnoi2.flyoff.map

import android.Manifest
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mahendrabishnoi2.flyoff.location.showsBearing
import io.github.mahendrabishnoi2.flyoff.location.toGpsFix
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.maps.MapLibreMap as NativeMap

/** Margins in pixels that keep the map's attribution, logo and compass clear of system bars and our overlays. */
data class MapInsets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

private const val FOLLOW_TRANSITION_MS = 750L

/**
 * The bundled offline map in Compose, with the GPS dot.
 *
 * @param location the latest GPS fix to draw, or null for none yet.
 * @param showLocation whether the location dot may be shown (precise location is granted).
 * @param following whether the camera follows the dot. A map gesture turns it off
 *   and reports that through [onFollowingChange].
 * @param ornamentInsets space to keep free around the map's own attribution, logo and compass.
 */
@Composable
fun MapLibreMap(
    location: Location?,
    showLocation: Boolean,
    following: Boolean,
    onFollowingChange: (Boolean) -> Unit,
    ornamentInsets: MapInsets,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = rememberSaveable(saver = mapViewSaver(context)) {
        createMapView(context, savedState = null)
    }
    var nativeMap by remember(mapView) { mutableStateOf<NativeMap?>(null) }
    var styleLoaded by remember(mapView) { mutableStateOf(false) }
    val currentOnFollowingChange by rememberUpdatedState(onFollowingChange)

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

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            nativeMap = map
            map.setMinZoomPreference(MIN_ZOOM)
            map.setMaxZoomPreference(MAX_ZOOM)
            map.setStyle(Style.Builder().fromUri(STYLE_URI)) { style ->
                activateLocationComponent(context, map.locationComponent, style) { currentOnFollowingChange(false) }
                styleLoaded = true
            }
        }
    }

    val density = LocalDensity.current
    LaunchedEffect(nativeMap, ornamentInsets, density) {
        val ui = nativeMap?.uiSettings ?: return@LaunchedEffect
        val gap = with(density) { 4.dp.roundToPx() }
        // MapLibre's default: the attribution (i) sits 92 dp in, to the right of the logo.
        val besideLogo = with(density) { 92.dp.roundToPx() }
        with(ornamentInsets) {
            ui.setLogoMargins(left + gap, top + gap, right + gap, bottom + gap)
            ui.setAttributionMargins(left + besideLogo, top + gap, right + besideLogo, bottom + gap)
            ui.setCompassMargins(left + gap, top + gap, right + gap, bottom + gap)
        }
    }

    // Effects run in this order, so the dot is enabled before it is moved or followed.
    LaunchedEffect(styleLoaded, showLocation) {
        val component = nativeMap?.locationComponent ?: return@LaunchedEffect
        if (styleLoaded) setLocationShown(context, component, showLocation)
    }

    LaunchedEffect(styleLoaded, showLocation, location) {
        val component = nativeMap?.locationComponent ?: return@LaunchedEffect
        if (location == null || !component.isReady()) return@LaunchedEffect
        component.renderMode = if (location.toGpsFix().showsBearing()) RenderMode.GPS else RenderMode.NORMAL
        component.forceLocationUpdate(location)
    }

    LaunchedEffect(styleLoaded, showLocation, following) {
        val map = nativeMap ?: return@LaunchedEffect
        val component = map.locationComponent
        if (!component.isReady()) return@LaunchedEffect
        if (following) {
            val zoom = followZoom(map.cameraPosition.zoom)
            component.setCameraMode(CameraMode.TRACKING, FOLLOW_TRANSITION_MS, zoom, null, null, null)
        } else if (component.cameraMode != CameraMode.NONE) {
            component.cameraMode = CameraMode.NONE
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun LocationComponent.isReady() = isLocationComponentActivated && isLocationComponentEnabled

/**
 * Sets up the dot without MapLibre's own location engine: positions come from
 * [io.github.mahendrabishnoi2.flyoff.location.rememberGpsUpdates] (GPS only) through
 * [LocationComponent.forceLocationUpdate].
 */
private fun activateLocationComponent(
    context: Context,
    component: LocationComponent,
    style: Style,
    onTrackingDismissed: () -> Unit,
) {
    component.activateLocationComponent(
        LocationComponentActivationOptions.builder(context, style)
            .useDefaultLocationEngine(false)
            .build(),
    )
    component.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
        override fun onCameraTrackingDismissed() = onTrackingDismissed()

        override fun onCameraTrackingChanged(currentMode: Int) {
            if (currentMode == CameraMode.NONE) onTrackingDismissed()
        }
    })
}

private fun setLocationShown(context: Context, component: LocationComponent, show: Boolean) {
    val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    component.isLocationComponentEnabled = show && granted
}

private fun createMapView(context: Context, savedState: Bundle?): MapView {
    MapLibre.getInstance(context)
    // A restored map keeps its camera from savedState; a new one starts on the whole world.
    val options = MapLibreMapOptions.createFromAttributes(context)
        .camera(
            CameraPosition.Builder()
                .target(LatLng(START_LATITUDE, START_LONGITUDE))
                .zoom(START_ZOOM)
                .build(),
        )
        .minZoomPreference(MIN_ZOOM)
        .maxZoomPreference(MAX_ZOOM)
    return MapView(context, options).apply { onCreate(savedState) }
}

private fun mapViewSaver(context: Context) = Saver<MapView, Bundle>(
    save = { mapView -> Bundle().also(mapView::onSaveInstanceState) },
    restore = { savedState -> createMapView(context, savedState) },
)
