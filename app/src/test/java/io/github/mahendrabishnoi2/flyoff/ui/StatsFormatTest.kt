package io.github.mahendrabishnoi2.flyoff.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class StatsFormatTest {

    private val us = Locale.US
    private val germany = Locale.GERMANY

    @Test
    fun `unknown values show a dash`() {
        assertEquals(UNKNOWN, formatDistance(null, us))
        assertEquals(UNKNOWN, formatDistance(Double.NaN, us))
        assertEquals(UNKNOWN, formatDuration(null))
        assertEquals(UNKNOWN, formatSpeed(null, us))
        assertEquals(UNKNOWN, formatAltitude(null, us))
        assertEquals(UNKNOWN, formatHeading(null, us))
        assertEquals(UNKNOWN, formatProgress(null, us))
        assertEquals("—", UNKNOWN)
    }

    @Test
    fun `long distances are whole kilometres with grouping`() {
        assertEquals("5,540 km", formatDistance(5_539_700.0, us))
        assertEquals("5.540 km", formatDistance(5_539_700.0, germany))
        assertEquals("12 km", formatDistance(12_400.0, us))
    }

    @Test
    fun `short distances keep one decimal`() {
        assertEquals("9.5 km", formatDistance(9_460.0, us))
        assertEquals("0.0 km", formatDistance(0.0, us))
        assertEquals("0.4 km", formatDistance(420.0, us))
        assertEquals("9,5 km", formatDistance(9_460.0, germany))
    }

    @Test
    fun `a distance that rounds up to 10 km drops the decimal`() {
        assertEquals("10 km", formatDistance(9_960.0, us))
    }

    @Test
    fun `negative distances count as zero`() {
        assertEquals("0.0 km", formatDistance(-3.0, us))
    }

    @Test
    fun `durations are hours and minutes`() {
        assertEquals("0 min", formatDuration(0))
        assertEquals("< 1 min", formatDuration(29))
        assertEquals("1 min", formatDuration(60))
        assertEquals("42 min", formatDuration(42 * 60 + 10))
        assertEquals("1 h 00 min", formatDuration(3_600))
        assertEquals("6 h 05 min", formatDuration(6 * 3_600 + 5 * 60 + 20))
        assertEquals("26 h 10 min", formatDuration(26 * 3_600 + 10 * 60))
    }

    @Test
    fun `durations round to the nearest minute`() {
        assertEquals("2 min", formatDuration(90))
        assertEquals("1 h 00 min", formatDuration(3_599))
    }

    @Test
    fun `negative durations are unknown`() {
        assertEquals(UNKNOWN, formatDuration(-1))
    }

    @Test
    fun `speed is in kilometres per hour`() {
        assertEquals("900 km/h", formatSpeed(250.0, us))
        assertEquals("1,080 km/h", formatSpeed(300.0, us))
        assertEquals("0 km/h", formatSpeed(0.0, us))
    }

    @Test
    fun `altitude is in whole metres`() {
        assertEquals("10,668 m", formatAltitude(10_668.2, us))
        assertEquals("-12 m", formatAltitude(-12.0, us))
    }

    @Test
    fun `compass points cover the circle`() {
        assertEquals("N", compassPoint(0.0))
        assertEquals("N", compassPoint(22.4))
        assertEquals("NE", compassPoint(22.5))
        assertEquals("E", compassPoint(90.0))
        assertEquals("SE", compassPoint(135.0))
        assertEquals("S", compassPoint(180.0))
        assertEquals("SW", compassPoint(225.0))
        assertEquals("W", compassPoint(274.0))
        assertEquals("NW", compassPoint(315.0))
        assertEquals("N", compassPoint(359.9))
        assertEquals("W", compassPoint(-90.0))
        assertEquals("E", compassPoint(450.0))
    }

    @Test
    fun `heading shows degrees and a compass point`() {
        assertEquals("274° W", formatHeading(274.3, us))
        assertEquals("0° N", formatHeading(359.6, us))
        assertEquals("45° NE", formatHeading(45.0, us))
        assertEquals("270° W", formatHeading(-90.0, us))
    }

    @Test
    fun `progress rounds down so 100 percent means arrived`() {
        assertEquals("99%", formatProgress(0.9966, us))
        assertEquals("99%", formatProgress(0.999999, us))
        assertEquals("42%", formatProgress(0.429, us))
        assertEquals("100%", formatProgress(1.0, us))
        assertEquals("0%", formatProgress(-0.01, us))
        assertEquals("100%", formatProgress(1.2, us))
    }

    @Test
    fun `stat columns shrink with the font scale`() {
        assertEquals(3, statColumns(widthDp = 348f, fontScale = 1f))
        assertEquals(4, statColumns(widthDp = 416f, fontScale = 1f))
        assertEquals(6, statColumns(widthDp = 900f, fontScale = 1f))
        assertEquals(2, statColumns(widthDp = 348f, fontScale = 1.5f))
        // Never fewer than two: the ETA spans two cells, so it then takes a whole row.
        assertEquals(2, statColumns(widthDp = 348f, fontScale = 2f))
        assertEquals(2, statColumns(widthDp = 120f, fontScale = 1f))
    }

    @Test
    fun `progress is a whole percentage`() {
        assertEquals("42%", formatProgress(0.42, us))
        assertEquals("0%", formatProgress(0.0, us))
        assertEquals("100%", formatProgress(1.0, us))
    }

    @Test
    fun `eta uses the destination's zone`() {
        val now = ZonedDateTime.of(2026, 1, 15, 10, 0, 0, 0, ZoneId.of("Europe/London")).toInstant().toEpochMilli()
        val eta = now + (7 * 3_600 + 50 * 60) * 1000L
        val text = formatEtaOrUtc(eta, now, "America/New_York", us)
        assertEquals("12:50 EST", text.text)
        assertFalse(text.inUtc)
    }

    @Test
    fun `eta falls back to UTC for a zone this device doesn't know`() {
        val now = ZonedDateTime.of(2026, 1, 15, 10, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val eta = now + 3_600_000L
        for (zone in listOf("Mars/Olympus_Mons", "not a zone", "")) {
            val text = formatEtaOrUtc(eta, now, zone, us)
            assertEquals("zone '$zone'", "11:00 UTC", text.text)
            assertTrue("zone '$zone'", text.inUtc)
        }
    }

    @Test
    fun `unknown eta shows a dash`() {
        val text = formatEtaOrUtc(null, 0L, "Europe/London", us)
        assertEquals(UNKNOWN, text.text)
        assertFalse(text.inUtc)
    }

    @Test
    fun `download estimates round up to whole megabytes`() {
        assertEquals("34 MB", formatUpToMegabytes(33_000_001, us))
        assertEquals("33 MB", formatUpToMegabytes(33_000_000, us))
        assertEquals("1 MB", formatUpToMegabytes(0, us))
        assertEquals("1,200 MB", formatUpToMegabytes(1_200_000_000, us))
    }

    @Test
    fun `stored sizes have one decimal`() {
        assertEquals("12.3 MB", formatMegabytes(12_345_678, us))
        assertEquals("12,3 MB", formatMegabytes(12_345_678, germany))
        assertEquals("0.0 MB", formatMegabytes(0, us))
    }
}
