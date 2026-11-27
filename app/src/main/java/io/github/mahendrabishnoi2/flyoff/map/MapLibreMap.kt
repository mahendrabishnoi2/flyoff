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
import io.github.mahendrabishnoi2.flyoff.R
import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import io.github.mahendrabishnoi2.flyoff.location.showsBearing
import io.github.mahendrabishnoi2.flyoff.location.toGpsFix
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
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

/**
 * The fitted route follows the panel's size, but not every small change (a stat wrapping).
 * No more than the 24 dp margin FlyoffScreen fits with, so the route never slips under the panel.
 */
private val REFIT_THRESHOLD = 24.dp

/** MapLibre's world is 512 dp wide at zoom 0. */
private const val WORLD_SIZE_DP = 512.0

/**
 * The bundled offline map in Compose, with the GPS dot.
 *
 * @param location the latest position to draw (a GPS fix or a simulated one), or null for none yet.
 * @param showLocation whether the location dot may be shown: precise location is granted, or
 *   the flight simulator is running.
 * @param estimated whether [location] is a dead-reckoning estimate rather than a fix: the dot is
 *   then drawn grey (#a1b0c0, MapLibre's own stale grey).
 * @param following whether the camera follows the dot. A map gesture turns it off
 *   and reports that through [onFollowingChange].
 * @param route the flight's route to draw, or null for none.
 * @param fitRoute whether the camera should show the whole [route]. While this stays true it is
 *   fitted again when the route or the view size changes, or the padding changes a lot.
 * @param routePadding space to keep the fitted route clear of (system bars, the panel), in pixels.
 * @param onCameraMovedByUser called when a map gesture starts moving the camera.
 * @param ornamentInsets space to keep free around the map's own attribution, logo and compass.
 * @param onLoadFailed called with MapLibre's message if the bundled style can't be loaded.
 * @param track the recorded flown track, longitudes unwrapped; drawn again only when it changes.
 * @param trackHead the live fix the track's last point joins up to, or null.
 * @param styleJson the style to show instead of the bundled [STYLE_URI]: the world merged with a
 *   downloaded corridor ([mergeCorridorStyle]), or null for the bundled style alone. A change
 *   loads the new style; the route, track and dot are drawn again on it.
 * @param maxZoom how far the user may zoom in: [MAX_ZOOM], or more with a detailed corridor.
 * @param onCorridorShown called after each style load with whether it shows the corridor
 *   (true as soon as a corridor style starts loading), and with false when the map goes away.
 */
