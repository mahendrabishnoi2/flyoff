package io.github.mahendrabishnoi2.flyoff.routes

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Writes small routes files for tests, in the format of `tools/routes/build_routes.py` (whose
 * output the bundled-file test reads, so the two are checked against each other).
 */
object RouteFileWriter {

    class Airline(val code: String, val iata: String, val name: String, val flights: Map<Int, List<String>>)

    fun raw(airlines: List<Airline>, date: Long = 1_795_754_966, version: Int = RouteFile.FORMAT_VERSION, source: String = "0".repeat(40)): ByteArray {
        val routes = airlines.flatMap { it.flights.values }.map { it.joinToString("-") }.distinct()
        val airports = airlines.flatMap { a -> a.flights.values.flatten() }.distinct()
        val out = ByteArrayOutputStream()
        out.write("FLYR".toByteArray())
        out.varint(version.toLong())
        out.varint(date)
        out.string(source)
        out.varint(airports.size.toLong())
        airports.forEach { out.string(it) }
        out.varint(routes.size.toLong())
        for (route in routes) {
            val stops = route.split("-")
            out.varint(stops.size.toLong())
            stops.forEach { out.varint(airports.indexOf(it).toLong()) }
        }
        val sorted = airlines.sortedBy { it.code }
        out.varint(sorted.size.toLong())
        for (a in sorted) {
            out.string(a.code)
            out.string(a.iata)
            out.string(a.name)
            out.varint(a.flights.size.toLong())
        }
        for (a in sorted) {
            var previous = -1
            for (n in a.flights.keys.sorted()) {
                out.varint((n - previous - 1).toLong())
                previous = n
            }
        }
        for (a in sorted) {
            for (n in a.flights.keys.sorted()) out.varint(routes.indexOf(a.flights.getValue(n).joinToString("-")).toLong())
        }
        out.varint(sorted.sumOf { it.flights.size }.toLong())
        return out.toByteArray()
    }

    fun gzip(raw: ByteArray): ByteArray = ByteArrayOutputStream().also { bytes -> GZIPOutputStream(bytes).use { it.write(raw) } }.toByteArray()

    fun file(airlines: List<Airline>, date: Long = 1_795_754_966): ByteArray = gzip(raw(airlines, date))

    private fun ByteArrayOutputStream.varint(value: Long) {
        var n = value
        while (n >= 0x80) {
            write(((n and 0x7F) or 0x80).toInt())
            n = n ushr 7
        }
        write(n.toInt())
    }

    private fun ByteArrayOutputStream.string(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        varint(bytes.size.toLong())
        write(bytes)
    }

    /** British Airways and BA Shuttle share "BA"; easyJet is U2; QantasLink shares QF with Qantas. */
    val SAMPLE = listOf(
        Airline("BAW", "BA", "British Airways", mapOf(1 to listOf("EGLL", "KJFK"), 15 to listOf("EGLL", "WSSS", "YSSY"), 117 to listOf("EGLL", "KJFK"), 9999 to listOf("EGLL", "LTBA"))),
        Airline("SHT", "BA", "BA Shuttle", mapOf(117 to listOf("EGLL", "EGPH"))),
        Airline("EZY", "U2", "easyJet", mapOf(1234 to listOf("EGKK", "LEPA"), 8 to listOf("EGKK", "LTBA", "UKBB"))),
        Airline("QFA", "QF", "Qantas", mapOf(1 to listOf("YSSY", "WSSS", "EGLL", "WSSS", "YSSY"))),
        Airline("QLK", "QF", "QantasLink", mapOf(1 to listOf("YSTW", "YSSY"))),
        Airline("XAA", "", "No IATA Air", mapOf(5 to listOf("KJFK", "LHR"))),
    )
}
