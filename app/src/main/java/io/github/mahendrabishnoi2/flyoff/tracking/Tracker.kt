package io.github.mahendrabishnoi2.flyoff.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import io.github.mahendrabishnoi2.flyoff.airports.AirportCatalog
import io.github.mahendrabishnoi2.flyoff.airports.AirportSearch
import io.github.mahendrabishnoi2.flyoff.flight.FlightCodes
import io.github.mahendrabishnoi2.flyoff.flight.FlightRecord
import io.github.mahendrabishnoi2.flyoff.flight.TrackPoint
import io.github.mahendrabishnoi2.flyoff.geo.LatLon
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
    /**
     * The flight whose tracking ended without the user stopping it: Android killed the app (or
     * destroyed the service) while it was recording. The UI offers to resume it.
     */
    val interrupted: FlightCodes? = null,
) {
    val active: Boolean get() = flight != null
}

/**
 * Tracking state for the whole process: the [FlightTrackingService] writes it, the UI reads it.
 * After process death everything here starts from scratch (tracking off): the service is
 * `START_NOT_STICKY`, so it never comes back on its own. The track itself survives in
 * [trackFile], and a "tracking wanted" mark in preferences, set while tracking runs and cleared
 * by every stop the app asks for, lets the next launch see that tracking ended without the user
 * ([restore], [TrackingState.interrupted]).
 *
 * Whenever tracking ends, however it ends (Stop, arrival, an error, Android closing the app),
 * and before the track file is deleted or started afresh, the track is copied into the
 * [FlightLog] ([archive]). The copy is named after the track's first point, so copying a
 * continued track again replaces the earlier copy.
 */
