package io.github.mahendrabishnoi2.flyoff.flight

import io.github.mahendrabishnoi2.flyoff.geo.Geodesy
import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import kotlin.math.roundToLong

/**
 * One position fix, from the GPS or from [SimulatedFlight].
 * Each optional value is null when the source didn't report it.
 */
data class Position(
    val latLon: LatLon,
    /** Height in metres (GPS height above the WGS-84 ellipsoid on a real fix). */
    val altitudeMeters: Double?,
    val speedMetersPerSecond: Double?,
    /** Course over the ground in degrees from true north. */
    val bearingDegrees: Double?,
    /** Epoch milliseconds. */
    val timeMillis: Long,
)

/**
 * The numbers shown during a flight. Distances are great-circle metres on the sphere of
 * [Geodesy]; see [FlightStatsCalculator.compute] for how each is worked out.
 */
data class FlightStats(
    val altitudeMeters: Double?,
    val groundSpeedMetersPerSecond: Double?,
    val headingDegrees: Double?,
    val distanceFlownMeters: Double,
    val distanceRemainingMeters: Double,
    val routeLengthMeters: Double,
    /** Null when the speed is unknown or below [FlightStatsCalculator.MIN_SPEED_FOR_ETA_MPS]. */
    val timeRemainingSeconds: Long?,
    /** Estimated arrival, epoch milliseconds; null whenever [timeRemainingSeconds] is. */
    val etaMillis: Long?,
    /** 0 at the origin, 1 at the destination. */
    val progress: Double,
)

object FlightStatsCalculator {

    /** 50 km/h. Below this (taxiing, holding on the ground) an arrival estimate means nothing. */
    const val MIN_SPEED_FOR_ETA_MPS: Double = 50.0 / 3.6

    /**
     * Works out the flight stats for [position] on the great-circle route from [origin] to
     * [destination]:
     *
     * - **altitude** and **ground speed**: the position's own values, null when unknown.
     * - **heading**: the position's GPS course when it has one; otherwise the great-circle
     *   course from the position to the destination; null without a GPS course within 1 m of
     *   the destination, where that course is undefined.
     * - **distance flown**: the along-track distance from the origin, that is how far along the
     *   route the position's closest point on the route lies, clamped to 0..route length. Being
     *   off the route sideways doesn't add to it.
     * - **distance remaining**: the great-circle distance from the position straight to the
     *   destination (what is actually left to fly, even off the route).
     * - **time remaining**: distance remaining ÷ ground speed, rounded to whole seconds; null
     *   when the speed is unknown or below [MIN_SPEED_FOR_ETA_MPS].
     * - **ETA**: the position's time plus the time remaining.
     * - **progress**: flown ÷ (flown + remaining), in 0..1; 1 when both are zero.
     *
     * When the origin and destination are the same point, nothing counts as flown. When they are
     * exactly antipodal no single route joins them, so distance flown becomes route length minus
     * distance remaining.
     */
    fun compute(origin: LatLon, destination: LatLon, position: Position): FlightStats {
        val here = position.latLon
        val routeLength = Geodesy.distanceMeters(origin, destination)
        val remaining = Geodesy.distanceMeters(here, destination)
        val flown = when {
            Geodesy.hasTrack(origin, destination) ->
                Geodesy.alongTrackMeters(origin, destination, here).coerceIn(0.0, routeLength)
            routeLength > 0.5 * Math.PI * Geodesy.EARTH_RADIUS_M -> (routeLength - remaining).coerceIn(0.0, routeLength)
            else -> 0.0
        }
        val total = flown + remaining
        val progress = if (total > 0.0) (flown / total).coerceIn(0.0, 1.0) else 1.0

        val heading = position.bearingDegrees
            ?: if (remaining > 1.0) Geodesy.initialBearingDegrees(here, destination) else null

        val speed = position.speedMetersPerSecond
        val timeRemaining = if (speed != null && speed >= MIN_SPEED_FOR_ETA_MPS) {
            (remaining / speed).roundToLong()
        } else {
            null
        }

        return FlightStats(
            altitudeMeters = position.altitudeMeters,
            groundSpeedMetersPerSecond = speed,
            headingDegrees = heading,
            distanceFlownMeters = flown,
            distanceRemainingMeters = remaining,
            routeLengthMeters = routeLength,
            timeRemainingSeconds = timeRemaining,
            etaMillis = timeRemaining?.let { position.timeMillis + it * 1000 },
            progress = progress,
        )
    }
}
