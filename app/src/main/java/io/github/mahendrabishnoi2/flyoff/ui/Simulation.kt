package io.github.mahendrabishnoi2.flyoff.ui

import android.location.Location
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.github.mahendrabishnoi2.flyoff.flight.FlightCodes
import io.github.mahendrabishnoi2.flyoff.flight.Position
import io.github.mahendrabishnoi2.flyoff.flight.SimulationTimeline
import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import kotlinx.coroutines.delay

/**
 * A flight-simulator replay: which flight, its [timeline], and the wall-clock time of its
 * take-off ([takeoffMillis], so simulated fixes carry plausible times and the ETA is the
 * simulated flight's arrival).
 */
data class SimulationState(val codes: FlightCodes, val timeline: SimulationTimeline, val takeoffMillis: Long)

/**
 * Saves a replay across rotation and process death as plain values. The timeline's clock is
 * [SystemClock.elapsedRealtime], which keeps counting while the app is away, so a restored
 * replay carries on where it would have been.
 */
val SimulationStateSaver: Saver<SimulationState?, Any> = Saver(
    save = { state ->
        state?.let {
            arrayListOf<Any>(
                it.codes.origin, it.codes.destination, it.takeoffMillis,
                it.timeline.durationSeconds, it.timeline.speed, it.timeline.running,
                it.timeline.elapsedAtAnchor, it.timeline.anchorMillis,
            )
        }
    },
    restore = { saved ->
        val v = saved as List<*>
        runCatching {
            SimulationState(
                codes = FlightCodes(v[0] as String, v[1] as String),
                takeoffMillis = v[2] as Long,
                timeline = SimulationTimeline(
                    durationSeconds = v[3] as Double,
                    speed = v[4] as Int,
                    running = v[5] as Boolean,
                    elapsedAtAnchor = v[6] as Double,
                    anchorMillis = v[7] as Long,
                ),
            )
        }.getOrNull()
    },
)

/** How often a running replay moves the dot and the stats: about as often as a GPS fix. */
private const val TICK_MS = 1_000L

/** The monotonic clock the simulator runs on. */
fun simulatorClockMillis(): Long = SystemClock.elapsedRealtime()

/**
 * The simulator clock's current reading, updated every [TICK_MS] while [state] is running and
 * the app is in the foreground. In the background nothing ticks; the replay is worked out from
 * the clock again on return.
 */
@Composable
fun rememberSimulationClock(state: SimulationState?): Long {
    var now by remember { mutableLongStateOf(simulatorClockMillis()) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val ticking = state?.timeline?.running == true && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(state, ticking) {
        now = simulatorClockMillis()
        while (ticking) {
            delay(TICK_MS)
            now = simulatorClockMillis()
        }
    }
    return now
}

/** Marks fixes from the simulator; nothing outside the app ever sees them. */
private const val SIMULATOR_PROVIDER = "flyoff-simulator"

/** A simulated position as a [Location] for the map's location component. */
fun Position.toLocation(): Location = Location(SIMULATOR_PROVIDER).also { location ->
    location.latitude = latLon.latitude
    location.longitude = latLon.longitude
    location.time = timeMillis
    location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    altitudeMeters?.let { location.altitude = it }
    speedMetersPerSecond?.let { location.speed = it.toFloat() }
    bearingDegrees?.let { location.bearing = it.toFloat() }
}

/** A GPS fix as a [Position] for the flight stats; values the receiver didn't report stay null. */
fun Location.toPosition(): Position = Position(
    latLon = LatLon(latitude, longitude),
    altitudeMeters = if (hasAltitude()) altitude else null,
    speedMetersPerSecond = if (hasSpeed()) speed.toDouble() else null,
    bearingDegrees = if (hasBearing()) bearing.toDouble() else null,
    timeMillis = time,
)
