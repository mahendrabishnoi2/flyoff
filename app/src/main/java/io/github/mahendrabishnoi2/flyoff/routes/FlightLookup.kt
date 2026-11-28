package io.github.mahendrabishnoi2.flyoff.routes

import io.github.mahendrabishnoi2.flyoff.airports.Airport
import io.github.mahendrabishnoi2.flyoff.flight.FlightCodes

/** One leg of a suggested route, between two bundled airports. */
data class RouteLeg(val origin: Airport, val destination: Airport) {
    val codes: FlightCodes get() = FlightCodes(origin.iata, destination.iata)
}

/** A route the data suggests for a flight number, with its stops resolved to bundled airports. */
data class RouteSuggestion(
    val airline: RouteAirline,
    val callsign: String,
    /** Every stop in the order flown: the data's code, and the bundled airport (null if none). */
    val stops: List<Pair<String, Airport?>>,
    /**
     * The legs Flyoff can set up: consecutive stops that are both bundled airports, each leg
     * once. One for a direct flight; for a flight with stops, the user picks theirs.
     */
    val legs: List<RouteLeg>,
) {
    /** The stops that aren't bundled airports: their legs can't be suggested. */
    val unknownStops: List<String> get() = stops.filter { it.second == null }.map { it.first }.distinct()
}

/** What a flight number finds. Every result is a suggestion: the data is crowd-sourced. */
sealed interface FlightLookup {
    /** The text isn't a flight number. */
    data object NotAFlightNumber : FlightLookup

    /** No route for this flight number. */
    data class NotFound(val flight: FlightNumber) : FlightLookup

    /** Routes exist, but none has a leg between two bundled airports. */
    data class NoKnownAirports(val flight: FlightNumber, val stops: List<String>) : FlightLookup

    /** Routes with at least one leg to suggest, the likelier airline first. */
    data class Found(val flight: FlightNumber, val suggestions: List<RouteSuggestion>) : FlightLookup

    companion object {
        /**
         * Looks up the flight number in [text] in [data]; [airport] resolves the data's airport
         * codes to bundled airports (see `AirportSearch.byRouteCode`).
         */
        fun of(text: String, data: RouteData, airport: (String) -> Airport?): FlightLookup {
            val flight = FlightNumber.parse(text) ?: return NotAFlightNumber
            val matches = data.lookup(flight)
            if (matches.isEmpty()) return NotFound(flight)
            val suggestions = matches.map { suggest(it, airport) }.filter { it.legs.isNotEmpty() }
            return if (suggestions.isEmpty()) {
                NoKnownAirports(flight, matches.flatMap { it.stops }.distinct())
            } else {
                Found(flight, suggestions)
            }
        }

        private fun suggest(match: RouteMatch, airport: (String) -> Airport?): RouteSuggestion {
            val stops = match.stops.map { it to airport(it) }
            val legs = stops.zipWithNext()
                .mapNotNull { (from, to) ->
                    val origin = from.second ?: return@mapNotNull null
                    val destination = to.second ?: return@mapNotNull null
                    if (origin.iata == destination.iata) null else RouteLeg(origin, destination)
                }
                .distinctBy { it.origin.iata to it.destination.iata }
            return RouteSuggestion(match.airline, match.callsign, stops, legs)
        }
    }
}
