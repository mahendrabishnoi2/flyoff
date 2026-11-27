package io.github.mahendrabishnoi2.flyoff.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LatLonTest {

    @Test
    fun `accepts the full latitude range and normal longitudes`() {
        LatLon(90.0, 180.0)
        LatLon(-90.0, -180.0)
        assertEquals(51.47, LatLon(51.47, -0.46).latitude, 0.0)
    }

    @Test
    fun `accepts unwrapped longitudes beyond 180`() {
        // greatCirclePoints returns these for routes across the antimeridian.
        assertEquals(237.625, LatLon(37.619, 237.625).longitude, 0.0)
        assertEquals(-200.0, LatLon(0.0, -200.0).longitude, 0.0)
    }

    @Test
    fun `rejects latitudes beyond the poles`() {
        assertThrows(IllegalArgumentException::class.java) { LatLon(90.000001, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { LatLon(-91.0, 0.0) }
    }

    @Test
    fun `rejects values that are not numbers`() {
        assertThrows(IllegalArgumentException::class.java) { LatLon(Double.NaN, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { LatLon(0.0, Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { LatLon(0.0, Double.POSITIVE_INFINITY) }
    }
}
