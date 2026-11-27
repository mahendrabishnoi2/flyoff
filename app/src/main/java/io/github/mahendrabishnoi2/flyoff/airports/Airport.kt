package io.github.mahendrabishnoi2.flyoff.airports

/** An airport with scheduled passenger service, from the bundled list. */
data class Airport(
    val iata: String,
    val icao: String?,
    val name: String,
    val city: String,
    val countryCode: String,
    val latitude: Double,
    val longitude: Double,
    val size: AirportSize,
    /** IANA time zone id, e.g. "Europe/London". */
    val timeZone: String,
)

/** OurAirports' airport type, used to rank search results: bigger airports first. */
enum class AirportSize { LARGE, MEDIUM, SMALL, OTHER }
