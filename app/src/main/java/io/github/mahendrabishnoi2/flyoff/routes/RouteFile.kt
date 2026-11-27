package io.github.mahendrabishnoi2.flyoff.routes

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** A routes file that can't be read: damaged, cut short, or not one at all. */
class RouteFileException(message: String) : IOException(message)

/**
 * Reads a routes file, as built by `tools/routes/build_routes.py` (which documents the format):
 * a gzip stream of "FLYR", the format version, the data's date, the VRS commit, the airports,
 * the routes, the airlines, then each airline's flight numbers and their routes.
 *
 * Every count and index is checked, so a damaged file fails with [RouteFileException] (or the
 * gzip stream's own [IOException]) and never with a crash or a huge allocation.
 */
object RouteFile {

    const val FORMAT_VERSION: Int = 1

    private val MAGIC = byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'Y'.code.toByte(), 'R'.code.toByte())
    private const val MAX_NUMBER = 9999

    /** The start of a routes file: enough to choose between two files without reading either. */
    data class Header(val formatVersion: Int, val dateSeconds: Long, val source: String)

    /** The header of the gzip-compressed routes file [input]; reads only its first bytes. */
    fun header(input: InputStream): Header {
        val start = GZIPInputStream(input).use { it.readNBytesCompat(HEADER_BYTES) }
        val reader = Reader(start)
        return readHeader(reader)
    }

    /** The whole gzip-compressed routes file [gzipped]. */
    fun read(gzipped: ByteArray): RouteData = read(ByteArrayInputStream(gzipped))

    /** The whole gzip-compressed routes file read from [input] (not closed). */
    fun read(input: InputStream): RouteData {
        val raw = GZIPInputStream(input, 1 shl 16).readBytes()
        return parse(raw)
    }

    /** A routes file's bytes after gzip. */
    fun parse(raw: ByteArray): RouteData {
        val r = Reader(raw)
        val header = readHeader(r)
        if (header.formatVersion != FORMAT_VERSION) fail("format version ${header.formatVersion}, expected $FORMAT_VERSION")

        val airports = Array(r.count()) { r.string() }
        val routeCount = r.count()
        val routeStarts = IntArray(routeCount + 1)
        val stops = IntList()
        for (i in 0 until routeCount) {
            val n = r.count()
            if (n < 2) fail("route $i has $n stops")
            repeat(n) { stops.add(r.index(airports.size, "airport")) }
            routeStarts[i + 1] = stops.size
        }

        val airlineCount = r.count()
        val airlines = arrayOfNulls<RouteAirline>(airlineCount)
        val flightStarts = IntArray(airlineCount + 1)
        for (i in 0 until airlineCount) {
            val code = r.string()
            val iata = r.string()
            val name = r.string()
            airlines[i] = RouteAirline(code, iata.ifEmpty { null }, name)
            flightStarts[i + 1] = flightStarts[i] + r.count()
            if (flightStarts[i + 1] > r.remaining) fail("${flightStarts[i + 1]} flights in ${r.remaining} bytes")
        }
        val flightCount = flightStarts[airlineCount]
        val numbers = ShortArray(flightCount)
        for (i in 0 until airlineCount) {
            var number = -1
            for (f in flightStarts[i] until flightStarts[i + 1]) {
                val gap = r.varint()
                if (gap > MAX_NUMBER) fail("flight number gap $gap")
                number += gap.toInt() + 1
                if (number > MAX_NUMBER) fail("flight number $number")
                numbers[f] = number.toShort()
            }
        }
        val flightRoutes = IntArray(flightCount) { r.index(routeCount, "route") }
        if (r.varint() != flightCount.toLong()) fail("flight count check failed")
        if (r.remaining != 0) fail("${r.remaining} stray bytes at the end")

        @Suppress("UNCHECKED_CAST")
        return RouteData(
            formatVersion = header.formatVersion,
            dateSeconds = header.dateSeconds,
            source = header.source,
            airports = airports,
            routeStarts = routeStarts,
            routeStops = stops.toArray(),
            airlines = airlines as Array<RouteAirline>,
            flightStarts = flightStarts,
            numbers = numbers,
            flightRoutes = flightRoutes,
        )
    }

    /** Magic, version, date and source take well under this. */
    private const val HEADER_BYTES = 256

    private fun readHeader(r: Reader): Header {
        val magic = r.bytes(MAGIC.size)
        if (!magic.contentEquals(MAGIC)) fail("not a Flyoff routes file")
        val version = r.varint()
        val date = r.varint()
        val source = r.string()
        if (version > Int.MAX_VALUE) fail("bad format version")
        return Header(version.toInt(), date, source)
    }

    private fun fail(message: String): Nothing = throw RouteFileException("routes file: $message")

    /** A cursor over the uncompressed bytes. */
    private class Reader(private val data: ByteArray) {
        private var pos = 0

        val remaining: Int get() = data.size - pos

        fun bytes(n: Int): ByteArray {
            if (n < 0 || n > remaining) fail("ends early")
            return data.copyOfRange(pos, pos + n).also { pos += n }
        }

        fun varint(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                if (pos >= data.size) fail("ends early")
                val b = data[pos++].toInt() and 0xFF
                value = value or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) return value
                shift += 7
                if (shift > 56) fail("varint too long")
            }
        }

        /** A count of things that each take at least one byte, so never more than what is left. */
        fun count(): Int {
            val n = varint()
            if (n > remaining) fail("count $n with $remaining bytes left")
            return n.toInt()
        }

        fun index(size: Int, what: String): Int {
            val i = varint()
            if (i >= size) fail("$what index $i of $size")
            return i.toInt()
        }

        fun string(): String = String(bytes(count()), Charsets.UTF_8)
    }

    /** A growable IntArray, so the route stops aren't boxed while reading. */
    private class IntList {
        private var array = IntArray(1 shl 16)
        var size = 0
            private set

        fun add(value: Int) {
            if (size == array.size) array = array.copyOf(size * 2)
            array[size++] = value
        }

        fun toArray(): IntArray = array.copyOf(size)
    }

    /** InputStream.readNBytes is Android 13+ (API 33); minSdk is 26. */
    private fun InputStream.readNBytesCompat(n: Int): ByteArray {
        val buffer = ByteArray(n)
        var read = 0
        while (read < n) {
            val got = read(buffer, read, n - read)
            if (got < 0) break
            read += got
        }
        return buffer.copyOf(read)
    }
}
