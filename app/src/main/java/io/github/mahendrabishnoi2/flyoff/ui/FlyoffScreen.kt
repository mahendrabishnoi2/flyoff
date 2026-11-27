package io.github.mahendrabishnoi2.flyoff.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mahendrabishnoi2.flyoff.R
import io.github.mahendrabishnoi2.flyoff.download.CorridorPlan
import io.github.mahendrabishnoi2.flyoff.download.DownloadKind
import io.github.mahendrabishnoi2.flyoff.download.DownloadRequest
import io.github.mahendrabishnoi2.flyoff.download.RouteMaps
import io.github.mahendrabishnoi2.flyoff.flight.DeadReckoning
import io.github.mahendrabishnoi2.flyoff.flight.FlightCodes
import io.github.mahendrabishnoi2.flyoff.flight.FlightPlan
import io.github.mahendrabishnoi2.flyoff.flight.FlightStats
import io.github.mahendrabishnoi2.flyoff.flight.FlightStatsCalculator
import io.github.mahendrabishnoi2.flyoff.flight.FlightStore
import io.github.mahendrabishnoi2.flyoff.flight.SimulationTimeline
import io.github.mahendrabishnoi2.flyoff.flight.simulatorFix
import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import io.github.mahendrabishnoi2.flyoff.geo.unwrapLongitudes
import io.github.mahendrabishnoi2.flyoff.location.GpsState
import io.github.mahendrabishnoi2.flyoff.location.LocationAccess
import io.github.mahendrabishnoi2.flyoff.location.LocationPermission
import io.github.mahendrabishnoi2.flyoff.location.gpsReadiness
import io.github.mahendrabishnoi2.flyoff.location.openBatterySaverSettings
import io.github.mahendrabishnoi2.flyoff.location.openLocationSettings
import io.github.mahendrabishnoi2.flyoff.location.rememberBatterySaverLimitsGps
import io.github.mahendrabishnoi2.flyoff.location.rememberGnssSatellites
import io.github.mahendrabishnoi2.flyoff.location.rememberGpsUpdates
import io.github.mahendrabishnoi2.flyoff.location.rememberLocationPermission
import io.github.mahendrabishnoi2.flyoff.location.summary
import io.github.mahendrabishnoi2.flyoff.location.text
import io.github.mahendrabishnoi2.flyoff.location.toGpsFix
import io.github.mahendrabishnoi2.flyoff.map.MAX_CORRIDOR_ZOOM
import io.github.mahendrabishnoi2.flyoff.map.MAX_ZOOM
import io.github.mahendrabishnoi2.flyoff.map.MapInsets
import io.github.mahendrabishnoi2.flyoff.map.MapLibreMap
import io.github.mahendrabishnoi2.flyoff.map.MapRoute
import io.github.mahendrabishnoi2.flyoff.map.PanelBounds
import io.github.mahendrabishnoi2.flyoff.map.FreeSide
import io.github.mahendrabishnoi2.flyoff.map.freeSide
import io.github.mahendrabishnoi2.flyoff.map.ornamentInsets
import io.github.mahendrabishnoi2.flyoff.map.routePadding
import io.github.mahendrabishnoi2.flyoff.tracking.TrackedFlight
import io.github.mahendrabishnoi2.flyoff.tracking.Tracker
import io.github.mahendrabishnoi2.flyoff.tracking.TrackingError
import io.github.mahendrabishnoi2.flyoff.tracking.estimatedPositionLine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The one screen: the offline map, with a panel at the bottom for location status, the flight
 * and its stats, tracking, and the flight simulator. Flight setup opens over it.
 */
