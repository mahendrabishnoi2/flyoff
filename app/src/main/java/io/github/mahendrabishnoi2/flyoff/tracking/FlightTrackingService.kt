package io.github.mahendrabishnoi2.flyoff.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.mahendrabishnoi2.flyoff.MainActivity
import io.github.mahendrabishnoi2.flyoff.R
import io.github.mahendrabishnoi2.flyoff.download.LandingRule
import io.github.mahendrabishnoi2.flyoff.download.RouteMaps
import io.github.mahendrabishnoi2.flyoff.flight.FlightCodes
import io.github.mahendrabishnoi2.flyoff.flight.TrackDecimator
import io.github.mahendrabishnoi2.flyoff.flight.TrackPoint
import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import io.github.mahendrabishnoi2.flyoff.ui.toPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Records the flight with the screen off: a foreground service of type `location`, so it keeps
 * the GPS on after the user leaves the app. It is started only from the visible app with precise
 * location granted, so while-in-use location covers it; there is no background location
 * permission.
 *
 * While it runs it owns the GPS (the UI stops its own listener): [LocationManager.GPS_PROVIDER]
 * only, no network and no Play services, a fix every second while the app is visible and every
 * 5 s otherwise. No wake lock: the GPS fixes themselves wake the app. Points go through a
 * [TrackDecimator] (one per 5 s) into [Tracker] and the [TrackFile]; the writes are queued in
 * [Tracker], so a stop never drops the newest point.
 *
 * It stops when the user taps Stop (in the app or the notification), when the flight is
 * changed or cleared (the UI stops it), or when location permission is missing. If the user
 * turns location off it keeps running and says so in the notification: the listener stays
 * registered and recording resumes by itself when location comes back, so a mistaken toggle
 * doesn't end the track.
 *
 * `START_NOT_STICKY`: if Android kills the process, a restart from the background couldn't
 * legally get location anyway. The next launch shows that tracking stopped (see
 * [Tracker.restore]), and Resume, or Start, continues the same file
 * (unless its newest point is over 12 hours old: see [TrackFile]).
 */
class FlightTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var locationManager: LocationManager
    private lateinit var notifications: NotificationManager
    private lateinit var trackFile: TrackFile
    private val decimator = TrackDecimator()
    private var flight: TrackedFlight? = null

    /** Whether this tracking session has recorded the landing ([LandingRule]) already. */
    private var landed = false
    private var jobs: List<Job> = emptyList()

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            Tracker.onFix(location)
            val point = TrackPoint(
                timeMillis = location.time,
                latLon = LatLon(location.latitude, location.longitude),
                altitudeMeters = if (location.hasAltitude()) location.altitude else null,
                speedMetersPerSecond = if (location.hasSpeed()) location.speed.toDouble() else null,
            )
            if (decimator.accept(point)) {
                Tracker.onPoint(point)
                Tracker.appendLater(trackFile, point)
                checkLanded(point)
            }
        }

        override fun onProviderEnabled(provider: String) = Tracker.onProviderEnabled(true)

        override fun onProviderDisabled(provider: String) = Tracker.onProviderEnabled(false)

        // Before Android 11 the platform interface has no default methods.
        @Deprecated("Deprecated in Android 10; still called on older versions.")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    /**
     * At the first recorded point near the destination ([LandingRule]), stores the landing time
     * with the flight's route maps, which are then deleted 3 days later.
     */
    private fun checkLanded(point: TrackPoint) {
        val tracked = flight ?: return
        if (landed || !LandingRule.isLanded(point.latLon, tracked.origin, tracked.destination)) return
        landed = true
        RouteMaps.markLanded(this, tracked.codes.origin, tracked.codes.destination, point.timeMillis)
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        notifications = getSystemService(NotificationManager::class.java)
        trackFile = TrackFile(Tracker.trackFile(this))
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.tracking_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.tracking_channel_description)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.takeIf { it.action == ACTION_START }?.let(::flightOf)
        if (requested == null) {
            // The notification's Stop, or nothing to track.
            stopTracking(null)
            return START_NOT_STICKY
        }
        if (!goForeground(requested)) return START_NOT_STICKY
        if (requested != flight) begin(requested)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        locationManager.removeUpdates(listener)
        scope.cancel()
        // The 15 s refresh may have re-posted the notification; it must not outlive the service.
        notifications.cancel(NOTIFICATION_ID)
        Tracker.onDestroyed(this)
        super.onDestroy()
    }

    /** Becomes a foreground service at once; false (and stopped) if Android refuses. */
    private fun goForeground(requested: TrackedFlight): Boolean {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopTracking(TrackingError.PERMISSION)
            return false
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(requested), type)
            true
        } catch (_: SecurityException) {
            // Android 14+: location permission missing for a location-type service.
            stopTracking(TrackingError.PERMISSION)
            false
        } catch (_: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (Android 12+): not started from the foreground.
            stopTracking(TrackingError.NOT_ALLOWED)
            false
        }
    }

    private fun begin(requested: TrackedFlight) {
        jobs.forEach { it.cancel() }
        locationManager.removeUpdates(listener)
        flight = requested
        landed = false
        decimator.reset()
        jobs = listOf(
            scope.launch {
                // Load (or start) the file before the first fix, so no point is lost or doubled.
                val points = withContext(Tracker.fileIo) {
                    try {
                        trackFile.open(requested.codes, System.currentTimeMillis())
                    } catch (_: IOException) {
                        emptyList()
                    }
                }
                points.lastOrNull()?.let(decimator::accept)
                Tracker.onStarted(this@FlightTrackingService, requested, points)
                Tracker.uiVisible.collect { visible -> requestGps(if (visible) VISIBLE_INTERVAL_MS else HIDDEN_INTERVAL_MS) }
            },
            scope.launch {
                while (true) {
                    delay(NOTIFICATION_INTERVAL_MS)
                    notifications.notify(NOTIFICATION_ID, notification(requested))
                }
            },
        )
    }

    /** Asks for GPS fixes every [intervalMs]; asking again with the same listener replaces the request. */
    private fun requestGps(intervalMs: Long) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopTracking(TrackingError.PERMISSION)
            return
        }
        try {
            Tracker.onProviderEnabled(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, listener, Looper.getMainLooper())
        } catch (_: SecurityException) {
            stopTracking(TrackingError.PERMISSION)
        } catch (_: IllegalArgumentException) {
            stopTracking(TrackingError.NO_GPS)
        }
    }

    private fun stopTracking(error: TrackingError?) {
        locationManager.removeUpdates(listener)
        jobs.forEach { it.cancel() }
        flight = null
        Tracker.onStoppedByService(this, error)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(tracked: TrackedFlight): Notification {
        val state = Tracker.state.value
        val fix = state.lastFix
        val age = fix?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 } ?: 0L
        val text = trackingNotificationText(
            flight = tracked,
            providerEnabled = state.providerEnabled,
            lastFix = fix?.toPosition(),
            millisSinceFix = age,
            nowMillis = System.currentTimeMillis(),
            locale = resources.configuration.locales[0],
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, FlightTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_plane)
            .setContentTitle(text.title)
            .setContentText(text.text)
            .setContentIntent(open)
            .addAction(R.drawable.ic_close, getString(R.string.stop_tracking), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        /** The notification channel; the CI smoke test looks for it in `dumpsys notification`. */
        const val CHANNEL_ID = "flight_tracking"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "io.github.mahendrabishnoi2.flyoff.action.START_TRACKING"
        private const val ACTION_STOP = "io.github.mahendrabishnoi2.flyoff.action.STOP_TRACKING"
        private const val VISIBLE_INTERVAL_MS = 1_000L
        private const val HIDDEN_INTERVAL_MS = 5_000L
        private const val NOTIFICATION_INTERVAL_MS = 15_000L

        private const val ORIGIN = "origin"
        private const val DESTINATION = "destination"
        private const val ORIGIN_LAT = "origin_lat"
        private const val ORIGIN_LON = "origin_lon"
        private const val DESTINATION_LAT = "destination_lat"
        private const val DESTINATION_LON = "destination_lon"
        private const val DESTINATION_ZONE = "destination_zone"

        fun startIntent(context: Context, flight: TrackedFlight): Intent =
            Intent(context, FlightTrackingService::class.java)
                .setAction(ACTION_START)
                .putExtra(ORIGIN, flight.codes.origin)
                .putExtra(DESTINATION, flight.codes.destination)
                .putExtra(ORIGIN_LAT, flight.origin.latitude)
                .putExtra(ORIGIN_LON, flight.origin.longitude)
                .putExtra(DESTINATION_LAT, flight.destination.latitude)
                .putExtra(DESTINATION_LON, flight.destination.longitude)
                .putExtra(DESTINATION_ZONE, flight.destinationZone)

        private fun flightOf(intent: Intent): TrackedFlight? {
            val codes = FlightCodes.parse(intent.getStringExtra(ORIGIN), intent.getStringExtra(DESTINATION)) ?: return null
            val zone = intent.getStringExtra(DESTINATION_ZONE) ?: return null
            return runCatching {
                TrackedFlight(
                    codes = codes,
                    origin = LatLon(intent.getDoubleExtra(ORIGIN_LAT, Double.NaN), intent.getDoubleExtra(ORIGIN_LON, Double.NaN)),
                    destination = LatLon(intent.getDoubleExtra(DESTINATION_LAT, Double.NaN), intent.getDoubleExtra(DESTINATION_LON, Double.NaN)),
                    destinationZone = zone,
                )
            }.getOrNull()
        }
    }
}
