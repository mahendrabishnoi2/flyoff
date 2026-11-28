package io.github.mahendrabishnoi2.flyoff.flight

import io.github.mahendrabishnoi2.flyoff.download.LandingRule
import io.github.mahendrabishnoi2.flyoff.geo.LatLon

/**
 * When a tracked flight is over, so tracking can end on its own and show the summary.
 *
 * [LandingRule] (the first position within 30 km of the destination) is when the plane counts as
 * landed, but that is still on the final approach, minutes before touchdown. So the flight is
 * over at the first recorded point after that rule holds whose GPS ground speed is below
 * [FlightStatsCalculator.MIN_SPEED_FOR_ETA_MPS] (50 km/h): on the runway after touchdown, or
 * taxiing. It has to have flown first: a track that never reached flying speed (tracking started
 * at the destination's gate) never arrives. Without a ground speed nothing is decided.
 */
object ArrivalRule {

    /** True at flying speed: the plane has taken off (or is about to). */
    fun isAirborne(point: TrackPoint): Boolean {
        val speed = point.speedMetersPerSecond ?: return false
        return speed >= FlightStatsCalculator.MIN_SPEED_FOR_ETA_MPS
    }

    /** True when [point] ends a flight that [wasAirborne] before it. */
    fun hasArrived(wasAirborne: Boolean, point: TrackPoint, origin: LatLon, destination: LatLon): Boolean {
        val speed = point.speedMetersPerSecond ?: return false
        return wasAirborne &&
            speed < FlightStatsCalculator.MIN_SPEED_FOR_ETA_MPS &&
            LandingRule.isLanded(point.latLon, origin, destination)
    }
}
