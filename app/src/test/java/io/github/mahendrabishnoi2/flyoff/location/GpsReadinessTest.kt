package io.github.mahendrabishnoi2.flyoff.location

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class GpsReadinessTest {

    private val en = Locale.US

    @Test
    fun noFixIsSearching() {
        assertEquals(GpsReadiness.Searching(4), gpsReadiness(null, null, Satellites(4, 0)))
        assertEquals(GpsReadiness.Searching(null), gpsReadiness(null, 5f, null))
    }

    @Test
    fun aFixOlderThanTenSecondsIsSearching() {
        assertEquals(GpsReadiness.Searching(9), gpsReadiness(10_000, 5f, Satellites(9, 7)))
        assertEquals(GpsReadiness.Ready(5f, 7), gpsReadiness(9_999, 5f, Satellites(9, 7)))
    }

    @Test
    fun aNegativeAgeIsNotAFreshFix() {
        assertEquals(GpsReadiness.Searching(null), gpsReadiness(-1, 5f, null))
    }

    @Test
    fun accuracyDecidesReadyOrWeak() {
        assertEquals(GpsReadiness.Ready(50f, null), gpsReadiness(0, 50f, null))
        assertEquals(GpsReadiness.Weak(50.5f, 3), gpsReadiness(0, 50.5f, Satellites(8, 3)))
        // No accuracy reported: the fix itself is the best evidence there is.
        assertEquals(GpsReadiness.Ready(null, 9), gpsReadiness(0, null, Satellites(12, 9)))
    }

    @Test
    fun zeroSatellitesUsedIsLeftOut() {
        assertEquals(GpsReadiness.Ready(6f, null), gpsReadiness(0, 6f, Satellites(3, 0)))
    }

    @Test
    fun wording() {
        assertEquals("Searching for GPS…", GpsReadiness.Searching(null).text(en))
        assertEquals("Searching for GPS…", GpsReadiness.Searching(0).text(en))
        assertEquals("Searching for GPS · 1 satellite in view", GpsReadiness.Searching(1).text(en))
        assertEquals("Searching for GPS · 4 satellites in view", GpsReadiness.Searching(4).text(en))
        assertEquals("GPS ready · ±6 m · 9 satellites", GpsReadiness.Ready(6.4f, 9).text(en))
        assertEquals("GPS ready · ±6 m", GpsReadiness.Ready(5.5f, null).text(en))
        assertEquals("GPS ready · 1 satellite", GpsReadiness.Ready(null, 1).text(en))
        assertEquals("GPS ready", GpsReadiness.Ready(null, null).text(en))
        assertEquals("Weak GPS signal · ±1,200 m · 3 satellites", GpsReadiness.Weak(1200f, 3).text(en))
    }

    @Test
    fun numbersFollowTheLocale() {
        assertEquals("Weak GPS signal · ±1.200 m", GpsReadiness.Weak(1200f, null).text(Locale.GERMANY))
    }
}
