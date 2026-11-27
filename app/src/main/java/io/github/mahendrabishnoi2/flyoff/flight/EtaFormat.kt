package io.github.mahendrabishnoi2.flyoff.flight

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The arrival time [etaMillis] as 24-hour local time in the destination's IANA zone [zoneId],
 * with [locale]'s short zone name: "14:05 EST", "00:30 IST +1".
 *
 * When the arrival's local date differs from today's local date in the same zone (today being
 * [nowMillis]), the difference in days is appended: " +1", " +2", or " −1" (U+2212 MINUS SIGN)
 * for an estimate already in the past.
 *
 * Where a locale has no short name for a zone, java.time writes a GMT offset instead, such as
 * "GMT-05:00". Android uses its own ICU zone names, so that text can differ slightly from the
 * JVM's.
 */
fun formatEta(etaMillis: Long, nowMillis: Long, zoneId: String, locale: Locale): String {
    val zone = ZoneId.of(zoneId)
    val arrival = Instant.ofEpochMilli(etaMillis).atZone(zone)
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val time = DateTimeFormatter.ofPattern("HH:mm z", locale).format(arrival)
    val days = ChronoUnit.DAYS.between(today, arrival.toLocalDate())
    return when {
        days > 0 -> "$time +$days"
        days < 0 -> "$time −${-days}"
        else -> time
    }
}
