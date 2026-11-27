package io.github.mahendrabishnoi2.flyoff.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/**
 * Reference values come from formulas different from the ones [Geodesy] uses, computed in
 * Python (double precision) with R = 6,371,008.8 m unless noted:
 *
 * - "haversine": the haversine distance in its asin form, and the classic atan2 initial-course
 *   formula, both from Ed Williams' Aviation Formulary (edwilliams.org/avform147.htm).
 * - "formulary intermediate point" and "formulary XTD/ATD": the Aviation Formulary's
 *   intermediate-point, cross-track (`asin(sin d13 · sin(crs13 − crs12))`, + right of course)
 *   and along-track (`acos(cos d13 / cos xtd)`) formulas.
 * - "Vincenty WGS-84": Vincenty's inverse formula on the WGS-84 ellipsoid (Survey Review, 1975),
 *   to check that the spherical model stays within 0.5 % of the ellipsoid.
 * - "analytic": exact values on the sphere, such as a quarter meridian (πR/2) or an arc along
 *   the equator (Δλ · R).
 *
 * Airport coordinates are from OurAirports (public domain).
 */
class GeodesyTest {

    private val lhr = LatLon(51.4706, -0.461941)
    private val jfk = LatLon(40.639801, -73.7789)
    private val hnd = LatLon(35.552299, 139.779999)
    private val sfo = LatLon(37.618999, -122.375)

    private val quarterCircle = PI / 2 * Geodesy.EARTH_RADIUS_M // analytic: 10,007,557.221 m

    // --- distance ---

    @Test
    fun `earth radius is the mean radius`() {
        assertEquals(6_371_008.8, Geodesy.EARTH_RADIUS_M, 0.0)
    }

    @Test
    fun `LHR to JFK is about 5,540 km`() {
        // haversine: 5,539,651.7 m
        assertEquals(5_539_651.7, Geodesy.distanceMeters(lhr, jfk), 1.0)
        assertEquals(5_539_651.7, Geodesy.distanceMeters(jfk, lhr), 1.0)
    }

    @Test
    fun `spherical distance is within half a percent of the ellipsoid`() {
        // Vincenty WGS-84: LHR-JFK 5,554,539.9 m; HND-SFO 8,304,662.4 m.
        assertRelative(5_554_539.9, Geodesy.distanceMeters(lhr, jfk), 0.005)
        assertRelative(8_304_662.4, Geodesy.distanceMeters(hnd, sfo), 0.005)
    }

    @Test
    fun `HND to SFO across the antimeridian`() {
        // haversine: 8,285,884.3 m
        assertEquals(8_285_884.3, Geodesy.distanceMeters(hnd, sfo), 1.0)
    }

    @Test
    fun `same point is zero distance`() {
        assertEquals(0.0, Geodesy.distanceMeters(lhr, lhr), 0.0)
        assertEquals(0.0, Geodesy.distanceMeters(LatLon(90.0, 0.0), LatLon(90.0, 123.0)), 1e-6)
    }

    @Test
    fun `tiny distances keep their precision`() {
        // haversine: (51, 0) to (51, 0.00001) is 0.699773 m
        assertEquals(0.699773, Geodesy.distanceMeters(LatLon(51.0, 0.0), LatLon(51.0, 0.00001)), 1e-6)
    }

    @Test
    fun `quarter circles along the equator and a meridian`() {
        val origin = LatLon(0.0, 0.0)
        assertEquals(quarterCircle, Geodesy.distanceMeters(origin, LatLon(0.0, 90.0)), 1e-6)
        assertEquals(quarterCircle, Geodesy.distanceMeters(origin, LatLon(90.0, 0.0)), 1e-6)
        assertEquals(quarterCircle, Geodesy.distanceMeters(origin, LatLon(-90.0, 45.0)), 1e-6)
    }

