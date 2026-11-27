package io.github.mahendrabishnoi2.flyoff.airports

/**
 * Reads the bundled airport list, `assets/airports/airports.tsv`, built by
 * `tools/airports/build_airports.py` (which documents the format).
 *
 * The file is UTF-8 text: a header line, then one airport per line with the
 * tab-separated fields `iata icao name city country lat lon size tz`. `icao`
 * and `city` may be empty; `size` is L, M, S or O. Fields never contain tabs or
 * line breaks, so there is no quoting or escaping.
 */
object AirportFile {

    private const val HEADER = "iata\ticao\tname\tcity\tcountry\tlat\tlon\tsize\ttz"
    private const val FIELDS = 9

    private val iataPattern = Regex("[A-Z]{3}")
    private val icaoPattern = Regex("[A-Z0-9]{4}")
    private val sizes = mapOf(
        "L" to AirportSize.LARGE,
        "M" to AirportSize.MEDIUM,
        "S" to AirportSize.SMALL,
        "O" to AirportSize.OTHER,
    )

    /** Parses the whole file. Throws [IllegalArgumentException] naming the bad line. */
    fun parse(text: String): List<Airport> {
        val lines = text.split('\n').map { it.removeSuffix("\r") }
        require(lines.first() == HEADER) { "airports.tsv: the first line must be the header '$HEADER'" }
        val body = if (lines.last().isEmpty()) lines.subList(1, lines.size - 1) else lines.drop(1)
        val seen = HashSet<String>(body.size * 2)
        return body.mapIndexed { index, line ->
            val lineNumber = index + 2
            val airport = parseLine(line) { problem -> "airports.tsv line $lineNumber: $problem" }
            require(seen.add(airport.iata)) { "airports.tsv line $lineNumber: duplicate IATA code ${airport.iata}" }
            airport
        }
    }

    private inline fun parseLine(line: String, error: (String) -> String): Airport {
        val f = line.split('\t')
        require(f.size == FIELDS) { error("${f.size} fields, expected $FIELDS") }
        val (iata, icao, name, city, country) = f
        require(iataPattern.matches(iata)) { error("bad IATA code '$iata'") }
        require(icao.isEmpty() || icaoPattern.matches(icao)) { error("bad ICAO code '$icao'") }
        require(name.isNotBlank()) { error("empty name") }
        val latitude = f[5].toDoubleOrNull()
        require(latitude != null && latitude in -90.0..90.0) { error("bad latitude '${f[5]}'") }
        val longitude = f[6].toDoubleOrNull()
        require(longitude != null && longitude in -180.0..180.0) { error("bad longitude '${f[6]}'") }
        val size = sizes[f[7]]
        require(size != null) { error("bad size '${f[7]}', expected one of ${sizes.keys}") }
        require(f[8].isNotBlank()) { error("empty time zone") }
        return Airport(
            iata = iata,
            icao = icao.ifEmpty { null },
            name = name,
            city = city,
            countryCode = country,
            latitude = latitude,
            longitude = longitude,
            size = size,
            timeZone = f[8],
        )
    }
}
