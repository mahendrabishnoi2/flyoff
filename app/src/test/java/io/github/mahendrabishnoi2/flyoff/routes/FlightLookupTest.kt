package io.github.mahendrabishnoi2.flyoff.routes

import io.github.mahendrabishnoi2.flyoff.airports.Airport
import io.github.mahendrabishnoi2.flyoff.airports.AirportSearch
import io.github.mahendrabishnoi2.flyoff.airports.AirportSize
import io.github.mahendrabishnoi2.flyoff.flight.FlightCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightLookupTest {

    private fun airport(iata: String, icao: String?) = Airport(iata, icao, "$iata Airport", "Town", "XX", 0.0, 0.0, AirportSize.LARGE, "Etc/UTC")

    // No LTBA (closed) and no UKBB, as in the bundled list; YSTW without an ICAO code.
    private val search = AirportSearch(
        listOf(
            airport("LHR", "EGLL"), airport("JFK", "KJFK"), airport("SIN", "WSSS"), airport("SYD", "YSSY"),
            airport("EDI", "EGPH"), airport("LGW", "EGKK"), airport("PMI", "LEPA"), airport("TMW", null),
        ),
    )
    private val data = RouteFile.read(RouteFileWriter.file(RouteFileWriter.SAMPLE))

    private fun lookup(text: String) = FlightLookup.of(text, data, search::byRouteCode)

    private fun found(text: String) = lookup(text) as FlightLookup.Found

    private fun legs(suggestion: RouteSuggestion) = suggestion.legs.map { it.codes }

    @Test
    fun `BA117 suggests Heathrow to JFK first, then BA Shuttle's route`() {
        val result = found("ba 117")
        assertEquals(FlightNumber("BA", 117, AirlineCodeKind.IATA), result.flight)
        assertEquals(listOf("BAW117", "SHT117"), result.suggestions.map { it.callsign })
        assertEquals(listOf(FlightCodes("LHR", "JFK")), legs(result.suggestions[0]))
        assertEquals("British Airways", result.suggestions[0].airline.name)
        assertEquals(listOf(FlightCodes("LHR", "EDI")), legs(result.suggestions[1]))
    }

    @Test
    fun `a flight with a stop offers each leg`() {
        val ba15 = found("BA15").suggestions.single()
        assertEquals(listOf(FlightCodes("LHR", "SIN"), FlightCodes("SIN", "SYD")), legs(ba15))
        assertEquals(listOf("EGLL", "WSSS", "YSSY"), ba15.stops.map { it.first })
        assertTrue(ba15.unknownStops.isEmpty())
    }

    @Test
    fun `a round trip offers each leg once`() {
        val qf1 = found("QF1").suggestions
        assertEquals(listOf("QFA1"), qf1.map { it.callsign })
        assertEquals(
            listOf(FlightCodes("SYD", "SIN"), FlightCodes("SIN", "LHR"), FlightCodes("LHR", "SIN"), FlightCodes("SIN", "SYD")),
            legs(qf1.single()),
        )
    }

    @Test
    fun `a leg with an airport outside the bundled list is left out`() {
        // QantasLink's QF1 is YSTW-YSSY: TMW has no ICAO code here, so the leg can't be resolved.
        assertEquals(listOf("QFA1"), found("QF1").suggestions.map { it.callsign })
        val result = lookup("BA9999")
        assertEquals(FlightLookup.NoKnownAirports(FlightNumber("BA", 9999, AirlineCodeKind.IATA), listOf("EGLL", "LTBA")), result)
    }

    @Test
    fun `a route keeps the legs it can still offer`() {
        // EGKK-LTBA-UKBB: neither leg has both ends in the list.
        assertTrue(lookup("U2 8") is FlightLookup.NoKnownAirports)
        val u2 = found("U21234").suggestions.single()
        assertEquals(listOf(FlightCodes("LGW", "PMI")), legs(u2))
    }

    @Test
    fun `stops outside the list are named`() {
        val partial = RouteFile.read(
            RouteFileWriter.file(listOf(RouteFileWriter.Airline("BAW", "BA", "British Airways", mapOf(7 to listOf("EGLL", "KJFK", "LTBA"))))),
        )
        val result = FlightLookup.of("BA7", partial, search::byRouteCode) as FlightLookup.Found
        assertEquals(listOf(FlightCodes("LHR", "JFK")), legs(result.suggestions.single()))
        assertEquals(listOf("LTBA"), result.suggestions.single().unknownStops)
    }

    @Test
    fun `unknown flight and other text`() {
        assertEquals(FlightLookup.NotFound(FlightNumber("ZZ", 9999, AirlineCodeKind.IATA)), lookup("ZZ9999"))
        assertEquals(FlightLookup.NotFound(FlightNumber("BA", 118, AirlineCodeKind.IATA)), lookup("BA118"))
        assertEquals(FlightLookup.NotAFlightNumber, lookup("London"))
        assertEquals(FlightLookup.NotAFlightNumber, lookup(""))
    }
}