    @Test
    fun `antipodal and near-antipodal distances are accurate`() {
        // analytic: πR = 20,015,114.442036 m
        assertEquals(20_015_114.442036, Geodesy.distanceMeters(LatLon(0.0, 0.0), LatLon(0.0, 180.0)), 1e-3)
        assertEquals(20_015_114.442036, Geodesy.distanceMeters(LatLon(90.0, 0.0), LatLon(-90.0, 0.0)), 1e-3)
        // analytic: 179.999° of the equator = 20,015,003.246956 m
        assertEquals(20_015_003.246956, Geodesy.distanceMeters(LatLon(0.0, 0.0), LatLon(0.0, 179.999)), 1e-3)
        // antipode identity d(p, q) = πR − d(antipode(p), q), small term by haversine: 20,015,004.936259 m
        assertEquals(20_015_004.936259, Geodesy.distanceMeters(LatLon(10.0, 20.0), LatLon(-10.0, -160.001)), 1e-3)
    }

    @Test
    fun `longitudes outside -180 to 180 mean the same meridian`() {
        assertEquals(
            Geodesy.distanceMeters(hnd, sfo),
            Geodesy.distanceMeters(hnd, LatLon(sfo.latitude, sfo.longitude + 360.0)),
            1e-6,
        )
    }

    // --- bearing ---

    @Test
    fun `LHR to JFK starts at about 288 degrees and JFK to LHR at about 51`() {
        // haversine (atan2 course): 287.9369° and 51.3525°. Vincenty WGS-84: 287.9766° and 51.3816°.
        assertEquals(287.9369, Geodesy.initialBearingDegrees(lhr, jfk), 1e-3)
        assertEquals(51.3525, Geodesy.initialBearingDegrees(jfk, lhr), 1e-3)
        assertEquals(287.9766, Geodesy.initialBearingDegrees(lhr, jfk), 0.1)
        assertEquals(51.3816, Geodesy.initialBearingDegrees(jfk, lhr), 0.1)
        // haversine (atan2 course): HND to SFO 54.5107°
        assertEquals(54.5107, Geodesy.initialBearingDegrees(hnd, sfo), 1e-3)
    }

    @Test
    fun `bearings along the equator and meridians`() {
        val origin = LatLon(0.0, 0.0)
        assertEquals(90.0, Geodesy.initialBearingDegrees(origin, LatLon(0.0, 10.0)), 1e-9)
        assertEquals(270.0, Geodesy.initialBearingDegrees(origin, LatLon(0.0, -10.0)), 1e-9)
        assertEquals(0.0, Geodesy.initialBearingDegrees(origin, LatLon(10.0, 0.0)), 1e-9)
        assertEquals(180.0, Geodesy.initialBearingDegrees(origin, LatLon(-10.0, 0.0)), 1e-9)
        assertEquals(0.0, Geodesy.initialBearingDegrees(origin, origin), 0.0)
        // From the North Pole every way is south.
        assertEquals(180.0, Geodesy.initialBearingDegrees(LatLon(90.0, 0.0), LatLon(0.0, 0.0)), 1e-9)
    }

    @Test
    fun `bearing is in 0 until 360`() {
        val due = Geodesy.initialBearingDegrees(LatLon(10.0, 20.0), LatLon(20.0, 20.0))
        assertEquals(0.0, due, 1e-9)
        for (lon in -179..180 step 7) {
            for (lat in -80..80 step 20) {
                val b = Geodesy.initialBearingDegrees(LatLon(lat.toDouble(), 0.0), LatLon(-lat / 2.0, lon.toDouble()))
                assertTrue("bearing $b", b >= 0.0 && b < 360.0)
            }
        }
    }

    // --- interpolate ---

    @Test
    fun `interpolate at the ends returns the ends`() {
        assertLatLon(lhr, Geodesy.interpolate(lhr, jfk, 0.0), 1e-9)
        assertLatLon(jfk, Geodesy.interpolate(lhr, jfk, 1.0), 1e-9)
    }

    @Test
    fun `interpolate halfway gives the great-circle midpoint`() {
        // formulary intermediate point: (52.214998, -41.307119)
        val mid = Geodesy.interpolate(lhr, jfk, 0.5)
        assertLatLon(LatLon(52.214998, -41.307119), mid, 1e-6)
        val half = Geodesy.distanceMeters(lhr, jfk) / 2
        assertEquals(half, Geodesy.distanceMeters(lhr, mid), 1e-3)
        assertEquals(half, Geodesy.distanceMeters(mid, jfk), 1e-3)
        assertEquals(0.0, Geodesy.crossTrackMeters(lhr, jfk, mid), 1e-3)
    }

