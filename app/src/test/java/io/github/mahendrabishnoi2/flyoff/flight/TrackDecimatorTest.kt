package io.github.mahendrabishnoi2.flyoff.flight

import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TrackDecimatorTest {

    private val t0 = 1_767_225_600_000L

    private fun at(offsetMillis: Long) = TrackPoint(t0 + offsetMillis, LatLon(51.0, 0.0), 10_000.0, 230.0)

    /** The offsets [decimator] keeps from [offsets], fed in order. */
    private fun kept(decimator: TrackDecimator, vararg offsets: Long) = offsets.filter { decimator.accept(at(it)) }

    @Test
    fun `keeps the first point`() {
        assertEquals(listOf(0L), kept(TrackDecimator(), 0))
    }

    @Test
    fun `keeps one point per interval, measured from the last kept point`() {
        // 3,000 and 4,499 are too soon after 0; 6,000 is 6 s after 0; 9,000 and 10,499 are under 4.5 s after 6,000.
        assertEquals(listOf(0L, 6_000L, 10_500L), kept(TrackDecimator(), 0, 3_000, 4_499, 6_000, 9_000, 10_499, 10_500))
    }

    @Test
    fun `90 percent of the interval is kept and a repeated time is not`() {
        assertEquals(listOf(0L, 4_500L, 9_000L), kept(TrackDecimator(), 0, 0, 4_500, 4_500, 8_999, 9_000))
    }

    @Test
    fun `fixes every 5 seconds that arrive up to 10 percent early are all kept`() {
        // The screen-off request is every 5 s, and Android may deliver each fix up to 0.5 s early.
        val offsets = longArrayOf(0, 4_600, 9_100, 14_000, 18_500, 23_400)
        assertEquals(offsets.toList(), kept(TrackDecimator(), *offsets))
    }

    @Test
    fun `one fix a second gives one point every 5 seconds`() {
        val decimator = TrackDecimator()
        val keptCount = (0 until 3_600).count { decimator.accept(at(it * 1_000L)) }
        assertEquals(720, keptCount)
    }

    @Test
    fun `time going backwards restarts from that point`() {
        // 2,000 went back (a clock change or a new run): kept, and the interval restarts from it.
        assertEquals(listOf(0L, 5_000L, 2_000L, 7_000L), kept(TrackDecimator(), 0, 5_000, 2_000, 4_000, 6_499, 7_000))
        // A jump far into the past works the same way.
        assertEquals(listOf(0L, -3_600_000L, -3_595_000L), kept(TrackDecimator(), 0, -3_600_000, -3_599_000, -3_595_000))
    }

    @Test
    fun `reset forgets the last kept point`() {
        val decimator = TrackDecimator()
        assertEquals(listOf(0L), kept(decimator, 0, 1_000))
        decimator.reset()
        assertEquals(listOf(2_000L, 7_000L), kept(decimator, 2_000, 3_000, 7_000))
    }

    @Test
    fun `a custom interval`() {
        assertEquals(listOf(0L, 900L, 2_500L), kept(TrackDecimator(intervalMillis = 1_000), 0, 500, 899, 900, 1_799, 2_500))
        assertEquals(listOf(0L, 0L, 1L), kept(TrackDecimator(intervalMillis = 0), 0, 0, 1))
    }

    @Test
    fun `rejects a negative interval`() {
        assertThrows(IllegalArgumentException::class.java) { TrackDecimator(intervalMillis = -1) }
    }
}
