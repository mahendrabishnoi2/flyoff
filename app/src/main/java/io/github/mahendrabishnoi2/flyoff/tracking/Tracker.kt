package io.github.mahendrabishnoi2.flyoff.tracking

import android.content.Context
import android.content.Intent
import android.location.Location
import io.github.mahendrabishnoi2.flyoff.flight.FlightCodes
import io.github.mahendrabishnoi2.flyoff.flight.TrackPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Why tracking stopped on its own (or couldn't start), for the UI to explain. */
enum class TrackingError {
    /** Precise location was missing or taken away. */
    PERMISSION,

    /** Android didn't allow the foreground service to start (the app wasn't in the foreground). */
    NOT_ALLOWED,

    /** The GPS provider isn't there after all. */
    NO_GPS,
}

/** What the UI shows about tracking. */
data class TrackingState(
    /** The flight being tracked, or null while tracking is off. */
    val flight: TrackedFlight? = null,
    /** The latest GPS fix the tracking service received (only meaningful while [active]). */
    val lastFix: Location? = null,
    /** False while the user has turned location off; the service keeps waiting for it. */
    val providerEnabled: Boolean = true,
    /** Whose recorded track [track] is; null when none has been loaded. */
    val trackCodes: FlightCodes? = null,
    /** The recorded (decimated) track of [trackCodes]. */
    val track: List<TrackPoint> = emptyList(),
    /** Why tracking last stopped on its own; cleared when the user dismisses it or starts again. */
    val error: TrackingError? = null,
) {
    val active: Boolean get() = flight != null
}

/**
 * Tracking state for the whole process: the [FlightTrackingService] writes it, the UI reads it.
 * After process death everything here starts from scratch (tracking off): the service is
 * `START_NOT_STICKY`, so it never comes back on its own, and the UI shows that tracking ended.
 * The track itself survives in [trackFile].
 */
object Tracker {
    private val mutableState = MutableStateFlow(TrackingState())
    val state: StateFlow<TrackingState> = mutableState.asStateFlow()

    private val visible = MutableStateFlow(false)

    /** Track file I/O: one task at a time, in the order asked, off the main thread. */
    internal val fileIo = Dispatchers.IO.limitedParallelism(1)

    /**
     * Appends belong to the process, not to the service: stopping the service must not cancel
     * a point that is still waiting to be written.
     */
    private val fileWrites = CoroutineScope(SupervisorJob() + fileIo)

    /** Whether the app's screen is visible; the service asks for fixes more often then. */
    val uiVisible: StateFlow<Boolean> = visible.asStateFlow()

    fun setUiVisible(isVisible: Boolean) {
        visible.value = isVisible
    }

    /**
     * Starts tracking [flight]. Call it only from the visible app with precise location granted:
     * that is what lets a location foreground service run with while-in-use permission. The UI's
     * latest fix [lastFix] is shown until the service's first one arrives.
     */
    fun start(context: Context, flight: TrackedFlight, lastFix: Location?) {
        mutableState.update { it.copy(error = null, lastFix = lastFix) }
        try {
            // A plain start, not startForegroundService(): the app is in the foreground, and if
            // startForeground() then fails the service can stop without the system crashing the
            // app for "did not then call startForeground()".
            context.startService(FlightTrackingService.startIntent(context, flight))
        } catch (_: IllegalStateException) {
            // Not in the foreground after all (background start restrictions).
            onStopped(TrackingError.NOT_ALLOWED)
        } catch (_: SecurityException) {
            onStopped(TrackingError.PERMISSION)
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, FlightTrackingService::class.java))
        onStopped(null)
    }

    fun clearError() {
        mutableState.update { it.copy(error = null) }
    }

    /** Reads the recorded track of [codes] from the file, unless it is loaded or being recorded. */
    suspend fun loadTrack(context: Context, codes: FlightCodes) {
        if (state.value.trackCodes == codes) return
        val points = withContext(fileIo) { TrackFile(trackFile(context)).read(codes, System.currentTimeMillis()) }
        mutableState.update { if (it.active) it else it.copy(trackCodes = codes, track = points) }
    }

    /** Deletes the recorded track: the flight was changed or cleared. Stop tracking first. */
    suspend fun forgetTrack(context: Context) {
        mutableState.update { it.copy(trackCodes = null, track = emptyList()) }
        withContext(fileIo) { TrackFile(trackFile(context)).delete() }
    }

    /** The one track file: it belongs to one flight at a time (its header says which). */
    fun trackFile(context: Context): File = File(context.filesDir, "track.log")

    // Called by FlightTrackingService on the main thread.

    /** Writes [point] to [file] soon, after anything already queued; it isn't cancelled by a stop. */
    internal fun appendLater(file: TrackFile, point: TrackPoint) {
        fileWrites.launch {
            try {
                file.append(point)
            } catch (_: IOException) {
                // Storage full: the point stays in memory and on the map.
            }
        }
    }

    internal fun onStarted(flight: TrackedFlight, track: List<TrackPoint>) {
        mutableState.update { it.copy(flight = flight, trackCodes = flight.codes, track = track, error = null) }
    }

    internal fun onFix(location: Location) {
        mutableState.update { it.copy(lastFix = location, providerEnabled = true) }
    }

    internal fun onPoint(point: TrackPoint) {
        mutableState.update { it.copy(track = it.track + point) }
    }

    internal fun onProviderEnabled(enabled: Boolean) {
        mutableState.update { it.copy(providerEnabled = enabled) }
    }

    internal fun onStopped(error: TrackingError?) {
        mutableState.update {
            it.copy(flight = null, lastFix = null, providerEnabled = true, error = error ?: it.error)
        }
    }
}
