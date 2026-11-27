package io.github.mahendrabishnoi2.flyoff.flight

import io.github.mahendrabishnoi2.flyoff.geo.Geodesy
import io.github.mahendrabishnoi2.flyoff.geo.LatLon

/**
 * Keeps the dot moving while the GPS has dropped out (a window seat that loses the sky, a phone
 * pocketed mid-flight), shown as an estimate.
 *
 * **Toward the destination from the last fix, not back onto the planned route.** The estimate
 * flies the great circle from the last fix to the destination. Real flights are often 100 km or
 * more off the airport-to-airport great circle (oceanic tracks, airways, weather), so snapping to
 * the planned line would make the dot jump sideways the moment the GPS drops. Starting from where
 * the plane really was keeps the dot continuous, and every route ends at the destination anyway.
 *
 * **The age is a duration.** Callers pass how long ago the last fix was, measured on a monotonic
 * clock (`SystemClock.elapsedRealtime()` for the GPS, the simulator's own clock for a simulated
 * flight), never the difference of two wall-clock times. A phone clock that is wrong, or that
 * jumps when the network time or time zone changes after landing, then can't move the dot.
 */
object DeadReckoning {

    /** No fix for this long means the GPS has dropped out. */
    const val STALE_AFTER_MILLIS: Long = 10_000

    /** Stop estimating this long after the last fix: the guess is worthless by then. */
    const val MAX_ESTIMATE_MILLIS: Long = 3 * 60 * 60 * 1000L

    /** Within this distance of the destination the estimate counts as arrived (no course any more). */
    private const val ARRIVED_METERS = 1.0

    /**
     * True when the last fix is at least [STALE_AFTER_MILLIS] old. A negative age (a clock problem,
     * not a drop-out) is not stale.
     */
    fun isStale(millisSinceFix: Long): Boolean = millisSinceFix >= STALE_AFTER_MILLIS

    /**
     * Where the plane probably is [millisSinceFix] after [lastFix]: moved from the last fix toward
     * [destination] along the great circle at the last ground speed, keeping the last altitude.
     * Returns null when there is nothing sensible to show: no speed, speed below
     * FlightStatsCalculator.MIN_SPEED_FOR_ETA_MPS (on the ground), millisSinceFix < 0, or older
     * than MAX_ESTIMATE_MILLIS. Never passes the destination (stops there). The result has
     * estimated = true, timeMillis = lastFix.timeMillis + millisSinceFix, bearing = course toward
     * the destination at the estimated point (null once at the destination), and the last speed.
     *
     * [millisSinceFix] is a duration from a monotonic clock; see the class comment for why.
     * [lastFix] may itself be an estimate.
     */
    fun estimate(destination: LatLon, lastFix: Position, millisSinceFix: Long): Position? {
        if (millisSinceFix < 0 || millisSinceFix > MAX_ESTIMATE_MILLIS) return null
        val speed = lastFix.speedMetersPerSecond ?: return null
        // Written so that NaN fails too.
        if (!(speed >= FlightStatsCalculator.MIN_SPEED_FOR_ETA_MPS) || speed.isInfinite()) return null

        val start = lastFix.latLon
        val remaining = Geodesy.distanceMeters(start, destination)
        val travelled = speed * millisSinceFix / 1000.0
        val here = when {
            travelled >= remaining -> destination
            else -> Geodesy.interpolate(start, destination, travelled / remaining)
        }
        val bearing = if (Geodesy.distanceMeters(here, destination) > ARRIVED_METERS) {
            Geodesy.initialBearingDegrees(here, destination)
        } else {
            null
        }
        return Position(
            latLon = here,
            altitudeMeters = lastFix.altitudeMeters,
            speedMetersPerSecond = speed,
            bearingDegrees = bearing,
            timeMillis = lastFix.timeMillis + millisSinceFix,
            estimated = true,
        )
    }
}
