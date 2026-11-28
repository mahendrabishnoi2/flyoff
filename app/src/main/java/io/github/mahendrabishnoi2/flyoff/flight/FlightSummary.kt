package io.github.mahendrabishnoi2.flyoff.flight

import io.github.mahendrabishnoi2.flyoff.geo.Geodesy

/** The numbers on the summary screen after a flight. Distances in metres, speeds in m/s. */
data class FlightSummary(
    val distanceFlownMeters: Double,
    /** Airport to airport; null when the airports' positions are unknown. */
    val greatCircleMeters: Double?,
    val durationMillis: Long,
    /** GPS height (above the WGS-84 ellipsoid); null when no point had one. */
    val maxAltitudeMeters: Double?,
    val maxGroundSpeedMetersPerSecond: Double?,
    /** Distance flown ÷ duration; null for a flight with no duration. */
    val averageSpeedMetersPerSecond: Double?,
    /** How long the app showed an estimated (dead-reckoned) position instead of a GPS fix. */
    val estimatedMillis: Long,
)

object FlightSummaryCalculator {

    /**
     * Works out the summary of [record]:
     *
     * - **distance flown**: [distanceFlownMeters], the sum of the great-circle legs between
     *   consecutive recorded points. Across a GPS drop-out that is the straight leg between the
     *   fixes either side of it.
     * - **great-circle distance**: from the origin airport to the destination airport.
     * - **duration**: from the earliest to the latest recorded point.
     * - **max altitude** and **max ground speed**: the highest values the GPS reported.
     * - **average speed**: distance flown ÷ duration.
     * - **estimated time**: the app shows a dead-reckoned position from
     *   [DeadReckoning.STALE_AFTER_MILLIS] after a fix until the next fix, for at most
     *   [DeadReckoning.MAX_ESTIMATE_MILLIS], and only while the last fix was at flying speed
     *   (as [DeadReckoning.estimate] decides). Summed over the gaps between recorded points.
     *   Time while Android had closed the app counts too: the track has no fix then either.
     *
     * Times that go backwards (a clock correction) add nothing.
     */
    fun compute(record: FlightRecord): FlightSummary {
        val points = record.points
        val distance = distanceFlownMeters(points)
        val duration = (points.maxOf { it.timeMillis } - points.minOf { it.timeMillis }).coerceAtLeast(0)
        val greatCircle = if (record.origin != null && record.destination != null) {
            Geodesy.distanceMeters(record.origin, record.destination)
        } else {
            null
        }
        return FlightSummary(
            distanceFlownMeters = distance,
            greatCircleMeters = greatCircle,
            durationMillis = duration,
            maxAltitudeMeters = points.mapNotNull { it.altitudeMeters }.maxOrNull(),
            maxGroundSpeedMetersPerSecond = points.mapNotNull { it.speedMetersPerSecond }.maxOrNull(),
            averageSpeedMetersPerSecond = if (duration > 0) distance / (duration / 1000.0) else null,
            estimatedMillis = estimatedMillis(points),
        )
    }

    /** The sum of the great-circle distances between consecutive points; 0 for fewer than two. */
    fun distanceFlownMeters(points: List<TrackPoint>): Double =
        points.zipWithNext().sumOf { (a, b) -> Geodesy.distanceMeters(a.latLon, b.latLon) }

    private fun estimatedMillis(points: List<TrackPoint>): Long = points.zipWithNext().sumOf { (fix, next) ->
        val gap = next.timeMillis - fix.timeMillis
        val speed = fix.speedMetersPerSecond
        val estimating = DeadReckoning.isStale(gap) && speed != null && speed >= FlightStatsCalculator.MIN_SPEED_FOR_ETA_MPS
        if (estimating) gap.coerceAtMost(DeadReckoning.MAX_ESTIMATE_MILLIS) - DeadReckoning.STALE_AFTER_MILLIS else 0L
    }
}
