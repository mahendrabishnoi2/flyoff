package io.github.mahendrabishnoi2.flyoff.flight

import io.github.mahendrabishnoi2.flyoff.airports.Airport
import io.github.mahendrabishnoi2.flyoff.geo.Geodesy
import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import java.util.Locale

/** One end of a flight. */
enum class Endpoint { ORIGIN, DESTINATION }

/** A chosen flight as two different IATA codes: what is stored, and what survives a restart. */
data class FlightCodes(val origin: String, val destination: String) {
    init {
        require(origin != destination) { "origin and destination are both $origin" }
    }

    /** The airports for these codes, or null if [lookup] doesn't know one of them. */
    fun resolve(lookup: (String) -> Airport?): FlightPlan? {
        val from = lookup(origin) ?: return null
        val to = lookup(destination) ?: return null
        return FlightPlan(from, to)
    }

    companion object {
        private val IATA = Regex("[A-Z]{3}")

        /** Codes read back from storage, or null when missing or not a valid pair. */
        fun parse(origin: String?, destination: String?): FlightCodes? {
            val from = origin?.trim()?.uppercase(Locale.ROOT) ?: return null
            val to = destination?.trim()?.uppercase(Locale.ROOT) ?: return null
            if (!IATA.matches(from) || !IATA.matches(to) || from == to) return null
            return FlightCodes(from, to)
        }
    }
}

/**
 * The flight being set up: either end may still be empty. The two ends are never the same
 * airport; [pick] refuses that.
 */
data class FlightDraft(val origin: String? = null, val destination: String? = null) {

    /** The draft with [iata] at [end], or null when it is already the other end's airport. */
    fun pick(end: Endpoint, iata: String): FlightDraft? = when (end) {
        Endpoint.ORIGIN -> if (iata == destination) null else copy(origin = iata)
        Endpoint.DESTINATION -> if (iata == origin) null else copy(destination = iata)
    }

    fun cleared(end: Endpoint): FlightDraft = when (end) {
        Endpoint.ORIGIN -> copy(origin = null)
        Endpoint.DESTINATION -> copy(destination = null)
    }

    fun swapped(): FlightDraft = FlightDraft(origin = destination, destination = origin)

    /** The finished flight, or null while an end is missing. */
    fun toCodes(): FlightCodes? {
        val from = origin ?: return null
        val to = destination ?: return null
        return if (from == to) null else FlightCodes(from, to)
    }
}

/** A flight between two airports, with what the map and the stats need. */
data class FlightPlan(val origin: Airport, val destination: Airport) {
    val codes: FlightCodes get() = FlightCodes(origin.iata, destination.iata)

    val originLatLon = LatLon(origin.latitude, origin.longitude)
    val destinationLatLon = LatLon(destination.latitude, destination.longitude)

    val routeLengthMeters: Double = Geodesy.distanceMeters(originLatLon, destinationLatLon)

    /** The great circle for drawing; longitudes are unwrapped (see [Geodesy.greatCirclePoints]). */
    val routePoints: List<LatLon> by lazy { Geodesy.greatCirclePoints(originLatLon, destinationLatLon) }

    val simulatedFlight: SimulatedFlight by lazy { SimulatedFlight(originLatLon, destinationLatLon) }
}
