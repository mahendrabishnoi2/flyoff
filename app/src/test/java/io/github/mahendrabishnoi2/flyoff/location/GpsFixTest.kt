package io.github.mahendrabishnoi2.flyoff.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class GpsFixTest {

    private val cruise = GpsFix(
        accuracyMeters = 4.6f,
        altitudeMeters = 10_668.4,
        speedMetersPerSecond = 233.9f, // 842.04 km/h, a typical airliner ground speed
        hasBearing = true,
    )

    @Test
    fun `converts metres per second to km per hour`() {
        assertEquals(3.6f, metersPerSecondToKmh(1f), 1e-6f)
        assertEquals(842.04f, metersPerSecondToKmh(233.9f), 0.01f)
    }

    @Test
    fun `summary shows accuracy, altitude and speed`() {
        assertEquals("±5 m · GPS alt 10,668 m · 842 km/h", cruise.summary(Locale.US))
    }

    @Test
    fun `summary uses the locale's digit grouping`() {
        assertEquals("±5 m · GPS alt 10.668 m · 842 km/h", cruise.summary(Locale.GERMANY))
    }

    @Test
    fun `summary leaves out values the receiver did not report`() {
        val fix = GpsFix(accuracyMeters = 12f, altitudeMeters = null, speedMetersPerSecond = null, hasBearing = false)
        assertEquals("±12 m", fix.summary(Locale.US))
        assertEquals("", GpsFix(null, null, null, false).summary(Locale.US))
    }

    @Test
    fun `bearing arrow only when moving with a course`() {
        assertTrue(cruise.showsBearing())
        assertTrue(cruise.copy(speedMetersPerSecond = MOVING_SPEED_METERS_PER_SECOND).showsBearing())
        assertFalse(cruise.copy(speedMetersPerSecond = 0.5f).showsBearing())
        assertFalse(cruise.copy(speedMetersPerSecond = null).showsBearing())
        assertFalse(cruise.copy(hasBearing = false).showsBearing())
    }
}
