package io.github.mahendrabishnoi2.flyoff.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class UnwrapLongitudesTest {

    private fun longitudes(points: List<LatLon>) = points.map { it.longitude }

    @Test
    fun `empty and single-point lists are unchanged`() {
        assertEquals(emptyList<LatLon>(), unwrapLongitudes(emptyList()))
        assertEquals(listOf(LatLon(10.0, 190.0)), unwrapLongitudes(listOf(LatLon(10.0, 190.0))))
    }

    @Test
    fun `a track that does not cross the antimeridian is unchanged`() {
        val lhrToJfk = listOf(LatLon(51.47, -0.46), LatLon(53.0, -20.0), LatLon(52.2, -41.3), LatLon(40.64, -73.78))
        assertEquals(lhrToJfk, unwrapLongitudes(lhrToJfk))
        val greenwich = listOf(LatLon(51.0, -1.0), LatLon(51.0, 0.0), LatLon(51.0, 1.0), LatLon(51.0, -0.5))
        assertEquals(greenwich, unwrapLongitudes(greenwich))
    }

    @Test
    fun `eastbound across the Pacific keeps counting past 180`() {
        val points = listOf(LatLon(35.0, 170.0), LatLon(40.0, 179.0), LatLon(45.0, -175.0), LatLon(48.0, -160.0))
        val unwrapped = unwrapLongitudes(points)
        assertEquals(listOf(170.0, 179.0, 185.0, 200.0), longitudes(unwrapped))
        assertEquals(points.map { it.latitude }, unwrapped.map { it.latitude })
    }

    @Test
    fun `westbound across the Pacific keeps counting past -180`() {
        val points = listOf(LatLon(48.0, -160.0), LatLon(45.0, -175.0), LatLon(40.0, 179.0), LatLon(35.0, 170.0))
        assertEquals(listOf(-160.0, -175.0, -181.0, -190.0), longitudes(unwrapLongitudes(points)))
    }

    @Test
    fun `the first point is kept as given and the rest follow it`() {
        val points = listOf(LatLon(0.0, 190.0), LatLon(0.0, -165.0), LatLon(0.0, 175.0))
        assertEquals(listOf(190.0, 195.0, 175.0), longitudes(unwrapLongitudes(points)))
    }

    @Test
    fun `going round the world keeps counting`() {
        val points = listOf(0.0, 120.0, -120.0, 0.0, 120.0).map { LatLon(0.0, it) }
        assertEquals(listOf(0.0, 120.0, 240.0, 360.0, 480.0), longitudes(unwrapLongitudes(points)))
    }

    @Test
    fun `far-off longitudes unwrap in one step`() {
        // 1e15 + 30 is 310° modulo 360, so it lands at -50, within 180° of 0.
        val points = listOf(LatLon(0.0, 0.0), LatLon(0.0, 1e15 + 30.0), LatLon(0.0, -1e15))
        val unwrapped = longitudes(unwrapLongitudes(points))
        assertEquals(-50.0, unwrapped[1], 1e-9)
        assertTrue("${unwrapped[2]}", abs(unwrapped[2] - unwrapped[1]) <= 180.0)
    }

    @Test
    fun `consecutive points are never more than 180 degrees apart and keep their meridian`() {
        val points = listOf(170.0, -170.0, 179.9, -179.9, 0.0, 180.0, -180.0, 540.0, -45.0).map { LatLon(10.0, it) }
        val unwrapped = unwrapLongitudes(points)
        unwrapped.zipWithNext().forEach { (a, b) ->
            assertTrue("${a.longitude} -> ${b.longitude}", abs(b.longitude - a.longitude) <= 180.0)
        }
        points.zip(unwrapped).forEach { (original, moved) ->
            val turns = (moved.longitude - original.longitude) / 360.0
            assertEquals("${original.longitude} -> ${moved.longitude}", Math.round(turns).toDouble(), turns, 1e-12)
        }
    }
}
