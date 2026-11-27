package io.github.mahendrabishnoi2.flyoff.ui

import android.content.Context
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
import io.github.mahendrabishnoi2.flyoff.R
import io.github.mahendrabishnoi2.flyoff.flight.FlightCodes
import io.github.mahendrabishnoi2.flyoff.flight.FlightPlan
import io.github.mahendrabishnoi2.flyoff.flight.FlightStats
import io.github.mahendrabishnoi2.flyoff.flight.FlightStatsCalculator
import io.github.mahendrabishnoi2.flyoff.flight.FlightStore
import io.github.mahendrabishnoi2.flyoff.flight.SimulationTimeline
import io.github.mahendrabishnoi2.flyoff.flight.simulatorPosition
import io.github.mahendrabishnoi2.flyoff.location.GpsState
import io.github.mahendrabishnoi2.flyoff.location.LocationAccess
import io.github.mahendrabishnoi2.flyoff.location.LocationPermission
import io.github.mahendrabishnoi2.flyoff.location.openLocationSettings
import io.github.mahendrabishnoi2.flyoff.location.rememberGpsUpdates
import io.github.mahendrabishnoi2.flyoff.location.rememberLocationPermission
import io.github.mahendrabishnoi2.flyoff.location.summary
import io.github.mahendrabishnoi2.flyoff.location.toGpsFix
import io.github.mahendrabishnoi2.flyoff.map.MapInsets
import io.github.mahendrabishnoi2.flyoff.map.MapLibreMap
import io.github.mahendrabishnoi2.flyoff.map.MapRoute
import io.github.mahendrabishnoi2.flyoff.map.PanelBounds
import io.github.mahendrabishnoi2.flyoff.map.FreeSide
import io.github.mahendrabishnoi2.flyoff.map.freeSide
import io.github.mahendrabishnoi2.flyoff.map.ornamentInsets
import io.github.mahendrabishnoi2.flyoff.map.routePadding
import kotlin.math.roundToInt

/**
 * The one screen: the offline map, with a panel at the bottom for location status, the flight
 * and its stats, and the flight simulator. Flight setup opens over it.
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
    val simulatedPosition = if (plan != null && activeSimulation != null) {
        simulatorPosition(
            flight = plan.simulatedFlight,
            timeline = activeSimulation.timeline,
            takeoffMillis = activeSimulation.takeoffMillis,
            clockMillis = simulatorNow,
            wallMillis = System.currentTimeMillis(),
        )
    } else {
        null
    }

    // Real GPS is switched off while simulating, so a real fix can never mix with simulated ones.
    val gps = rememberGpsUpdates(active = precise && !simulating)
    val position = simulatedPosition ?: gps.location?.toPosition()
    val mapLocation = remember(simulatedPosition, gps.location) { simulatedPosition?.toLocation() ?: gps.location }
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

    fun setFlight(codes: FlightCodes?) {
        if (codes != flightCodes) {
            simulation = null
            cameraMovedByUser = false
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
            following = following && mapLocation != null,
            onFollowingChange = { following = it },
            route = route,
            fitRoute = route != null && !following && !cameraMovedByUser,
            routePadding = fitPadding,
            onCameraMovedByUser = { cameraMovedByUser = true },
            ornamentInsets = ornaments,
            onLoadFailed = { mapError = it },
            modifier = Modifier.fillMaxSize(),
        )
        // Not composed under the setup screen, so screen readers don't find it there.
        if (!setupOpen) Box(
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
                    val locale = LocalConfiguration.current.locales[0]
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
                    )
                },
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
                modifier = Modifier
                    .widthIn(max = if (landscape) 480.dp else 560.dp)
                    .fillMaxWidth()
                    .heightIn(max = panelMaxHeight)
                    .onGloballyPositioned { coordinates ->
                        val r = coordinates.boundsInRoot()
                        panel = PanelBounds(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
                    }
                    // Anchored at the bottom: if the panel must scroll, the flight card stays in view.
                    .verticalScroll(rememberScrollState(), reverseScrolling = true),
            )
        }
        // After the panel, so the panel can never draw over it; placed in the free map area.
        if (simulating && !setupOpen) {
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (plan == null && !flightPending) {
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
                onChange = onSetUpFlight,
                onSimulate = onSimulate,
                onSimulatorPlayPause = onSimulatorPlayPause,
                onSimulatorSpeed = onSimulatorSpeed,
                onSimulatorStop = onSimulatorStop,
            )
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
            if (action != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    if (secondaryAction != null) {
                        TextButton(onClick = onSecondaryAction) { Text(secondaryAction) }
                    }
                    Button(onClick = onAction) { Text(action) }
                }
            }
        }
    }
}