@Composable
fun FlyoffScreen() {
    val context = LocalContext.current
    val permission = rememberLocationPermission()
    val precise = permission.access == LocationAccess.PRECISE

    // The flight: stored as two IATA codes, resolved once the airport list has loaded.
    val airports = rememberAirports()
    val store = remember(context) { FlightStore(context) }
    var flightCodes by remember { mutableStateOf(store.load()) }
    val search = (airports.state as? AirportsState.Ready)?.search
    val plan = remember(flightCodes, search) { search?.let { s -> flightCodes?.resolve(s::byIata) } }
    var setupOpen by rememberSaveable { mutableStateOf(false) }
    var storedMapsOpen by rememberSaveable { mutableStateOf(false) }

    // Route maps: what is stored (read once at start, which also deletes expired maps), the
    // plans for this flight, and the downloaded corridor on the map.
    val routeMaps by RouteMaps.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { RouteMaps.refresh(context) }
    val flightMaps = plan?.let { routeMaps.flight(it.origin.iata, it.destination.iata) }
    val routePlan = rememberCorridorPlan(plan, DownloadKind.ROUTE)
    val routeReady = flightMaps?.isReady(DownloadKind.ROUTE) == true
    val detailedPlan = rememberCorridorPlan(plan, DownloadKind.DETAILED, enabled = routeReady)
    val corridor = rememberCorridorStyle(flightMaps, downloading = routeMaps.running != null)

    // The flight simulator. It belongs to one flight, and is ignored for any other.
    var simulation by rememberSaveable(stateSaver = SimulationStateSaver) { mutableStateOf<SimulationState?>(null) }
    val activeSimulation = simulation?.takeIf { plan != null && it.codes == plan.codes }
    // After process death the saved flight (and any replay) waits for the airport list; until
    // then it counts as set, so real GPS doesn't start and "Set up flight" doesn't flash.
    val flightPending = plan == null && flightCodes != null && airports.state is AirportsState.Loading
    val simulating = activeSimulation != null || (flightPending && simulation?.codes == flightCodes)
    val simulatorNow = rememberSimulationClock(activeSimulation)
    LaunchedEffect(activeSimulation, simulatorNow) {
        val sim = activeSimulation ?: return@LaunchedEffect
        if (sim.timeline.running && sim.timeline.isFinished(simulatorNow)) {
            simulation = sim.copy(timeline = sim.timeline.paused(simulatorNow))
        }
    }
    val simulated = if (plan != null && activeSimulation != null) {
        simulatorFix(
            flight = plan.simulatedFlight,
            timeline = activeSimulation.timeline,
            takeoffMillis = activeSimulation.takeoffMillis,
            clockMillis = simulatorNow,
            wallMillis = System.currentTimeMillis(),
            gaps = activeSimulation.gaps,
        )
    } else {
        null
    }

    // Tracking: while the service runs it owns the GPS and the screen shows its fixes; otherwise
    // the screen listens itself, only while visible. The service asks for fixes more often while
    // the screen is visible.
    val tracking by Tracker.state.collectAsStateWithLifecycle()
    // Did Android end tracking (kill the app) while it was recording? Then offer to resume.
    LaunchedEffect(Unit) { Tracker.restore(context) }
    LifecycleStartEffect(Unit) {
        Tracker.setUiVisible(true)
        onStopOrDispose { Tracker.setUiVisible(false) }
    }
    val trackingActive = tracking.active
    // Real GPS is switched off while simulating, so a real fix can never mix with simulated ones.
    val ownGps = rememberGpsUpdates(active = precise && !simulating && !trackingActive)
    val gps = if (trackingActive) GpsState(ownGps.hasReceiver, tracking.providerEnabled, tracking.lastFix) else ownGps

    // The last fix (simulated or real) and its age. With a flight set, a fix older than 10 s
    // gives way to a dead-reckoning estimate toward the destination, moving every second.
    val realFixAge = rememberFixAgeMillis(gps.location, enabled = plan != null && simulated == null) { fix, age ->
        plan != null && DeadReckoning.estimate(plan.destinationLatLon, fix.toPosition(), age) != null
    }
    val lastFix = simulated?.position ?: gps.location?.toPosition()
    val millisSinceFix = simulated?.millisSinceFix ?: realFixAge
    val estimate = if (plan != null && lastFix != null && DeadReckoning.isStale(millisSinceFix)) {
        DeadReckoning.estimate(plan.destinationLatLon, lastFix, millisSinceFix)
    } else {
        null
    }
    val position = estimate ?: lastFix
    val mapLocation = remember(estimate, simulated?.position, gps.location) {
        estimate?.toLocation() ?: simulated?.position?.toLocation() ?: gps.location
    }
    val stats = if (plan != null && position != null) {
        FlightStatsCalculator.compute(plan.originLatLon, plan.destinationLatLon, position)
    } else {
        null
    }

    var following by rememberSaveable { mutableStateOf(false) }
    // The camera shows the whole route until the user moves the map.
    var cameraMovedByUser by rememberSaveable { mutableStateOf(false) }
    var mapError by remember { mutableStateOf<String?>(null) }
    val route = remember(plan) {
        plan?.let { MapRoute(it.routePoints, it.origin.iata, it.destination.iata) }
    }

    // "GPS ready" before take-off: satellites are listened to only while this line shows (an
    // estimate takes its place).
    val readinessShown = plan != null && !simulating && precise && gps.hasReceiver && gps.providerEnabled && estimate == null
    val satellites = rememberGnssSatellites(active = readinessShown)
    val locale = LocalConfiguration.current.locales[0]
    val statusLine = when {
        estimate != null -> StatusLine(estimatedPositionLine(millisSinceFix), estimated = true)
        readinessShown -> {
            val fix = gps.location
            val accuracy = if (fix != null && fix.hasAccuracy()) fix.accuracy else null
            StatusLine(gpsReadiness(fix?.let { realFixAge }, accuracy, satellites).text(locale), estimated = false)
        }
        else -> null
    }

    // The recorded track of this flight, from the file after a restart; not under a simulation.
    val scope = rememberCoroutineScope()
    LaunchedEffect(plan?.codes, trackingActive) {
        val codes = plan?.codes ?: return@LaunchedEffect
        if (!trackingActive) Tracker.loadTrack(context, codes)
    }
    val trackPoints = if (plan != null && !simulating && tracking.trackCodes == plan.codes) tracking.track else emptyList()
    val trackLine = remember(trackPoints) { unwrapLongitudes(trackPoints.map { it.latLon }) }
    val trackHead = gps.location?.takeIf { trackingActive && trackLine.isNotEmpty() }?.let {
        unwrapLongitudes(listOf(trackLine.last(), LatLon(it.latitude, it.longitude)))[1]
    }

    // Start tracking; on Android 13+ ask for notifications first (tracking runs either way).
    val notificationsEnabled = rememberNotificationsEnabled()
    val currentPlan by rememberUpdatedState(plan)
    val currentFix by rememberUpdatedState(gps.location)
    val startTracking = {
        currentPlan?.let { p ->
            Tracker.start(context, TrackedFlight(p.codes, p.originLatLon, p.destinationLatLon, p.destination.timeZone), currentFix)
        }
        Unit
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        startTracking()
    }
    val batterySaverLimitsGps = rememberBatterySaverLimitsGps()

    fun setFlight(codes: FlightCodes?) {
        if (codes != flightCodes) {
            // A download belongs to the flight it was started for.
            if (routeMaps.running != null) RouteMaps.cancel()
            simulation = null
            cameraMovedByUser = false
            // The track belongs to one flight: a new or cleared flight stops it (or forgets an
            // interrupted one) and starts afresh.
            Tracker.stop(context)
            scope.launch { Tracker.forgetTrack(context) }
        }
        if (codes == null) store.clear() else store.save(codes)
        flightCodes = codes
        setupOpen = false
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        // In portrait the panel may take up to 65 % of the height (it scrolls beyond); in landscape it sits at one side.
        val panelMaxHeight = if (landscape) maxHeight else maxHeight * 0.65f
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        val safeDrawing = WindowInsets.safeDrawing
        val safe = MapInsets(
            left = safeDrawing.getLeft(density, LayoutDirection.Ltr),
            top = safeDrawing.getTop(density),
            right = safeDrawing.getRight(density, LayoutDirection.Ltr),
            bottom = safeDrawing.getBottom(density),
        )
        var panel by remember { mutableStateOf<PanelBounds?>(null) }
        // Keep MapLibre's attribution, logo and compass clear of the system bars and of the panel.
        val ornaments = ornamentInsets(widthPx, heightPx, panel, safe)
        val fitPadding = routePadding(widthPx, heightPx, panel, safe, margin = with(density) { 24.dp.roundToPx() })

        MapLibreMap(
            location = mapLocation,
            showLocation = precise || activeSimulation != null,
            estimated = estimate != null,
            following = following && mapLocation != null,
            onFollowingChange = { following = it },
            route = route,
            fitRoute = route != null && !following && !cameraMovedByUser,
            routePadding = fitPadding,
            onCameraMovedByUser = { cameraMovedByUser = true },
            ornamentInsets = ornaments,
            onLoadFailed = { mapError = it },
            track = trackLine,
            trackHead = trackHead,
            styleJson = corridor?.styleJson,
            maxZoom = if (corridor != null && corridor.maxZoom > MAX_ZOOM) MAX_CORRIDOR_ZOOM else MAX_ZOOM,
            onCorridorShown = RouteMaps::setCorridorShown,
            modifier = Modifier.fillMaxSize(),
        )
        // Not composed under the setup screen, so screen readers don't find it there.
        if (!setupOpen && !storedMapsOpen) Box(
            modifier = Modifier
                .align(if (landscape) Alignment.BottomStart else Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
        ) {
            StatusPanel(
                permission = permission,
                gps = gps,
                mapError = mapError,
                airports = airports,
                plan = plan,
                stats = stats,
                eta = if (plan != null && stats != null) {
                    formatEtaOrUtc(stats.etaMillis, position?.timeMillis ?: 0L, plan.destination.timeZone, locale)
                } else {
                    null
                },
                simulator = activeSimulation?.let {
                    SimulatorControls(
                        speed = it.timeline.speed,
                        running = it.timeline.running,
                        atStart = it.takeoffMillis == null,
                        finished = it.timeline.isFinished(simulatorNow),
                        gaps = it.gaps,
                    )
                },
                statusLine = statusLine,
                tracking = TrackingControls(
                    canStart = plan != null && precise && gps.hasReceiver && !simulating,
                    active = trackingActive,
                    points = tracking.track.size,
                    notificationsOff = !notificationsEnabled,
                ),
                trackingError = tracking.error,
                trackingInterrupted = !trackingActive && plan != null && tracking.interrupted == plan.codes,
                onDismissInterrupted = { Tracker.dismissInterrupted(context) },
                batterySaverLimitsGps = batterySaverLimitsGps && !simulating,
                onStartTracking = {
                    val needsAsking = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    if (needsAsking) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else startTracking()
                },
                onStopTracking = { Tracker.stop(context) },
                onDismissTrackingError = Tracker::clearError,
                simulating = simulating,
                flightPending = flightPending,
                showFollow = mapLocation != null,
                following = following,
                onFollowingChange = { following = it },
                onSetUpFlight = { setupOpen = true },
                onSimulate = {
                    val current = plan ?: return@StatusPanel
                    val now = simulatorClockMillis()
                    simulation = SimulationState(
                        codes = current.codes,
                        timeline = SimulationTimeline.ready(
                            current.simulatedFlight.durationSeconds,
                            SimulationTimeline.DEFAULT_SPEED,
                            now,
                        ),
                        // Stamped on the first Start: waiting before it mustn't skew the ETA.
                        takeoffMillis = null,
                    )
                },
                onSimulatorPlayPause = {
                    val sim = activeSimulation ?: return@StatusPanel
                    val now = simulatorClockMillis()
                    simulation = if (sim.timeline.running) {
                        sim.copy(timeline = sim.timeline.paused(now))
                    } else {
                        val takeOff = sim.takeoffMillis == null || sim.timeline.isFinished(now)
                        sim.copy(
                            timeline = sim.timeline.started(now),
                            // The first Start and a replay take off now; resuming keeps the take-off time.
                            takeoffMillis = if (takeOff) System.currentTimeMillis() else sim.takeoffMillis,
                        )
                    }
                },
                onSimulatorSpeed = { speed ->
                    val sim = activeSimulation ?: return@StatusPanel
                    simulation = sim.copy(timeline = sim.timeline.withSpeed(speed, simulatorClockMillis()))
                },
                onSimulatorStop = { simulation = null },
                onSimulatorGaps = { on ->
                    val sim = activeSimulation ?: return@StatusPanel
                    simulation = sim.copy(gaps = on)
                },
                routeMap = RouteMapControls(
                    routePlan = routePlan,
                    detailedPlan = detailedPlan,
                    maps = flightMaps,
                    running = routeMaps.running?.takeIf { plan != null && it.origin == plan.origin.iata && it.destination == plan.destination.iata },
                    otherRunning = routeMaps.running != null && (plan == null || routeMaps.running?.origin != plan.origin.iata || routeMaps.running?.destination != plan.destination.iata),
                    problem = plan?.let { routeMaps.problemFor(it.origin.iata, it.destination.iata) },
                    mobileDataAllowed = routeMaps.mobileDataAllowed,
                    compact = trackingActive,
                ),
                showStoredMaps = routeMaps.flights.isNotEmpty(),
                onDownloadMap = { corridorPlan, index ->
                    val current = plan ?: return@StatusPanel
                    RouteMaps.start(
                        context,
                        DownloadRequest(current.origin.iata, current.destination.iata, corridorPlan.kind, corridorPlan.chunks[index], corridorPlan.chunks.size),
                    )
                },
                onCancelDownload = RouteMaps::cancel,
                onReplaceMaps = {
                    val current = plan ?: return@StatusPanel
                    RouteMaps.clearProblem()
                    RouteMaps.deleteFlight(context, current.origin.iata, current.destination.iata)
                },
                onOpenStoredMaps = { storedMapsOpen = true },
                modifier = Modifier
                    .widthIn(max = if (landscape) 480.dp else 560.dp)
                    .fillMaxWidth()
                    .heightIn(max = panelMaxHeight)
                    .onGloballyPositioned { coordinates ->
                        val r = coordinates.boundsInRoot()
                        panel = PanelBounds(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
                    },
            )
        }
        // After the panel, so the panel can never draw over it; placed in the free map area.
        if (simulating && !setupOpen && !storedMapsOpen) {
            val freeSide = panel?.let { freeSide(widthPx, heightPx, it) } ?: FreeSide.ABOVE
            val toDp = { px: Int -> with(density) { px.toDp() } }
            SimulatedBadge(
                Modifier
                    .align(
                        when (freeSide) {
                            FreeSide.ABOVE -> Alignment.TopCenter
                            // Absolute, like FreeSide: right of the panel even in right-to-left layouts.
                            FreeSide.RIGHT -> AbsoluteAlignment.TopRight
                            FreeSide.LEFT -> AbsoluteAlignment.TopLeft
                        },
                    )
                    .absolutePadding(
                        left = if (freeSide == FreeSide.RIGHT) toDp(panel?.right ?: 0) else 0.dp,
                        right = if (freeSide == FreeSide.LEFT) toDp(widthPx - (panel?.left ?: widthPx)) else 0.dp,
                    )
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 8.dp, start = 16.dp, end = 16.dp),
            )
        }
        if (storedMapsOpen) {
            StoredMapsScreen(
                state = routeMaps,
                onMobileDataAllowed = { RouteMaps.setMobileDataAllowed(context, it) },
                onDelete = { RouteMaps.deleteFlight(context, it.origin, it.destination) },
                onClose = { storedMapsOpen = false },
            )
        }
        if (setupOpen) {
            FlightSetupScreen(
                airports = airports.state,
                onRetry = airports.retry,
                current = flightCodes,
                onDone = { setFlight(it) },
                onClear = { setFlight(null) },
                onClose = { setupOpen = false },
            )
        }
    }
}

/** Says, over the map, that the dot is not the real position. */
@Composable
private fun SimulatedBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiary,
        contentColor = MaterialTheme.colorScheme.onTertiary,
        shape = MaterialTheme.shapes.small,
        shadowElevation = 2.dp,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text = stringResource(R.string.simulated_badge),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun StatusPanel(
    permission: LocationPermission,
    gps: GpsState,
    mapError: String?,
    airports: Airports,
    plan: FlightPlan?,
    stats: FlightStats?,
    eta: EtaText?,
    simulator: SimulatorControls?,
    statusLine: StatusLine?,
    tracking: TrackingControls,
    trackingError: TrackingError?,
    trackingInterrupted: Boolean,
    onDismissInterrupted: () -> Unit,
    batterySaverLimitsGps: Boolean,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onDismissTrackingError: () -> Unit,
    simulating: Boolean,
    flightPending: Boolean,
    showFollow: Boolean,
    following: Boolean,
    onFollowingChange: (Boolean) -> Unit,
    onSetUpFlight: () -> Unit,
    onSimulate: () -> Unit,
    onSimulatorPlayPause: () -> Unit,
    onSimulatorSpeed: (Int) -> Unit,
    onSimulatorStop: () -> Unit,
    onSimulatorGaps: (Boolean) -> Unit,
    routeMap: RouteMapControls,
    showStoredMaps: Boolean,
    onDownloadMap: (CorridorPlan, Int) -> Unit,
    onCancelDownload: () -> Unit,
    onReplaceMaps: () -> Unit,
    onOpenStoredMaps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
        // The buttons stay put; if the panel is taller than the screen (landscape), only the
        // cards below them scroll, anchored at the bottom so the flight card stays in view.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (plan == null && !flightPending) {
                if (showStoredMaps) TextButton(onClick = onOpenStoredMaps) { Text(stringResource(R.string.stored_maps)) }
                FilledTonalButton(onClick = onSetUpFlight) { Text(stringResource(R.string.set_up_flight)) }
            }
            if (showFollow) {
                ElevatedFilterChip(
                    selected = following,
                    onClick = { onFollowingChange(!following) },
                    label = { Text(stringResource(R.string.follow_me)) },
                )
            }
        }
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState(), reverseScrolling = true),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (mapError != null) {
                MessageCard(
                    title = stringResource(R.string.map_error_title),
                    body = stringResource(R.string.map_error_body, mapError),
                )
            }
            val airportState = airports.state
            if (airportState is AirportsState.Failed) {
                MessageCard(
                    title = stringResource(R.string.airports_error_title),
                    body = stringResource(R.string.airports_error_body, airportState.message),
                    action = stringResource(R.string.retry),
                    onAction = airports.retry,
                )
            }
            if (trackingError != null) {
                MessageCard(
                    title = stringResource(R.string.tracking_stopped_title),
                    body = stringResource(
                        when (trackingError) {
                            TrackingError.PERMISSION -> R.string.tracking_stopped_permission
                            TrackingError.NOT_ALLOWED -> R.string.tracking_stopped_not_allowed
                            TrackingError.NO_GPS -> R.string.tracking_stopped_no_gps
                        },
                    ),
                    action = stringResource(R.string.ok),
                    onAction = onDismissTrackingError,
                )
            }
            if (trackingInterrupted) {
                MessageCard(
                    title = stringResource(R.string.tracking_stopped_title),
                    body = stringResource(R.string.tracking_interrupted_body),
                    action = stringResource(R.string.resume_tracking).takeIf { tracking.canStart },
                    onAction = onStartTracking,
                    secondaryAction = stringResource(R.string.dismiss),
                    onSecondaryAction = onDismissInterrupted,
                )
            }
            if (batterySaverLimitsGps) {
                MessageCard(
                    title = stringResource(R.string.battery_saver_title),
                    body = stringResource(R.string.battery_saver_body),
                    action = stringResource(R.string.battery_saver_settings),
                    onAction = { openBatterySaverSettings(context) },
                )
            }
            // While simulating, the real GPS is off and its state doesn't matter.
            if (!simulating) {
                GpsStatus(permission, gps, showFix = plan == null, context = context)
            }
            if (plan != null) {
                FlightCard(
                    plan = plan,
                    stats = stats,
                    eta = eta,
                    simulator = simulator,
                    statusLine = statusLine,
                    tracking = tracking,
                    onStartTracking = onStartTracking,
                    onStopTracking = onStopTracking,
                    onChange = onSetUpFlight,
                    onSimulate = onSimulate,
                    onSimulatorPlayPause = onSimulatorPlayPause,
                    onSimulatorSpeed = onSimulatorSpeed,
                    onSimulatorStop = onSimulatorStop,
                    onSimulatorGaps = onSimulatorGaps,
                    routeMap = routeMap,
                    onDownloadMap = onDownloadMap,
                    onCancelDownload = onCancelDownload,
                    onReplaceMaps = onReplaceMaps,
                    onOpenStoredMaps = onOpenStoredMaps,
                )
            }
        }
    }
}