@Composable
fun MapLibreMap(
    location: Location?,
    showLocation: Boolean,
    estimated: Boolean,
    following: Boolean,
    onFollowingChange: (Boolean) -> Unit,
    route: MapRoute?,
    fitRoute: Boolean,
    routePadding: MapInsets,
    onCameraMovedByUser: () -> Unit,
    ornamentInsets: MapInsets,
    onLoadFailed: (String) -> Unit,
    track: List<LatLon>,
    trackHead: LatLon?,
    styleJson: String?,
    maxZoom: Double,
    onCorridorShown: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = rememberSaveable(saver = mapViewSaver(context)) {
        createMapView(context, savedState = null)
    }
    var nativeMap by remember(mapView) { mutableStateOf<NativeMap?>(null) }
    // The loaded style, or null while one loads. The effects below are keyed on it, not on a
    // loaded flag: a style can load within one frame, and a flag going false and back to true
    // unseen would leave the new style without the route.
    var loadedStyle by remember(mapView) { mutableStateOf<Style?>(null) }
    var dotStyles by remember(mapView) { mutableStateOf<DotStyles?>(null) }
    var viewSize by remember(mapView) { mutableStateOf(IntSize.Zero) }
    var lastFit by remember(mapView) { mutableStateOf<FitRequest?>(null) }
    val currentOnFollowingChange by rememberUpdatedState(onFollowingChange)
    val currentOnLoadFailed by rememberUpdatedState(onLoadFailed)
    val currentFollowing by rememberUpdatedState(following)
    val currentOnCameraMovedByUser by rememberUpdatedState(onCameraMovedByUser)
    val currentOnCorridorShown by rememberUpdatedState(onCorridorShown)

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
            map.setMinZoomPreference(MIN_ZOOM)
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == NativeMap.OnCameraMoveStartedListener.REASON_API_GESTURE) currentOnCameraMovedByUser()
            }
            nativeMap = map
        }
    }

    DisposableEffect(mapView) {
        onDispose { currentOnCorridorShown(false) }
    }

    LaunchedEffect(nativeMap, maxZoom) {
        nativeMap?.setMaxZoomPreference(maxZoom)
    }

    // The style, loaded again whenever it changes (a corridor downloaded, or a download starting).
    LaunchedEffect(nativeMap, styleJson) {
        val map = nativeMap ?: return@LaunchedEffect
        val corridor = styleJson != null
        // Before loading starts: a corridor style may ask for tiles at once.
        if (corridor) currentOnCorridorShown(true)
        loadedStyle = null
        val builder = if (styleJson != null) Style.Builder().fromJson(styleJson) else Style.Builder().fromUri(STYLE_URI)
        map.setStyle(builder) { style ->
            // Before the location component, so the dot is drawn above the route.
            addRouteLayers(style)
            val component = map.locationComponent
            // The component follows later style changes itself.
            if (!component.isLocationComponentActivated) {
                activateLocationComponent(context, component, style) { currentOnFollowingChange(false) }
                dotStyles = DotStyles(component.locationComponentOptions)
            }
            loadedStyle = style
            currentOnCorridorShown(corridor)
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
    LaunchedEffect(loadedStyle, showLocation, location, estimated, following) {
        val map = nativeMap ?: return@LaunchedEffect
        val styles = dotStyles ?: return@LaunchedEffect
        if (loadedStyle != null) syncLocation(map, showLocation, location, estimated, styles) { currentFollowing }
    }

    LaunchedEffect(loadedStyle, track) {
        val style = loadedStyle ?: return@LaunchedEffect
        showTrack(style, track)
    }

    LaunchedEffect(loadedStyle, track, trackHead) {
        val style = loadedStyle ?: return@LaunchedEffect
        showTrackHead(style, track.lastOrNull(), trackHead)
    }

    LaunchedEffect(loadedStyle, route) {
        val style = loadedStyle ?: return@LaunchedEffect
        showRoute(style, route)
    }

    LaunchedEffect(loadedStyle, route, fitRoute, routePadding, viewSize, density) {
        val map = nativeMap ?: return@LaunchedEffect
        if (loadedStyle == null || !fitRoute || route == null) {
            lastFit = null
            return@LaunchedEffect
        }
        val request = FitRequest(route.points, viewSize.width, viewSize.height, routePadding)
        if (!needsRefit(lastFit, request, thresholdPx = with(density) { REFIT_THRESHOLD.roundToPx() })) return@LaunchedEffect
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
        lastFit = request
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
    estimated: Boolean,
    styles: DotStyles,
    following: () -> Boolean,
) {
    val component = map.locationComponent
    if (!component.isLocationComponentActivated) return
    val visible = showLocation && location != null
    if (component.isLocationComponentEnabled != visible) component.isLocationComponentEnabled = visible
    if (!visible) return
    if (styles.estimatedShown != estimated) {
        component.applyStyle(if (estimated) styles.estimated else styles.live)
        styles.estimatedShown = estimated
    }

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
 * The dot's two looks. MapLibre's own stale state can't mark an estimate: every
 * `forceLocationUpdate` resets its stale timer, and the estimate is fed in every second.
 *
 * The estimate uses its own grey drawables (#a1b0c0, a blue-grey that reads as "not live" next
 * to MapLibre's blue #4a90e2; CI looks for both) and no accuracy circle. It must not tint
 * MapLibre's drawables instead: MapLibre tints the drawable it gets from the resource cache
 * without `mutate()`, so the grey sticks to the cached copy and the live dot stays grey after
 * the GPS is back, until that cache entry is garbage-collected.
 */
private class DotStyles(val live: LocationComponentOptions) {
    val estimated: LocationComponentOptions = live.toBuilder()
        .foregroundDrawable(R.drawable.dot_estimated)
        .gpsDrawable(R.drawable.dot_estimated_puck)
        .bearingDrawable(R.drawable.dot_estimated_bearing)
        .accuracyAlpha(0f)
        .build()
    var estimatedShown = false
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
        .maxZoomPreference(MAX_CORRIDOR_ZOOM)
    return MapView(context, options).apply { onCreate(savedState) }
}

private fun mapViewSaver(context: Context) = Saver<MapView, Bundle>(
    save = { mapView -> Bundle().also(mapView::onSaveInstanceState) },
    restore = { savedState -> createMapView(context, savedState) },
)
