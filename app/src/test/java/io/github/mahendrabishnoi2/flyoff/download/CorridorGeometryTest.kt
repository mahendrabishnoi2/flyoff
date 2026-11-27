package io.github.mahendrabishnoi2.flyoff.download

import io.github.mahendrabishnoi2.flyoff.download.Routes.CDG
import io.github.mahendrabishnoi2.flyoff.download.Routes.JFK
import io.github.mahendrabishnoi2.flyoff.download.Routes.LAX
import io.github.mahendrabishnoi2.flyoff.download.Routes.LHR
import io.github.mahendrabishnoi2.flyoff.download.Routes.NRT
import io.github.mahendrabishnoi2.flyoff.download.Routes.OVER_POLE_FROM
import io.github.mahendrabishnoi2.flyoff.download.Routes.OVER_POLE_TO
import io.github.mahendrabishnoi2.flyoff.download.Routes.POLAR_80_FROM
import io.github.mahendrabishnoi2.flyoff.download.Routes.POLAR_80_TO
import io.github.mahendrabishnoi2.flyoff.download.Routes.SFO
import io.github.mahendrabishnoi2.flyoff.download.Routes.SIN
import io.github.mahendrabishnoi2.flyoff.download.Routes.SYD
import io.github.mahendrabishnoi2.flyoff.geo.Geodesy
import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CorridorGeometryTest {

    private val h = CorridorGeometry.DEFAULT_HALF_WIDTH_METERS

    /** Routes whose whole strip stays clear of the poles. */
    private val routes = listOf(LHR to JFK, JFK to LHR, SYD to LAX, LAX to SYD, NRT to SFO, JFK to SIN, POLAR_80_FROM to POLAR_80_TO, LHR to CDG)

    @Test
    fun `half-width is 175 km`() {
        assertEquals(175_000.0, h, 0.0)
    }

    @Test
    fun `LHR to JFK is one closed counter-clockwise ring within the map`() {
        val polygons = CorridorGeometry.strip(LHR, JFK)
        assertEquals(1, polygons.size)
        val ring = polygons.single()
        assertEquals(ring.first(), ring.last())
        assertTrue(signedArea(ring) > 0)
        assertValidPolygons(polygons)
    }

    @Test
    fun `every polygon is closed, counter-clockwise and within the map`() {
        for ((a, b) in routes + (OVER_POLE_FROM to OVER_POLE_TO)) {
            val polygons = CorridorGeometry.strip(a, b)
            assertValidPolygons(polygons)
            for (ring in polygons) {
                assertEquals(ring.first(), ring.last())
                assertTrue("$a-$b ring is not counter-clockwise", signedArea(ring) > 0)
            }
        }
    }

    @Test
    fun `vertices and edges stay within a few km of the true buffer`() {
        for ((a, b) in routes) {
            for (ring in CorridorGeometry.strip(a, b)) {
                for (i in 0 until ring.size - 1) {
                    val p = ring[i]
                    val q = ring[i + 1]
                    // Edges along the antimeridian cut or the map's top or bottom edge are not
                    // part of the buffer's boundary.
                    if (abs(p.longitude) == 180.0 && p.longitude == q.longitude) continue
                    if (atMapEdge(p) && atMapEdge(q) && p.latitude == q.latitude) continue
                    for (point in listOf(p, mercatorMidpoint(p, q))) {
                        val d = distanceToSegment(a, b, point)
                        assertTrue("$a-$b: $point is ${d / 1000} km from the route", d in h - 3_000.0..h + 1_000.0)
                    }
                }
            }
        }
    }

    @Test
    fun `the route and points just inside the edges are inside, points just outside are not`() {
        for ((a, b) in routes) {
            val polygons = CorridorGeometry.strip(a, b)
            val length = Geodesy.distanceMeters(a, b)
            for (i in 0..40) {
                val along = length * i / 40
                // Points beyond 85° are off the map (JFK–SIN peaks at 87.5° N).
                fun onMap(p: LatLon) = abs(p.latitude) < 85.0
                val onRoute = offsetPoint(a, b, along, 0.0)
                if (onMap(onRoute)) assertTrue("$a-$b route at $along", insideAny(polygons, onRoute))
                for (side in listOf(-1.0, 1.0)) {
                    val inside = offsetPoint(a, b, along, side * (h - 5_000.0))
                    val outside = offsetPoint(a, b, along, side * (h + 5_000.0))
                    if (onMap(inside)) assertTrue("$a-$b inside at $along", insideAny(polygons, inside))
                    if (onMap(outside)) assertFalse("$a-$b outside at $along", insideAny(polygons, outside))
                }
            }
        }
    }

    @Test
    fun `the ends at the airports are round`() {
        for ((a, b) in routes) {
            val polygons = CorridorGeometry.strip(a, b)
            for ((airport, away) in listOf(a to Geodesy.initialBearingDegrees(a, b) + 180.0, b to Geodesy.initialBearingDegrees(b, a) + 180.0)) {
                for (turn in listOf(-60.0, 0.0, 60.0)) {
                    assertTrue(insideAny(polygons, Geodesy.destination(airport, away + turn, h - 5_000.0)))
                    assertFalse(insideAny(polygons, Geodesy.destination(airport, away + turn, h + 5_000.0)))
                }
                // A round cap does not reach the square corner diagonally behind the airport.
                assertFalse(insideAny(polygons, Geodesy.destination(airport, away + 45.0, h * 1.3)))
            }
        }
    }

    @Test
    fun `routes across the antimeridian are split into pieces that do not wrap the world`() {
        for ((a, b) in listOf(SYD to LAX, LAX to SYD, NRT to SFO)) {
            val polygons = CorridorGeometry.strip(a, b)
            assertTrue("$a-$b gave ${polygons.size} polygons", polygons.size >= 2)
            for (ring in polygons) {
                val span = ring.maxOf { it.longitude } - ring.minOf { it.longitude }
                assertTrue("$a-$b piece spans $span°", span < 180.0)
            }
            assertTrue(polygons.any { ring -> ring.any { it.longitude == 180.0 } })
            assertTrue(polygons.any { ring -> ring.any { it.longitude == -180.0 } })
        }
    }

    @Test
    fun `a segment has square ends at its cut points`() {
        val polygons = CorridorGeometry.strip(LHR, JFK, fromMeters = 1_000_000.0, toMeters = 2_000_000.0)
        assertEquals(1, polygons.size)
        for (along in listOf(1_001_000.0, 1_500_000.0, 1_999_000.0)) {
            for (side in listOf(-h + 5_000.0, 0.0, h - 5_000.0)) {
                assertTrue("inside at $along, $side", insideAny(polygons, offsetPoint(LHR, JFK, along, side)))
            }
        }
        for (along in listOf(990_000.0, 2_010_000.0)) {
            for (side in listOf(-h + 5_000.0, 0.0, h - 5_000.0)) {
                assertFalse("outside at $along, $side", insideAny(polygons, offsetPoint(LHR, JFK, along, side)))
            }
        }
    }

    @Test
    fun `a segment can run to the destination and keep its round cap`() {
        val length = Geodesy.distanceMeters(LHR, JFK)
        val polygons = CorridorGeometry.strip(LHR, JFK, fromMeters = length - 500_000.0, toMeters = length)
        val beyond = Geodesy.destination(JFK, Geodesy.initialBearingDegrees(JFK, LHR) + 180.0, h - 5_000.0)
        assertTrue(insideAny(polygons, beyond))
        assertFalse(insideAny(polygons, offsetPoint(LHR, JFK, length - 520_000.0, 0.0)))
    }

    @Test
    fun `a route over the North Pole gives a valid strip that covers the top of the map`() {
        val polygons = CorridorGeometry.strip(OVER_POLE_FROM, OVER_POLE_TO)
        assertValidPolygons(polygons)
        // Along the route below the mercator limit: inside.
        val length = Geodesy.distanceMeters(OVER_POLE_FROM, OVER_POLE_TO)
        for (i in 0..40) {
            val p = offsetPoint(OVER_POLE_FROM, OVER_POLE_TO, length * i / 40, 0.0)
            if (p.latitude < 85.0) assertTrue("route point $p", insideAny(polygons, p))
        }
        // The strip contains the pole: its pieces reach the top edge of the map and together span
        // every longitude, as the polar cap does once it is projected.
        assertTrue(polygons.any { ring -> ring.any { atMapEdge(it) } })
        assertEquals(-180.0, polygons.minOf { ring -> ring.minOf { it.longitude } }, 0.0)
        assertEquals(180.0, polygons.maxOf { ring -> ring.maxOf { it.longitude } }, 0.0)
        // 667 km from the route (the 90° E meridian at 84° N): outside.
        assertFalse(insideAny(polygons, LatLon(84.0, 90.0)))
        assertFalse(insideAny(polygons, LatLon(84.0, -90.0)))
    }

    @Test
    fun `a route to the same airport is a circle`() {
        val polygons = CorridorGeometry.strip(LHR, LHR)
        assertValidPolygons(polygons)
        for (bearing in 0 until 360 step 15) {
            assertTrue(insideAny(polygons, Geodesy.destination(LHR, bearing.toDouble(), h - 5_000.0)))
            assertFalse(insideAny(polygons, Geodesy.destination(LHR, bearing.toDouble(), h + 5_000.0)))
        }
    }

    @Test
    fun `a narrower strip is narrower`() {
        val polygons = CorridorGeometry.strip(LHR, JFK, halfWidthMeters = 50_000.0)
        assertTrue(insideAny(polygons, offsetPoint(LHR, JFK, 2_000_000.0, 45_000.0)))
        assertFalse(insideAny(polygons, offsetPoint(LHR, JFK, 2_000_000.0, 55_000.0)))
    }

    @Test
    fun `bad arguments are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { CorridorGeometry.strip(LHR, JFK, halfWidthMeters = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { CorridorGeometry.strip(LHR, JFK, halfWidthMeters = 2_000_000.0) }
        assertThrows(IllegalArgumentException::class.java) { CorridorGeometry.strip(LHR, JFK, fromMeters = 2e6, toMeters = 1e6) }
        assertThrows(IllegalArgumentException::class.java) { CorridorGeometry.strip(LHR, JFK, fromMeters = -1.0, toMeters = 1e6) }
        assertThrows(IllegalArgumentException::class.java) { CorridorGeometry.strip(LHR, JFK, fromMeters = 0.0, toMeters = 6e6) }
        // Antipodal: no single great circle joins them.
        assertThrows(IllegalArgumentException::class.java) { CorridorGeometry.strip(LatLon(10.0, 20.0), LatLon(-10.0, -160.0)) }
    }

    private fun atMapEdge(p: LatLon) = abs(p.latitude) > WebMercator.MAX_LATITUDE - 1e-9

    private fun assertValidPolygons(polygons: List<List<LatLon>>) {
        assertTrue(polygons.isNotEmpty())
        for (ring in polygons) {
            assertTrue("ring has ${ring.size} points", ring.size >= 4)
            for (p in ring) {
                assertTrue("longitude ${p.longitude}", p.longitude in -180.0..180.0)
                assertTrue("latitude ${p.latitude}", abs(p.latitude) <= WebMercator.MAX_LATITUDE)
            }
        }
    }
}