/**
 * The location permission and GPS state. With a flight set, a fix shows in the flight's stats
 * instead ([showFix] false).
 */
@Composable
private fun GpsStatus(permission: LocationPermission, gps: GpsState, showFix: Boolean, context: Context) {
    val location = gps.location
    if (!gps.hasReceiver) {
        MessageCard(
            title = stringResource(R.string.no_gps_title),
            body = stringResource(R.string.no_gps_body),
        )
        return
    }
    when (permission.access) {
        LocationAccess.PRECISE -> when {
            !gps.providerEnabled -> MessageCard(
                title = stringResource(R.string.gps_off_title),
                body = stringResource(R.string.gps_off_body),
                action = stringResource(R.string.open_location_settings),
                onAction = { openLocationSettings(context) },
            )
            location == null -> MessageCard(
                title = stringResource(R.string.gps_waiting_title),
                body = stringResource(R.string.gps_waiting_body),
            )
            showFix -> {
                val locale = LocalConfiguration.current.locales[0]
                MessageCard(
                    title = stringResource(R.string.gps_fix_title),
                    body = location.toGpsFix().summary(locale),
                )
            }
        }
        LocationAccess.NOT_ASKED -> MessageCard(
            title = stringResource(R.string.permission_title),
            body = stringResource(R.string.permission_body),
            action = stringResource(R.string.permission_allow),
            onAction = permission.request,
        )
        // The system may or may not show its dialog again, so offer settings too.
        LocationAccess.DENIED -> MessageCard(
            title = stringResource(R.string.permission_title),
            body = stringResource(R.string.permission_body),
            action = stringResource(R.string.permission_allow),
            onAction = permission.request,
            secondaryAction = stringResource(R.string.open_app_settings),
            onSecondaryAction = permission.openAppSettings,
        )
        LocationAccess.BLOCKED -> MessageCard(
            title = stringResource(R.string.permission_blocked_title),
            body = stringResource(R.string.permission_blocked_body),
            action = stringResource(R.string.open_app_settings),
            onAction = permission.openAppSettings,
            secondaryAction = stringResource(R.string.ask_again),
            onSecondaryAction = permission.request,
        )
        LocationAccess.APPROXIMATE -> MessageCard(
            title = stringResource(R.string.approximate_title),
            body = stringResource(R.string.approximate_body),
            action = stringResource(R.string.approximate_allow),
            onAction = permission.request,
            secondaryAction = stringResource(R.string.open_app_settings),
            onSecondaryAction = permission.openAppSettings,
        )
        LocationAccess.APPROXIMATE_BLOCKED -> MessageCard(
            title = stringResource(R.string.approximate_title),
            body = stringResource(R.string.approximate_body),
            action = stringResource(R.string.open_app_settings),
            onAction = permission.openAppSettings,
            secondaryAction = stringResource(R.string.ask_again),
            onSecondaryAction = permission.request,
        )
    }
}

@Composable
private fun MessageCard(
    title: String,
    body: String,
    action: String? = null,
    onAction: () -> Unit = {},
    secondaryAction: String? = null,
    onSecondaryAction: () -> Unit = {},
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
            if (action != null || secondaryAction != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    if (secondaryAction != null) {
                        TextButton(onClick = onSecondaryAction) { Text(secondaryAction) }
                    }
                    if (action != null) Button(onClick = onAction) { Text(action) }
                }
            }
        }
    }
}
