package io.github.mahendrabishnoi2.flyoff.routes

import java.util.Locale

/** Which kind of airline code a flight number was typed with. */
enum class AirlineCodeKind {
    /** Two letters or a letter and a digit, as on a ticket: BA, U2, 9W. Several airlines may share one. */
    IATA,

    /** Three letters, as in a callsign: BAW. */
    ICAO,
}

/**
 * A flight number as a passenger types it: an airline code and a number of up to four digits.
 * Leading zeros don't count (BA0117 is BA117), as in the callsigns of the route data.
 */
data class FlightNumber(val airline: String, val number: Int, val kind: AirlineCodeKind) {

    /** The usual way to write it: "BA117". */
    val text: String get() = "$airline$number"

    companion object {
        private const val MAX_NUMBER = 9999
        private val icao = Regex("([A-Z]{3})([0-9]{1,5})")
        private val iata = Regex("([A-Z0-9]{2})([0-9]{1,5})")

        /**
         * The flight number in [text], or null when it isn't one. Case, spaces and hyphens are
         * ignored: "ba 117", "BA-0117" and "BA117" are all BA117. Three letters make an ICAO code
         * (BAW117); two characters with at least one letter an IATA code (BA117, U2 1234, 9W7).
         * A letter after the number (BA117A) isn't accepted: such flights can't be looked up.
         */
        fun parse(text: String): FlightNumber? {
            val plain = text.filterNot { it.isWhitespace() || it == '-' }.uppercase(Locale.ROOT)
            icao.matchEntire(plain)?.let { return build(it, AirlineCodeKind.ICAO) }
            val m = iata.matchEntire(plain) ?: return null
            if (m.groupValues[1].none { it in 'A'..'Z' }) return null
            return build(m, AirlineCodeKind.IATA)
        }

        private fun build(m: MatchResult, kind: AirlineCodeKind): FlightNumber? {
            val number = m.groupValues[2].toInt()
            return if (number > MAX_NUMBER) null else FlightNumber(m.groupValues[1], number, kind)
        }
    }
}
