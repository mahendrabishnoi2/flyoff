package io.github.mahendrabishnoi2.flyoff.flight

import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class TrackLogTest {

    private val t0 = 1_767_225_600_000L // 2026-01-01T00:00:00Z
    private val point = TrackPoint(t0, LatLon(51.47, -0.4543), 10_000.0, 231.5)

    @Test
    fun `formats one comma-separated line`() {
        assertEquals("1767225600000,51.47000,-0.45430,10000.0,231.5", TrackLog.format(point))
    }

    @Test
    fun `unknown altitude and speed are empty fields`() {
        val line = TrackLog.format(TrackPoint(t0, LatLon(51.47, -0.4543), null, null))
        assertEquals("1767225600000,51.47000,-0.45430,,", line)
        assertEquals(TrackPoint(t0, LatLon(51.47, -0.4543), null, null), TrackLog.parse(line))
        assertEquals(TrackPoint(t0, LatLon(51.47, -0.4543), 10_000.0, null), TrackLog.parse("1767225600000,51.47000,-0.45430,10000.0,"))
        assertEquals(TrackPoint(t0, LatLon(51.47, -0.4543), null, 231.5), TrackLog.parse("1767225600000,51.47000,-0.45430,,231.5"))
    }

    @Test
    fun `rounds coordinates to 5 decimals and altitude and speed to 1`() {
        // 5 decimals of a degree is at most 1.1 m on the ground.
        val precise = TrackPoint(t0, LatLon(51.4706049, -0.4619414), 10_668.04, 231.46)
        assertEquals("1767225600000,51.47060,-0.46194,10668.0,231.5", TrackLog.format(precise))
    }

    @Test
    fun `round trip keeps every field within the rounding`() {
        val points = listOf(
            point,
            TrackPoint(t0 + 5_000, LatLon(-33.946111, 151.177222), 35.2, 0.0),
            TrackPoint(t0 + 10_000, LatLon(-17.755392, -177.443), -27.4, 12.24),
            TrackPoint(t0 + 15_000, LatLon(-90.0, 180.0), 0.0, 300.0),
            TrackPoint(t0 + 20_000, LatLon(48.0, 200.123456), null, 250.0),
            TrackPoint(0L, LatLon(0.0, 0.0), null, null),
        )
        for (original in points) {
            val parsed = TrackLog.parse(TrackLog.format(original))!!
            assertEquals(original.timeMillis, parsed.timeMillis)
            assertEquals(original.latLon.latitude, parsed.latLon.latitude, 5e-6)
            assertEquals(original.latLon.longitude, parsed.latLon.longitude, 5e-6)
            assertNullOrClose(original.altitudeMeters, parsed.altitudeMeters)
            assertNullOrClose(original.speedMetersPerSecond, parsed.speedMetersPerSecond)
            // Formatting a parsed point gives the same line again.
            assertEquals(TrackLog.format(parsed), TrackLog.format(TrackLog.parse(TrackLog.format(parsed))!!))
        }
    }

    @Test
    fun `the format does not depend on the locale`() {
        val saved = Locale.getDefault()
        try {
            for (locale in listOf(Locale.GERMANY, Locale.FRANCE, Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("hi-IN-u-nu-deva"))) {
                Locale.setDefault(locale)
                assertEquals("$locale", "1767225600000,51.47000,-0.45430,10000.0,231.5", TrackLog.format(point))
                assertEquals("$locale", point, TrackLog.parse("1767225600000,51.47000,-0.45430,10000.0,231.5"))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `surrounding whitespace and a carriage return are ignored`() {
        assertEquals(point, TrackLog.parse("  1767225600000,51.47000,-0.45430,10000.0,231.5\r"))
    }

    @Test
    fun `blank, malformed and half-written lines give null`() {
        val bad = listOf(
            "",
            "   ",
            "1767225600000",
            "1767225600000,51.47000",
            "1767225600000,51.47000,-0.454",
            "1767225600000,51.47000,-0.45430,10000.0",
            "1767225600000,51.47000,-0.45430,10000.0,231.5,7",
            "1767225600000,51.47000,-0.45430,10000.0,231.5,",
            ",51.47000,-0.45430,10000.0,231.5",
            "1767225600000,,-0.45430,10000.0,231.5",
            "1767225600000,51.47000,,10000.0,231.5",
            "NaN",
            "1767225600000,NaN,-0.45430,10000.0,231.5",
            "1767225600000,51.47000,Infinity,10000.0,231.5",
            "1767225600000,51.47000,-0.45430,NaN,231.5",
            "1767225600000,51.47000,-0.45430,10000.0,-Infinity",
            "abc,51.47000,-0.45430,10000.0,231.5",
            "1767225600000,51.4700x,-0.45430,10000.0,231.5",
            "1767225600000,51.47000,-0.45430,1e4,231.5",
            "1767225600000,51.47000,-0.45430,10000.0d,231.5",
            "1767225600000,0x1p4,-0.45430,10000.0,231.5",
            "1767225600000, 51.47000,-0.45430,10000.0,231.5",
            "1767225600000,+51.47000,-0.45430,10000.0,231.5",
            "1767225600000,51.,-0.45430,10000.0,231.5",
            "1767225600000,.5,-0.45430,10000.0,231.5",
            "1767225600000.5,51.47000,-0.45430,10000.0,231.5",
            "99999999999999999999,51.47000,-0.45430,10000.0,231.5",
            "1767225600000,91.00000,-0.45430,10000.0,231.5",
            "1767225600000,-90.00001,-0.45430,10000.0,231.5",
            "1767225600000;51.47000;-0.45430;10000.0;231.5",
            "1767225600000,51,47000,-0,45430,10000,0,231,5",
            "\u0000\u0000\u0000\u0000",
        )
        bad.forEach { assertNull("'$it'", TrackLog.parse(it)) }
    }

    private fun assertNullOrClose(expected: Double?, actual: Double?) {
        if (expected == null) assertNull(actual) else assertEquals(expected, actual!!, 0.05)
    }
}