// The KTX `edit {}` would need androidx.core:core-ktx as a direct dependency for a few calls.
@SuppressLint("UseKtx")
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
        mutableState.update { it.copy(error = null, interrupted = null, lastFix = lastFix) }
        try {
            // A plain start, not startForegroundService(): the app is in the foreground, and if
            // startForeground() then fails the service can stop without the system crashing the
            // app for "did not then call startForeground()".
            context.startService(FlightTrackingService.startIntent(context, flight))
        } catch (_: IllegalStateException) {
            // Not in the foreground after all (background start restrictions).
            clearWanted(context)
            onStopped(TrackingError.NOT_ALLOWED)
        } catch (_: SecurityException) {
            clearWanted(context)
            onStopped(TrackingError.PERMISSION)
        }
    }

    /**
     * Stops tracking (the user asked, or the flight changed), and forgets an
     * [TrackingState.interrupted] one. The track goes into the flight log; with [showSummary]
     * (the user tapped Stop) its summary opens.
     */
    fun stop(context: Context, showSummary: Boolean = false) {
        // Before the service's onDestroy, which takes a remaining mark as an interruption.
        clearWanted(context)
        context.stopService(Intent(context, FlightTrackingService::class.java))
        onStopped(null)
        mutableState.update { it.copy(interrupted = null) }
        archiveLater(context, showSummary)
    }

    fun clearError() {
        mutableState.update { it.copy(error = null) }
    }

    /**
     * On launch: if tracking was still wanted when the process died, marks that flight
     * [TrackingState.interrupted]. Resuming is up to the user, from the visible app, which is
     * what lets a location foreground service start.
     */
    fun restore(context: Context) {
        if (state.value.active) return
        val codes = wanted(context) ?: return
        mutableState.update { if (it.active) it else it.copy(interrupted = codes) }
        // The process died while recording: keep what was recorded, even if it is never resumed.
        archiveLater(context, showSummary = false)
    }

    /** The user doesn't want to resume the interrupted tracking. */
    fun dismissInterrupted(context: Context) {
        clearWanted(context)
        mutableState.update { it.copy(interrupted = null) }
    }

    /** Reads the recorded track of [codes] from the file, unless it is loaded or being recorded. */
    suspend fun loadTrack(context: Context, codes: FlightCodes) {
        if (state.value.trackCodes == codes) return
        val points = withContext(fileIo) { TrackFile(trackFile(context)).read(codes, System.currentTimeMillis()) }
        mutableState.update { if (it.active) it else it.copy(trackCodes = codes, track = points) }
    }

    /** Deletes the recorded track (after keeping it in the flight log): the flight was changed or cleared. Stop tracking first. */
    suspend fun forgetTrack(context: Context) {
        mutableState.update { it.copy(trackCodes = null, track = emptyList()) }
        withContext(fileIo) {
            archive(context)
            TrackFile(trackFile(context)).delete()
        }
    }

    /**
     * Copies the track file into the [FlightLog], with the airports' positions from the bundled
     * list; returns the flight's id, or null when there was nothing to keep (no file, no point)
     * or it couldn't be stored. The airports are read first, so nothing suspends between reading
     * the track and storing it.
     */
    internal suspend fun archive(context: Context): String? {
        val airports = airports(context)
        return withContext(fileIo) {
            val contents = TrackFile(trackFile(context)).contents()
            if (contents == null || contents.points.isEmpty()) return@withContext null
            val record = FlightRecord(
                codes = contents.codes,
                origin = airports?.byIata(contents.codes.origin)?.let { LatLon(it.latitude, it.longitude) },
                destination = airports?.byIata(contents.codes.destination)?.let { LatLon(it.latitude, it.longitude) },
                simulated = false,
                points = contents.points,
            )
            record.id.takeIf { FlightLog.saveNow(context, record) }
        }
    }

    /** [archive] after the track writes already queued; then opens its summary if [showSummary]. */
    private fun archiveLater(context: Context, showSummary: Boolean) {
        val app = context.applicationContext
        fileWrites.launch {
            val id = archive(app)
            if (showSummary && id != null) FlightLog.openSummary(id)
        }
    }

    private suspend fun airports(context: Context): AirportSearch? = try {
        AirportCatalog.load(context)
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** [FlightLog] deleted the flight in the track file: forget it on the map, and any offer to resume it. */
    internal fun onTrackDeleted(context: Context) {
        mutableState.update {
            if (it.active) it else it.copy(trackCodes = null, track = emptyList(), interrupted = null)
        }
        clearWanted(context)
    }

    /** The one track file: it belongs to one flight at a time (its header says which). */
    fun trackFile(context: Context): File = File(context.filesDir, "track.log")

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun wanted(context: Context): FlightCodes? =
        prefs(context).let { FlightCodes.parse(it.getString(WANTED_ORIGIN, null), it.getString(WANTED_DESTINATION, null)) }

    private const val PREFS = "tracking"
    private const val WANTED_ORIGIN = "wanted_origin"
    private const val WANTED_DESTINATION = "wanted_destination"

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

    internal fun onStarted(context: Context, flight: TrackedFlight, track: List<TrackPoint>) {
        prefs(context).edit()
            .putString(WANTED_ORIGIN, flight.codes.origin)
            .putString(WANTED_DESTINATION, flight.codes.destination)
            .apply()
        mutableState.update { it.copy(flight = flight, trackCodes = flight.codes, track = track, error = null, interrupted = null) }
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

    /**
     * The service ended tracking itself (an error, the notification's Stop, or the plane
     * arrived). The track goes into the flight log; with [showSummary] its summary opens.
     */
    internal fun onStoppedByService(context: Context, error: TrackingError?, showSummary: Boolean) {
        clearWanted(context)
        onStopped(error)
        archiveLater(context, showSummary)
    }

    /** The service is being destroyed; if tracking was still wanted, it was interrupted (and the track so far is kept). */
    internal fun onDestroyed(context: Context) {
        val wanted = wanted(context)
        onStopped(null)
        if (wanted != null) {
            mutableState.update { it.copy(interrupted = wanted) }
            archiveLater(context, showSummary = false)
        }
    }

    internal fun clearWanted(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun onStopped(error: TrackingError?) {
        mutableState.update {
            it.copy(flight = null, lastFix = null, providerEnabled = true, error = error ?: it.error)
        }
    }
}
