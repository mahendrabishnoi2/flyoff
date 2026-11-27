package io.github.mahendrabishnoi2.flyoff.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class RouteFileTest {

    private val raw = RouteFileWriter.raw(RouteFileWriter.SAMPLE)
    private val data = RouteFile.read(RouteFileWriter.gzip(raw))

    private fun lookup(text: String) = data.lookup(FlightNumber.parse(text)!!)

    @Test
    fun `reads the header`() {
        assertEquals(1, data.formatVersion)
        assertEquals(1_795_754_966L, data.dateSeconds)
        assertEquals("0".repeat(40), data.source)
        assertEquals(10, data.flightCount)
        val header = RouteFile.header(ByteArrayInputStream(RouteFileWriter.gzip(raw)))
        assertEquals(RouteFile.Header(1, 1_795_754_966L, "0".repeat(40)), header)
    }

    @Test
    fun `finds a flight by IATA code`() {
        val matches = lookup("BA117")
        assertEquals(listOf("BAW117", "SHT117"), matches.map { it.callsign })
        assertEquals(listOf("EGLL", "KJFK"), matches[0].stops)
        assertEquals(RouteAirline("BAW", "BA", "British Airways"), matches[0].airline)
        assertEquals(listOf("EGLL", "EGPH"), matches[1].stops)
    }

    @Test
    fun `finds a flight by ICAO code, and only that airline`() {
        assertEquals(listOf("BAW117"), lookup("BAW117").map { it.callsign })
        assertEquals(listOf("SHT117"), lookup("SHT 117").map { it.callsign })
    }

    @Test
    fun `airlines sharing an IATA code come most flights first`() {
        assertEquals(listOf("QFA1", "QLK1"), lookup("QF1").map { it.callsign })
        // BA Shuttle has no BA1 in the data, so only British Airways answers.
        assertEquals(listOf("BAW1"), lookup("BA1").map { it.callsign })
    }

    @Test
    fun `keeps every stop in order`() {
        assertEquals(listOf("EGLL", "WSSS", "YSSY"), lookup("BA15").single().stops)
        assertEquals(listOf("YSSY", "WSSS", "EGLL", "WSSS", "YSSY"), lookup("QFA1").single().stops)
    }

    @Test
    fun `finds the first and last numbers of an airline`() {
        assertEquals(listOf("EGLL", "LTBA"), lookup("BA9999").single().stops)
        assertEquals(listOf("EGKK", "LTBA", "UKBB"), lookup("U2 8").single().stops)
        assertEquals(listOf("EGKK", "LEPA"), lookup("U21234").single().stops)
    }

    @Test
    fun `unknown airline or number finds nothing`() {
        assertTrue(lookup("BA118").isEmpty())
        assertTrue(lookup("BA0").isEmpty())
        assertTrue(lookup("ZZ9999").isEmpty())
        assertTrue(lookup("ZZZ1").isEmpty())
        // An airline without an IATA code is found by its ICAO code only.
        assertEquals(listOf("XAA5"), lookup("XAA5").map { it.callsign })
        assertNull(RouteFileWriter.SAMPLE.find { it.iata == "XA" })
    }

    @Test
    fun `refuses another format version`() {
        val v2 = RouteFileWriter.gzip(RouteFileWriter.raw(RouteFileWriter.SAMPLE, version = 2))
        val e = assertThrows(RouteFileException::class.java) { RouteFile.read(v2) }
        assertTrue(e.message, "format version 2" in e.message!!)
        assertEquals(2, RouteFile.header(ByteArrayInputStream(v2)).formatVersion)
    }

    @Test
    fun `refuses damaged files`() {
        val damaged = listOf(
            "not gzip" to raw,
            "wrong magic" to RouteFileWriter.gzip("NOPE".toByteArray() + raw.copyOfRange(4, raw.size)),
            "cut short" to RouteFileWriter.gzip(raw.copyOf(raw.size - 5)),
            "extra bytes" to RouteFileWriter.gzip(raw + byteArrayOf(0)),
            "empty" to RouteFileWriter.gzip(ByteArray(0)),
            "gzip cut short" to RouteFileWriter.gzip(raw).let { it.copyOf(it.size - 20) },
        )
        for ((what, bytes) in damaged) {
            assertThrows(what, IOException::class.java) { RouteFile.read(bytes) }
        }
    }

    @Test
    fun `refuses every single-byte truncation`() {
        for (size in 0 until raw.size) {
            assertThrows("cut at $size", IOException::class.java) { RouteFile.read(RouteFileWriter.gzip(raw.copyOf(size))) }
        }
    }

    @Test
    fun `refuses a huge count without allocating it`() {
        // Header, then an airport count of 2^40.
        val header = raw.copyOf(4 + 1 + 5 + 1 + 40)
        val huge = header + byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x20)
        assertThrows(RouteFileException::class.java) { RouteFile.read(RouteFileWriter.gzip(huge)) }
    }
}
