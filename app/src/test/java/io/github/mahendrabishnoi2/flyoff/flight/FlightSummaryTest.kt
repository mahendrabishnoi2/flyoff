package io.github.mahendrabishnoi2.flyoff.flight

import io.github.mahendrabishnoi2.flyoff.geo.Geodesy
import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightSummaryTest {

    private val t0 = 1_767_225_600_000L
    private val lhr = LatLon(51.4706, -0.461941)
    private val cdg = LatLon(49.0097, 2.5479)
    private val codes = FlightCodes("LHR", "CDG")

    private fun record(points: List<TrackPoint>, origin: LatLon? = lhr, destination: LatLon? = cdg) =
        FlightRecord(codes, origin, destination, simulated = false, points = points)

    private fun at(seconds: Long, latLon: LatLon, altitude: Double? = 10_000.0, speed: Double? = 230.0) =
        TrackPoint(t0 + seconds * 1000, latLon, altitude, speed)

    @Test
    fun `distance flown adds up the legs between points`() {
        val a = LatLon(51.0, 0.0)
        val b = LatLon(51.0, 1.0)
        val c = LatLon(50.0, 1.0)
        val summary = FlightSummaryCalculator.compute(record(listOf(at(0, a), at(5, b), at(10, c))))
        val expected = Geodesy.distanceMeters(a, b) + Geodesy.distanceMeters(b, c)
        assertEquals(expected, summary.distanceFlownMeters, 1e-6)
        assertEquals(Geodesy.distanceMeters(lhr, cdg), summary.greatCircleMeters!!, 1e-6)
    }

    @Test
    fun `a dog-leg is longer than the great circle`() {
        val middle = LatLon(51.5, 2.0)
        val summary = FlightSummaryCalculator.compute(record(listOf(at(0, lhr), at(600, middle), at(1200, cdg))))
        assertTrue(summary.distanceFlownMeters > summary.greatCircleMeters!!)
    }

    @Test
    fun `duration, maxima and average speed`() {
        val points = listOf(
            at(0, LatLon(51.0, 0.0), altitude = 100.0, speed = 80.0),
            at(60, LatLon(51.0, 0.1), altitude = 3_000.0, speed = 250.0),
            at(120, LatLon(51.0, 0.2), altitude = null, speed = null),
            at(180, LatLon(51.0, 0.3), altitude = 1_000.0, speed = 120.0),
        )
        val summary = FlightSummaryCalculator.compute(record(points))
        assertEquals(180_000L, summary.durationMillis)
        assertEquals(3_000.0, summary.maxAltitudeMeters!!, 0.0)
        assertEquals(250.0, summary.maxGroundSpeedMetersPerSecond!!, 0.0)
        assertEquals(summary.distanceFlownMeters / 180.0, summary.averageSpeedMetersPerSecond!!, 1e-9)
    }

    @Test
    fun `unknown altitude and speed stay unknown`() {
        val points = listOf(at(0, lhr, null, null), at(5, cdg, null, null))
        val summary = FlightSummaryCalculator.compute(record(points))
        assertNull(summary.maxAltitudeMeters)
        assertNull(summary.maxGroundSpeedMetersPerSecond)
    }

    @Test
    fun `one point has no distance, duration or average speed`() {
        val summary = FlightSummaryCalculator.compute(record(listOf(at(0, lhr))))
        assertEquals(0.0, summary.distanceFlownMeters, 0.0)
        assertEquals(0L, summary.durationMillis)
        assertNull(summary.averageSpeedMetersPerSecond)
        assertEquals(0L, summary.estimatedMillis)
    }

    @Test
    fun `no airport positions means no great-circle distance`() {
        val summary = FlightSummaryCalculator.compute(record(listOf(at(0, lhr), at(5, cdg)), origin = null))
        assertNull(summary.greatCircleMeters)
    }

    @Test
    fun `time on an estimated position is the part of each GPS gap the app shows an estimate`() {
        // Fixes every 5 s, then a 10-minute gap, then 5 s again. The app shows an estimate from
        // 10 s after the last fix until the next one.
        val points = listOf(
            at(0, LatLon(51.0, 0.0)),
            at(5, LatLon(51.0, 0.01)),
            at(605, LatLon(51.0, 1.0)),
            at(610, LatLon(51.0, 1.01)),
        )
        val summary = FlightSummaryCalculator.compute(record(points))
        assertEquals(600_000L - DeadReckoning.STALE_AFTER_MILLIS, summary.estimatedMillis)
    }

    @Test
    fun `short gaps and gaps on the ground show no estimate`() {
        val points = listOf(
            at(0, LatLon(51.0, 0.0)),
            // A missed fix: 10 s is just stale, but nothing is shown for longer than that.
            at(10, LatLon(51.0, 0.02)),
            // Slow (taxiing): dead reckoning shows nothing, however long the gap.
            at(15, LatLon(51.0, 0.03), speed = 5.0),
            at(915, LatLon(51.0, 0.04)),
            // Speed unknown: no estimate either.
            at(920, LatLon(51.0, 0.05), speed = null),
            at(1_920, LatLon(51.0, 0.06)),
        )
        assertEquals(0L, FlightSummaryCalculator.compute(record(points)).estimatedMillis)
    }

    @Test
    fun `an estimate stops after the dead-reckoning limit`() {
        val gapSeconds = DeadReckoning.MAX_ESTIMATE_MILLIS / 1000 + 3_600
        val points = listOf(at(0, LatLon(51.0, 0.0)), at(gapSeconds, LatLon(41.0, -70.0)))
        assertEquals(
            DeadReckoning.MAX_ESTIMATE_MILLIS - DeadReckoning.STALE_AFTER_MILLIS,
            FlightSummaryCalculator.compute(record(points)).estimatedMillis,
        )
    }

    @Test
    fun `a clock that went backwards adds no time`() {
        // 100 s, then a fix stamped 60 s earlier: no 60-second gap, and no negative estimate.
        val points = listOf(at(100, LatLon(51.0, 0.0)), at(40, LatLon(51.0, 0.1)), at(45, LatLon(51.0, 0.2)))
        val summary = FlightSummaryCalculator.compute(record(points))
        assertEquals(60_000L, summary.durationMillis)
        assertEquals(0L, summary.estimatedMillis)
    }

    @Test
    fun `the simulator's LHR to CDG flight sums to about the great circle`() {
        val flight = SimulatedFlight(lhr, cdg)
        val points = flight.recordedTrack(t0, gaps = false)
        val summary = FlightSummaryCalculator.compute(record(points))
        assertEquals(flight.routeLengthMeters, summary.distanceFlownMeters, 50.0)
        assertEquals((flight.durationSeconds * 1000).toLong().toDouble(), summary.durationMillis.toDouble(), 1_000.0)
        assertEquals(0L, summary.estimatedMillis)
        assertTrue(summary.maxAltitudeMeters!! > 3_000.0)
    }

    @Test
    fun `the simulator's GPS gaps show as estimated time`() {
        val flight = SimulatedFlight(LatLon(51.4706, -0.461941), LatLon(40.6398, -73.7789))
        val points = flight.recordedTrack(t0, gaps = true)
        val summary = FlightSummaryCalculator.compute(record(points, destination = LatLon(40.6398, -73.7789)))
        // GpsGaps drops 10 % and 7 % of the flight.
        val expected = 0.17 * flight.durationSeconds * 1000
        assertEquals(expected, summary.estimatedMillis.toDouble(), 30_000.0)
    }
}
