package io.github.mahendrabishnoi2.flyoff.flight

import io.github.mahendrabishnoi2.flyoff.geo.Geodesy
import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * A made-up flight along the great circle from [origin] to [destination], for the in-app flight
 * simulator. It is a plausible profile, not a performance model:
 *
 * 1. **Climb** over the first [CLIMB_DISTANCE_M] (180 km): speed rises steadily from
 *    [TAKEOFF_SPEED_MPS] to [cruiseSpeedMetersPerSecond] and altitude from 0 to
 *    [cruiseAltitudeMeters], both linear in time.
 * 2. **Cruise** at that speed and altitude.
 * 3. **Descent** over the last [DESCENT_DISTANCE_M] (230 km): speed falls steadily to
 *    [LANDING_SPEED_MPS] and altitude to 0, touching down at the destination.
 *
 * On a route too short for that, the climb takes at most 40 % of the route and the descent at
 * most 50 %, and the top of climb (both its speed and altitude) shrinks in proportion to the
 * shortened climb, so a 100 km hop peaks near 2,400 m rather than at cruise altitude. Take-off
 * and landing speeds never exceed the cruise speed.
 */
class SimulatedFlight(
    val origin: LatLon,
    val destination: LatLon,
    val cruiseSpeedMetersPerSecond: Double = 250.0,
    val cruiseAltitudeMeters: Double = 11_000.0,
) {
    init {
        require(cruiseSpeedMetersPerSecond.isFinite() && cruiseSpeedMetersPerSecond > 0.0) {
            "cruise speed $cruiseSpeedMetersPerSecond must be positive"
        }
        require(cruiseAltitudeMeters.isFinite() && cruiseAltitudeMeters >= 0.0) {
            "cruise altitude $cruiseAltitudeMeters must not be negative"
        }
    }

    /** Great-circle distance from [origin] to [destination]. */
    val routeLengthMeters: Double = Geodesy.distanceMeters(origin, destination)

    private val climbDistance = min(CLIMB_DISTANCE_M, 0.4 * routeLengthMeters)
    private val descentDistance = min(DESCENT_DISTANCE_M, 0.5 * routeLengthMeters)
    private val cruiseDistance = routeLengthMeters - climbDistance - descentDistance

    /** How much of the full climb fits: 1 on a long route, less on a short one. */
    private val climbScale = climbDistance / CLIMB_DISTANCE_M
    private val takeoffSpeed = min(TAKEOFF_SPEED_MPS, cruiseSpeedMetersPerSecond)
    private val landingSpeed = min(LANDING_SPEED_MPS, cruiseSpeedMetersPerSecond)
    private val topSpeed = takeoffSpeed + (cruiseSpeedMetersPerSecond - takeoffSpeed) * climbScale
    private val topAltitude = cruiseAltitudeMeters * climbScale

    // With speed changing linearly in time, a phase lasts distance ÷ average speed.
    private val climbSeconds = 2 * climbDistance / (takeoffSpeed + topSpeed)
    private val cruiseSeconds = cruiseDistance / topSpeed
    private val descentSeconds = 2 * descentDistance / (topSpeed + landingSpeed)

    /** Seconds from take-off at [origin] to touchdown at [destination]. */
    val durationSeconds: Double = climbSeconds + cruiseSeconds + descentSeconds

    /** The direction of travel on arrival: the great circle's final course. */
    private val finalCourse = (Geodesy.initialBearingDegrees(destination, origin) + 180.0) % 360.0

    /**
     * Where the flight is [elapsedSeconds] after take-off; its time is [startTimeMillis] plus
     * that. From [durationSeconds] on it is at the [destination] on the ground; after touchdown
     * it has stopped (speed 0). The bearing is the current direction of travel along the great
     * circle.
     */
    fun positionAt(elapsedSeconds: Double, startTimeMillis: Long): Position {
        require(elapsedSeconds >= 0.0) { "elapsed time $elapsedSeconds must not be negative" }
        val time = startTimeMillis + (elapsedSeconds * 1000).roundToLong()
        if (elapsedSeconds >= durationSeconds || routeLengthMeters == 0.0) {
            return Position(destination, 0.0, 0.0, finalCourse, time)
        }

        val distance: Double
        val speed: Double
        val altitude: Double
        when {
            elapsedSeconds < climbSeconds -> {
                val t = elapsedSeconds
                val accel = (topSpeed - takeoffSpeed) / climbSeconds
                distance = takeoffSpeed * t + accel * t * t / 2
                speed = takeoffSpeed + accel * t
                altitude = topAltitude * t / climbSeconds
            }
            elapsedSeconds < climbSeconds + cruiseSeconds -> {
                distance = climbDistance + topSpeed * (elapsedSeconds - climbSeconds)
                speed = topSpeed
                altitude = topAltitude
            }
            else -> {
                val t = elapsedSeconds - climbSeconds - cruiseSeconds
                val accel = (landingSpeed - topSpeed) / descentSeconds
                distance = climbDistance + cruiseDistance + topSpeed * t + accel * t * t / 2
                speed = topSpeed + accel * t
                altitude = (topAltitude * (1 - t / descentSeconds)).coerceAtLeast(0.0)
            }
        }

        val fraction = (distance / routeLengthMeters).coerceIn(0.0, 1.0)
        val here = Geodesy.interpolate(origin, destination, fraction)
        val bearing = if (Geodesy.distanceMeters(here, destination) > 1.0) {
            Geodesy.initialBearingDegrees(here, destination)
        } else {
            finalCourse
        }
        return Position(here, altitude, speed, bearing, time)
    }

    /**
     * The track the tracking service would record for this flight taken off at [takeoffMillis]:
     * a point every [intervalSeconds] of simulated time from take-off, and touchdown. With
     * [gaps], none during [GpsGaps]' drop-outs, as no fix arrives then. For the flight log.
     */
    fun recordedTrack(takeoffMillis: Long, gaps: Boolean, intervalSeconds: Int = 5): List<TrackPoint> {
        require(intervalSeconds > 0) { "interval $intervalSeconds must be positive" }
        val times = generateSequence(0.0) { it + intervalSeconds }.takeWhile { it < durationSeconds } + durationSeconds
        return times
            .filterNot { gaps && GpsGaps.inGap(it, durationSeconds) }
            .map { positionAt(it, takeoffMillis) }
            .map { TrackPoint(it.timeMillis, it.latLon, it.altitudeMeters, it.speedMetersPerSecond) }
            .toList()
    }

    companion object {
        /** Ground distance of a full climb to cruise altitude. */
        const val CLIMB_DISTANCE_M: Double = 180_000.0

        /** Ground distance of a full descent from cruise altitude. */
        const val DESCENT_DISTANCE_M: Double = 230_000.0

        /** About 155 kt, a typical airliner take-off speed. */
        const val TAKEOFF_SPEED_MPS: Double = 80.0

        /** About 136 kt, a typical airliner touchdown speed. */
        const val LANDING_SPEED_MPS: Double = 70.0
    }
}
