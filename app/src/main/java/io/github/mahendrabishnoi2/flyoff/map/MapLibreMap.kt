package io.github.mahendrabishnoi2.flyoff.map

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mahendrabishnoi2.flyoff.location.showsBearing
import io.github.mahendrabishnoi2.flyoff.location.toGpsFix
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
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
private const val ROUTE_FIT_MS = 800

/** MapLibre's world is 512 dp wide at zoom 0. */
private const val WORLD_SIZE_DP = 512.0

/**
 * The bundled offline map in Compose, with the GPS dot.
 *
 * @param location the latest position to draw (a GPS fix or a simulated one), or null for none yet.
 * @param showLocation whether the location dot may be shown: precise location is granted, or
 *   the flight simulator is running.
 * @param following whether the camera follows the dot. A map gesture turns it off
 *   and reports that through [onFollowingChange].
 * @param route the flight's route to draw, or null for none.
 * @param fitRoute whether the camera should show the whole [route]. It is fitted again whenever
 *   the route, the padding or the view size changes while this stays true.
 * @param routePadding space to keep the fitted route clear of (system bars, the panel), in pixels.
 * @param onCameraMovedByUser called when a map gesture starts moving the camera.
 * @param ornamentInsets space to keep free around the map's own attribution, logo and compass.
 * @param onLoadFailed called with MapLibre's message if the bundled style can't be loaded.
 */
@Composable
fun MapLibreMap(
    location: Location?,
    showLocation: Boolean,
    following: Boolean,
    onFollowingChange: (Boolean) -> Unit,
    route: MapRoute?,
    fitRoute: Boolean,
    routePadding: MapInsets,
    onCameraMovedByUser: () -> Unit,
    ornamentInsets: MapInsets,
    onLoadFailed: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = rememberSaveable(saver = mapViewSaver(context)) {
        createMapView(context, savedState = null)
    }
    var nativeMap by remember(mapView) { mutableStateOf<NativeMap?>(null) }
    var styleLoaded by remember(mapView) { mutableStateOf(false) }
    var viewSize by remember(mapView) { mutableStateOf(IntSize.Zero) }
    val currentOnFollowingChange by rememberUpdatedState(onFollowingChange)
    val currentOnLoadFailed by rememberUpdatedState(onLoadFailed)
    val currentFollowing by rememberUpdatedState(following)
    val currentOnCameraMovedByUser by rememberUpdatedState(onCameraMovedByUser)

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

    DisposableEffect(mapView) {
        val listener = MapView.OnDidFailLoadingMapListener { message -> currentOnLoadFailed(message) }
        mapView.addOnDidFailLoadingMapListener(listener)
        onDispose { mapView.removeOnDidFailLoadingMapListener(listener) }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            nativeMap = map
            map.setMinZoomPreference(MIN_ZOOM)
            map.setMaxZoomPreference(MAX_ZOOM)
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == NativeMap.OnCameraMoveStartedListener.REASON_API_GESTURE) currentOnCameraMovedByUser()
            }
            map.setStyle(Style.Builder().fromUri(STYLE_URI)) { style ->
                // Before the location component, so the dot is drawn above the route.
                addRouteLayers(style)
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

    // One effect sets the dot and camera from the current inputs, so nothing
    // depends on effect order. Each step only changes what differs.
    LaunchedEffect(styleLoaded, showLocation, location, following) {
        val map = nativeMap ?: return@LaunchedEffect
        if (styleLoaded) syncLocation(map, showLocation, location) { currentFollowing }
    }

    LaunchedEffect(styleLoaded, route) {
        val style = nativeMap?.style ?: return@LaunchedEffect
        if (styleLoaded) showRoute(style, route)
    }

    LaunchedEffect(styleLoaded, route, fitRoute, routePadding, viewSize, density) {
        val map = nativeMap ?: return@LaunchedEffect
        if (!styleLoaded || !fitRoute || route == null) return@LaunchedEffect
        val fit = fitCamera(
            points = route.points,
            widthPx = viewSize.width,
            heightPx = viewSize.height,
            padding = routePadding,
            worldSizePx = WORLD_SIZE_DP * density.density,
            minZoom = MIN_ZOOM,
            maxZoom = MAX_ZOOM,
        ) ?: return@LaunchedEffect
        val camera = CameraPosition.Builder()
            .target(LatLng(fit.latitude, fit.longitude))
            .zoom(fit.zoom)
            .bearing(0.0)
            .tilt(0.0)
            .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(camera), ROUTE_FIT_MS)
    }

    AndroidView(factory = { mapView }, modifier = modifier.onSizeChanged { viewSize = it })
}

/**
 * Shows [location] as the dot, or hides the dot when there is no current fix (so
 * a fix from before a restart or a GPS outage is never shown as current), and
 * applies the follow mode. [following] is read last, so a "Follow me" switched
 * off by a map gesture a moment ago is not switched back on.
 *
 * MapLibre marks enabling the component as needing location permission, but that is only for
 * its own location engine, which [activateLocationComponent] leaves out: the component reads no
 * location itself and only draws what [LocationComponent.forceLocationUpdate] hands it. So the
 * flight simulator can show its dot without the permission. Real GPS fixes only ever arrive
 * with precise location granted (see rememberGpsUpdates).
 */
@SuppressLint("MissingPermission")
private fun syncLocation(
    map: NativeMap,
    showLocation: Boolean,
    location: Location?,
    following: () -> Boolean,
) {
    val component = map.locationComponent
    if (!component.isLocationComponentActivated) return
    val visible = showLocation && location != null
    if (component.isLocationComponentEnabled != visible) component.isLocationComponentEnabled = visible
    if (!visible) return

    val arrowNow = component.renderMode == RenderMode.GPS
    val arrow = location.toGpsFix().showsBearing(shownNow = arrowNow)
    if (arrow != arrowNow) component.renderMode = if (arrow) RenderMode.GPS else RenderMode.NORMAL
    component.forceLocationUpdate(location)

    val tracking = component.cameraMode != CameraMode.NONE
    if (following() && !tracking) {
        val zoom = followZoom(map.cameraPosition.zoom)
        component.setCameraMode(CameraMode.TRACKING, FOLLOW_TRANSITION_MS, zoom, null, null, null)
    } else if (!following() && tracking) {
        component.cameraMode = CameraMode.NONE
    }
}

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
    // Called when a map gesture ends tracking; mode changes we make ourselves are ignored.
    component.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
        override fun onCameraTrackingDismissed() = onTrackingDismissed()

        override fun onCameraTrackingChanged(currentMode: Int) = Unit
    })
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
