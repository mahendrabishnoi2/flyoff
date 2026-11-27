package io.github.mahendrabishnoi2.flyoff.flight

import io.github.mahendrabishnoi2.flyoff.geo.Geodesy
import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reference distances and courses are haversine values (asin form) and the Aviation Formulary's
 * course and along-track formulas, computed in Python with R = 6,371,008.8 m; see GeodesyTest.
 */
class FlightStatsCalculatorTest {

    private val lhr = LatLon(51.4706, -0.461941)
    private val jfk = LatLon(40.639801, -73.7789)
    private val routeLength = 5_539_651.7 // haversine LHR-JFK
    private val t0 = 1_795_000_000_000L // 2026-11-18T11:06:40Z

    private fun at(latLon: LatLon, speed: Double? = 250.0, bearing: Double? = null, altitude: Double? = 11_000.0) =
        Position(latLon, altitude, speed, bearing, t0)

    @Test
    fun `at the origin nothing is flown yet`() {
        val stats = FlightStatsCalculator.compute(lhr, jfk, at(lhr))
        assertEquals(0.0, stats.distanceFlownMeters, 1e-6)
        assertEquals(routeLength, stats.distanceRemainingMeters, 1.0)
        assertEquals(routeLength, stats.routeLengthMeters, 1.0)
        assertEquals(0.0, stats.progress, 1e-9)
    }

    @Test
    fun `halfway along the route`() {
        // formulary intermediate point (52.214998, -41.307119); course from there to JFK 255.2981°.
        val stats = FlightStatsCalculator.compute(lhr, jfk, at(LatLon(52.214998, -41.307119)))
        assertEquals(routeLength / 2, stats.distanceFlownMeters, 1.0)
        assertEquals(2_769_825.9, stats.distanceRemainingMeters, 1.0)
        assertEquals(0.5, stats.progress, 1e-6)
        // 2,769,825.9 m at 250 m/s = 11,079.3 s
        assertEquals(11_079L, stats.timeRemainingSeconds)
        assertEquals(t0 + 11_079_000L, stats.etaMillis)
        // No GPS course: heading falls back to the course to the destination.
        assertEquals(255.2981, stats.headingDegrees!!, 1e-3)
    }

    @Test
    fun `GPS values pass through`() {
        val stats = FlightStatsCalculator.compute(lhr, jfk, at(LatLon(52.214998, -41.307119), bearing = 262.5, altitude = 10_668.0))
        assertEquals(262.5, stats.headingDegrees!!, 0.0)
        assertEquals(10_668.0, stats.altitudeMeters!!, 0.0)
        assertEquals(250.0, stats.groundSpeedMetersPerSecond!!, 0.0)
    }

    @Test
    fun `unknown altitude and speed stay unknown`() {
        val stats = FlightStatsCalculator.compute(lhr, jfk, at(LatLon(52.0, -41.0), speed = null, altitude = null))
        assertNull(stats.altitudeMeters)
        assertNull(stats.groundSpeedMetersPerSecond)
        assertNull(stats.timeRemainingSeconds)
        assertNull(stats.etaMillis)
    }

    @Test
    fun `no time remaining below 50 km per hour`() {
        val slow = FlightStatsCalculator.compute(lhr, jfk, at(lhr, speed = 13.8)) // 49.7 km/h, taxiing
        assertNull(slow.timeRemainingSeconds)
        assertNull(slow.etaMillis)
        val justFastEnough = FlightStatsCalculator.compute(lhr, jfk, at(lhr, speed = 50 / 3.6))
        assertEquals((routeLength / (50 / 3.6)), justFastEnough.timeRemainingSeconds!!.toDouble(), 1.0)
    }

    @Test
    fun `off the route the flown distance is along the track`() {
        // formulary ATD for (55, -30): 1,981,717.4 m; haversine to JFK: 3,561,640.8 m.
        val stats = FlightStatsCalculator.compute(lhr, jfk, at(LatLon(55.0, -30.0)))
        assertEquals(1_981_717.4, stats.distanceFlownMeters, 0.5)
        assertEquals(3_561_640.8, stats.distanceRemainingMeters, 0.5)
        assertEquals(1_981_717.4 / (1_981_717.4 + 3_561_640.8), stats.progress, 1e-6)
    }

    @Test
    fun `before the start nothing is flown`() {
        // formulary ATD for (52, 5): -333,075.5 m, clamped to 0; haversine to JFK 5,874,748.4 m.
        val stats = FlightStatsCalculator.compute(lhr, jfk, at(LatLon(52.0, 5.0)))
        assertEquals(0.0, stats.distanceFlownMeters, 0.0)
        assertEquals(5_874_748.4, stats.distanceRemainingMeters, 0.5)
        assertEquals(0.0, stats.progress, 0.0)
    }

    @Test
    fun `past the end the flown distance stops at the route length`() {
        // formulary ATD for (38, -80): 6,136,767.6 m, clamped to the route; haversine to JFK 610,154.1 m.
        val stats = FlightStatsCalculator.compute(lhr, jfk, at(LatLon(38.0, -80.0)))
        assertEquals(routeLength, stats.distanceFlownMeters, 1.0)
        assertEquals(610_154.1, stats.distanceRemainingMeters, 0.5)
        assertEquals(routeLength / (routeLength + 610_154.1), stats.progress, 1e-6)
    }

    @Test
    fun `at the destination the flight is complete`() {
        val stats = FlightStatsCalculator.compute(lhr, jfk, at(jfk, speed = 70.0))
        assertEquals(routeLength, stats.distanceFlownMeters, 1.0)
        assertEquals(0.0, stats.distanceRemainingMeters, 1e-6)
        assertEquals(1.0, stats.progress, 1e-9)
        assertEquals(0L, stats.timeRemainingSeconds)
        assertEquals(t0, stats.etaMillis)
        // Arrived with no GPS course: there is no course to the destination to fall back on.
        assertNull(stats.headingDegrees)
    }

    @Test
    fun `same origin and destination does not crash`() {
        val stats = FlightStatsCalculator.compute(lhr, lhr, at(lhr))
        assertEquals(0.0, stats.routeLengthMeters, 1e-6)
        assertEquals(0.0, stats.distanceFlownMeters, 0.0)
        assertEquals(1.0, stats.progress, 0.0)
        val away = FlightStatsCalculator.compute(lhr, lhr, at(jfk))
        assertEquals(0.0, away.distanceFlownMeters, 0.0)
        assertEquals(routeLength, away.distanceRemainingMeters, 1.0)
        assertEquals(0.0, away.progress, 0.0)
    }

    @Test
    fun `antipodal origin and destination fall back to the distance left`() {
        val origin = LatLon(0.0, 0.0)
        val destination = LatLon(0.0, 180.0)
        val position = LatLon(0.0, 90.0) // a quarter of the way round: half of an antipodal route
        val stats = FlightStatsCalculator.compute(origin, destination, at(position))
        val half = Geodesy.EARTH_RADIUS_M * Math.PI / 2
        assertEquals(half, stats.distanceFlownMeters, 1e-3)
        assertEquals(half, stats.distanceRemainingMeters, 1e-3)
        assertEquals(0.5, stats.progress, 1e-9)
    }
}
