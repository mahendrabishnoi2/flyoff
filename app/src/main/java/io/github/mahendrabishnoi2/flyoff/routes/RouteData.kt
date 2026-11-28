package io.github.mahendrabishnoi2.flyoff.routes

/** An airline in the route data. */
data class RouteAirline(
    /** The callsign prefix: the ICAO code, or the IATA code for an airline without one. */
    val code: String,
    /** The IATA code, as on a ticket; null when the airline has none. */
    val iata: String?,
    val name: String,
)

/** A callsign the data has a route for: its airline and the airports flown, in order. */
data class RouteMatch(
    val airline: RouteAirline,
    /** The callsign, such as BAW117. */
    val callsign: String,
    /** The data's airport codes, in the order flown: ICAO codes, or IATA codes for a few. */
    val stops: List<String>,
)

/**
 * The flight routes from a routes file ([RouteFile]), kept compactly in arrays: about 390,000
 * flights take a few megabytes. Lookups are binary searches, cheap enough for every keystroke.
 */
class RouteData internal constructor(
    val formatVersion: Int,
    /** The data's date: when its VRS snapshot was taken, in Unix seconds. */
    val dateSeconds: Long,
    /** The VRS commit the data was built from. */
    val source: String,
    private val airports: Array<String>,
    /** Route i's stops are routeStops[routeStarts[i] until routeStarts[i + 1]]. */
    private val routeStarts: IntArray,
    private val routeStops: IntArray,
    private val airlines: Array<RouteAirline>,
    /** Airline i's flights are flightStarts[i] until flightStarts[i + 1], by number. */
    private val flightStarts: IntArray,
    private val numbers: ShortArray,
    private val flightRoutes: IntArray,
) {
    val flightCount: Int get() = numbers.size

    private val byCode = HashMap<String, Int>(airlines.size * 2).apply {
        airlines.forEachIndexed { i, airline -> put(airline.code, i) }
    }

    /** IATA code -> airlines sharing it, the one with most flights first (the likelier one). */
    private val byIata: Map<String, List<Int>> = airlines.indices
        .filter { airlines[it].iata != null }
        .groupBy { airlines[it].iata!! }
        .mapValues { (_, list) -> list.sortedWith(compareBy({ -flightsOf(it) }, { airlines[it].code })) }

    private fun flightsOf(airline: Int) = flightStarts[airline + 1] - flightStarts[airline]

    /**
     * The routes for [flight]: for an IATA code, one per airline with that code that has the
     * number (several airlines may share an IATA code); for an ICAO code, at most one.
     */
    fun lookup(flight: FlightNumber): List<RouteMatch> {
        val candidates = when (flight.kind) {
            AirlineCodeKind.IATA -> byIata[flight.airline].orEmpty()
            AirlineCodeKind.ICAO -> listOfNotNull(byCode[flight.airline])
        }
        return candidates.mapNotNull { airline ->
            val index = java.util.Arrays.binarySearch(numbers, flightStarts[airline], flightStarts[airline + 1], flight.number.toShort())
            if (index < 0) return@mapNotNull null
            val route = flightRoutes[index]
            val stops = (routeStarts[route] until routeStarts[route + 1]).map { airports[routeStops[it]] }
            RouteMatch(airlines[airline], "${airlines[airline].code}${flight.number}", stops)
        }
    }
}
