package io.github.mahendrabishnoi2.flyoff.flight

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsGapsTest {

    @Test
    fun `gaps run from 30 to 40 and from 65 to 72 percent, start inclusive and end exclusive`() {
        val inside = listOf(30.0, 35.0, 39.999, 65.0, 68.0, 71.999)
        val outside = listOf(0.0, 10.0, 29.999, 40.0, 50.0, 64.999, 72.0, 90.0, 100.0)
        inside.forEach { assertTrue("$it of 100", GpsGaps.inGap(it, 100.0)) }
        outside.forEach { assertFalse("$it of 100", GpsGaps.inGap(it, 100.0)) }
    }

    @Test
    fun `gaps scale with the flight's duration`() {
        val sevenHours = 7 * 3600.0 // 30 % = 7,560 s, 40 % = 10,080 s
        assertFalse(GpsGaps.inGap(7_559.9, sevenHours))
        assertTrue(GpsGaps.inGap(7_560.0, sevenHours))
        assertTrue(GpsGaps.inGap(10_079.9, sevenHours))
        assertFalse(GpsGaps.inGap(10_080.0, sevenHours))
    }

    @Test
    fun `no gaps before take-off or after landing`() {
        assertFalse(GpsGaps.inGap(-1.0, 100.0))
        assertFalse(GpsGaps.inGap(150.0, 100.0))
    }

    @Test
    fun `no gaps without a positive duration`() {
        assertFalse(GpsGaps.inGap(0.0, 0.0))
        assertFalse(GpsGaps.inGap(35.0, 0.0))
        assertFalse(GpsGaps.inGap(-35.0, -100.0))
        assertFalse(GpsGaps.inGap(35.0, Double.NaN))
        assertFalse(GpsGaps.inGap(Double.NaN, 100.0))
    }
}
