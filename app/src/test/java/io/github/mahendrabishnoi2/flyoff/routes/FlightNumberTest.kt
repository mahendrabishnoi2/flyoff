package io.github.mahendrabishnoi2.flyoff.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlightNumberTest {

    private fun iata(code: String, number: Int) = FlightNumber(code, number, AirlineCodeKind.IATA)

    private fun icao(code: String, number: Int) = FlightNumber(code, number, AirlineCodeKind.ICAO)

    @Test
    fun `reads what passengers type`() {
        assertEquals(iata("BA", 117), FlightNumber.parse("BA117"))
        assertEquals(iata("BA", 117), FlightNumber.parse("ba 117"))
        assertEquals(iata("BA", 117), FlightNumber.parse(" BA  117 "))
        assertEquals(iata("BA", 117), FlightNumber.parse("BA-117"))
        assertEquals(iata("BA", 117), FlightNumber.parse("BA0117"))
        assertEquals(iata("BA", 1), FlightNumber.parse("BA 001"))
    }

    @Test
    fun `reads ICAO callsigns`() {
        assertEquals(icao("BAW", 117), FlightNumber.parse("BAW117"))
        assertEquals(icao("EZY", 1234), FlightNumber.parse("ezy 1234"))
    }

    @Test
    fun `reads IATA codes with a digit`() {
        assertEquals(iata("U2", 1234), FlightNumber.parse("U2 1234"))
        assertEquals(iata("U2", 1234), FlightNumber.parse("U21234"))
        assertEquals(iata("9W", 7), FlightNumber.parse("9W7"))
        assertEquals(iata("6E", 1), FlightNumber.parse("6e 1"))
        assertEquals(iata("A3", 600), FlightNumber.parse("A3600"))
    }

    @Test
    fun `refuses what is not a flight number`() {
        for (text in listOf("", "  ", "BA", "117", "12 345", "BA 12345", "BA117A", "BAWW117", "BA.117", "ÄB117", "BA 1 1 7x")) {
            assertNull(text, FlightNumber.parse(text))
        }
    }

    @Test
    fun `numbers stop at 9999`() {
        assertEquals(iata("BA", 9999), FlightNumber.parse("BA9999"))
        assertNull(FlightNumber.parse("BA10000"))
        assertEquals(iata("BA", 9999), FlightNumber.parse("BA09999"))
    }

    @Test
    fun `shows the usual way to write it`() {
        assertEquals("BA117", FlightNumber.parse("ba 0117")!!.text)
        assertEquals("BAW117", FlightNumber.parse("BAW 117")!!.text)
    }
}