    @Test
    fun `interpolate across the antimeridian`() {
        // formulary intermediate point: HND-SFO midpoint (48.480714, -172.177489)
        assertLatLon(LatLon(48.480714, -172.177489), Geodesy.interpolate(hnd, sfo, 0.5), 1e-6)
    }

    @Test
    fun `interpolate between the same point stays there`() {
        assertLatLon(lhr, Geodesy.interpolate(lhr, lhr, 0.3), 1e-12)
    }

    @Test
    fun `interpolate between antipodes still follows a great circle`() {
        val a = LatLon(0.0, 0.0)
        val b = LatLon(0.0, 180.0)
        val mid = Geodesy.interpolate(a, b, 0.5)
        assertEquals(quarterCircle, Geodesy.distanceMeters(a, mid), 1e-3)
        assertEquals(quarterCircle, Geodesy.distanceMeters(mid, b), 1e-3)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `interpolate rejects a fraction outside 0 to 1`() {
        Geodesy.interpolate(lhr, jfk, 1.5)
    }

    // --- destination ---

    @Test
    fun `destination along the equator and a meridian`() {
        val origin = LatLon(0.0, 0.0)
        assertLatLon(LatLon(0.0, 90.0), Geodesy.destination(origin, 90.0, quarterCircle), 1e-9)
        assertEquals(90.0, Geodesy.destination(origin, 0.0, quarterCircle).latitude, 1e-9)
        assertLatLon(LatLon(0.0, -90.0), Geodesy.destination(origin, 270.0, quarterCircle), 1e-9)
    }

    @Test
    fun `destination wraps the longitude at the antimeridian`() {
        // analytic: 2° of the equator = 2 × 111,195.080234 m
        assertLatLon(LatLon(0.0, -179.0), Geodesy.destination(LatLon(0.0, 179.0), 90.0, 2 * 111_195.080234), 1e-6)
    }

    @Test
    fun `destination reaches JFK from LHR`() {
        val there = Geodesy.destination(lhr, Geodesy.initialBearingDegrees(lhr, jfk), Geodesy.distanceMeters(lhr, jfk))
        assertLatLon(jfk, there, 1e-7)
        // Reference course and distance (haversine): lands within the rounding of 287.9369°.
        val reference = Geodesy.destination(lhr, 287.9369, 5_539_651.7)
        assertTrue(Geodesy.distanceMeters(reference, jfk) < 10.0)
    }

    // --- cross-track and along-track ---

    @Test
    fun `point north of a westbound track is on the right`() {
        // Westbound along the equator from 10°E to 0°. formulary XTD/ATD for (1, 5): +111,195.08 m, 555,975.40 m.
        val start = LatLon(0.0, 10.0)
        val end = LatLon(0.0, 0.0)
        assertEquals(111_195.08, Geodesy.crossTrackMeters(start, end, LatLon(1.0, 5.0)), 0.01)
        assertEquals(-111_195.08, Geodesy.crossTrackMeters(start, end, LatLon(-1.0, 5.0)), 0.01)
        assertEquals(555_975.40, Geodesy.alongTrackMeters(start, end, LatLon(1.0, 5.0)), 0.01)
    }

    @Test
    fun `cross-track and along-track on LHR to JFK`() {
        // formulary XTD/ATD: (55, -30) is 171,826.2 m right, 1,981,717.4 m along;
        // (60, -30) is 725,056.0 m right, 1,926,377.0 m along.
        assertEquals(171_826.2, Geodesy.crossTrackMeters(lhr, jfk, LatLon(55.0, -30.0)), 0.5)
        assertEquals(1_981_717.4, Geodesy.alongTrackMeters(lhr, jfk, LatLon(55.0, -30.0)), 0.5)
        assertEquals(725_056.0, Geodesy.crossTrackMeters(lhr, jfk, LatLon(60.0, -30.0)), 0.5)
        assertEquals(1_926_377.0, Geodesy.alongTrackMeters(lhr, jfk, LatLon(60.0, -30.0)), 0.5)
    }

    @Test
    fun `along-track is negative before the start and longer than the route past the end`() {
        // formulary XTD/ATD: (52, 5) is 333,075.5 m before LHR; (38, -80) is 6,136,767.6 m along.
        assertEquals(-333_075.5, Geodesy.alongTrackMeters(lhr, jfk, LatLon(52.0, 5.0)), 0.5)
        assertEquals(184_265.7, Geodesy.crossTrackMeters(lhr, jfk, LatLon(52.0, 5.0)), 0.5)
        assertEquals(6_136_767.6, Geodesy.alongTrackMeters(lhr, jfk, LatLon(38.0, -80.0)), 0.5)
        assertTrue(Geodesy.alongTrackMeters(lhr, jfk, LatLon(38.0, -80.0)) > Geodesy.distanceMeters(lhr, jfk))
    }

    @Test
    fun `the ends of the track are at zero and the route length`() {
        assertEquals(0.0, Geodesy.alongTrackMeters(lhr, jfk, lhr), 1e-6)
        assertEquals(Geodesy.distanceMeters(lhr, jfk), Geodesy.alongTrackMeters(lhr, jfk, jfk), 1e-6)
        assertEquals(0.0, Geodesy.crossTrackMeters(lhr, jfk, jfk), 1e-6)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cross-track needs a track`() {
        Geodesy.crossTrackMeters(lhr, lhr, jfk)
    }

    // --- greatCirclePoints ---

    @Test
    fun `great-circle points start and end at the airports`() {
        val points = Geodesy.greatCirclePoints(lhr, jfk)
        assertLatLon(lhr, points.first(), 1e-12)
        assertLatLon(jfk, points.last(), 1e-12)
        // 5,539.7 km in steps of at most 50 km: 111 segments, 112 points.
        assertEquals(112, points.size)
        points.zipWithNext().forEach { (a, b) -> assertTrue(Geodesy.distanceMeters(a, b) <= 50_000.0) }
        points.forEach { assertEquals(0.0, Geodesy.crossTrackMeters(lhr, jfk, it), 1e-3) }
    }

    @Test
    fun `great-circle points across the antimeridian have continuous longitudes`() {
        val points = Geodesy.greatCirclePoints(hnd, sfo)
        assertEquals(139.779999, points.first().longitude, 1e-9)
        assertEquals(-122.375 + 360.0, points.last().longitude, 1e-9)
        points.zipWithNext().forEach { (a, b) ->
            assertTrue("jump ${a.longitude} -> ${b.longitude}", abs(b.longitude - a.longitude) < 5.0)
        }
        // They still lie on the route: the 360°-shifted longitudes are the same meridians.
        points.forEach { assertEquals(0.0, Geodesy.crossTrackMeters(hnd, sfo, it), 1e-3) }
        val mid = points[points.size / 2]
        assertTrue(mid.longitude > 180.0)
    }

    @Test
    fun `great-circle points between the same point`() {
        assertEquals(listOf(lhr, lhr), Geodesy.greatCirclePoints(lhr, lhr))
    }

    @Test
    fun `great-circle points honour the segment length`() {
        assertEquals(1 + 6, Geodesy.greatCirclePoints(lhr, jfk, maxSegmentMeters = 1_000_000.0).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `great-circle points need a positive segment length`() {
        Geodesy.greatCirclePoints(lhr, jfk, maxSegmentMeters = 0.0)
    }

    private fun assertLatLon(expected: LatLon, actual: LatLon, toleranceDegrees: Double) {
        assertEquals("latitude", expected.latitude, actual.latitude, toleranceDegrees)
        assertEquals("longitude", expected.longitude, actual.longitude, toleranceDegrees)
    }

    private fun assertRelative(expected: Double, actual: Double, tolerance: Double) {
        assertTrue("$actual vs $expected", abs(actual - expected) / expected < tolerance)
    }
}
