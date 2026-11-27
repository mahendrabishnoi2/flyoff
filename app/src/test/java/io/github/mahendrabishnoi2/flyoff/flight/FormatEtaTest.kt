package io.github.mahendrabishnoi2.flyoff.flight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale

/**
 * Expected local times come from the IANA tz rules: in 2026 US daylight saving time starts on
 * Sunday 8 March at 02:00 EST (07:00 UTC) and ends on Sunday 1 November at 02:00 EDT
 * (06:00 UTC); India is UTC+5:30 all year with no daylight saving time.
 */
class FormatEtaTest {

    private fun ms(utc: String) = Instant.parse(utc).toEpochMilli()

    private fun eta(arrivalUtc: String, nowUtc: String = arrivalUtc, zone: String, locale: Locale = Locale.US) =
        formatEta(ms(arrivalUtc), ms(nowUtc), zone, locale)

    @Test
    fun `spring forward in New York`() {
        assertEquals("01:59 EST", eta("2026-03-08T06:59:00Z", zone = "America/New_York"))
        assertEquals("03:00 EDT", eta("2026-03-08T07:00:00Z", zone = "America/New_York"))
    }

    @Test
    fun `fall back in New York`() {
        assertEquals("01:59 EDT", eta("2026-11-01T05:59:00Z", zone = "America/New_York"))
        assertEquals("01:00 EST", eta("2026-11-01T06:00:00Z", zone = "America/New_York"))
    }

    @Test
    fun `half-hour offset in Kolkata`() {
        assertEquals("17:30 IST", eta("2026-11-27T12:00:00Z", zone = "Asia/Kolkata"))
    }

    @Test
    fun `arrival on the next local day`() {
        // Now: 17:30 on 27 Nov in Kolkata. Arrival: 00:30 on 28 Nov.
        assertEquals("00:30 IST +1", eta("2026-11-27T19:00:00Z", "2026-11-27T12:00:00Z", "Asia/Kolkata"))
    }

    @Test
    fun `arrival two local days later`() {
        // Now: 17:30 on 27 Nov in Kolkata. Arrival: 05:30 on 29 Nov.
        assertEquals("05:30 IST +2", eta("2026-11-29T00:00:00Z", "2026-11-27T12:00:00Z", "Asia/Kolkata"))
    }

    @Test
    fun `arrival on the previous local day`() {
        // Now: 05:30 on 28 Nov in Kolkata. Arrival: 17:30 on 27 Nov. The sign is U+2212 MINUS SIGN.
        assertEquals("17:30 IST −1", eta("2026-11-27T12:00:00Z", "2026-11-28T00:00:00Z", "Asia/Kolkata"))
    }

    @Test
    fun `the day is compared in the destination zone not in UTC`() {
        // The UTC dates differ (27 and 28 Nov) but in New York both are 27 Nov.
        assertEquals("22:00 EST", eta("2026-11-28T03:00:00Z", "2026-11-27T23:00:00Z", "America/New_York"))
        // The UTC dates match (27 Nov) but in Kolkata 23:00Z is already 28 Nov.
        assertEquals("04:30 IST +1", eta("2026-11-27T23:00:00Z", "2026-11-27T12:00:00Z", "Asia/Kolkata"))
    }

    @Test
    fun `zone names follow the locale`() {
        // British English has no "EST" abbreviation for New York, so java.time falls back to a
        // GMT offset ("GMT-05:00" on the JDK; Android's ICU data may write it differently).
        val british = eta("2026-01-15T12:00:00Z", zone = "America/New_York", locale = Locale.UK)
        assertTrue(british, british.startsWith("07:00 GMT"))
        assertEquals("07:00 EST", eta("2026-01-15T12:00:00Z", zone = "America/New_York"))
    }
}
