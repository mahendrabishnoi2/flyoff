package io.github.mahendrabishnoi2.flyoff.routes

import io.github.mahendrabishnoi2.flyoff.airports.AirportFile
import io.github.mahendrabishnoi2.flyoff.airports.AirportSearch
import io.github.mahendrabishnoi2.flyoff.flight.FlightCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The routes file bundled in the APK, read straight from the assets folder with the bundled
 * airports: what the app suggests. The expected routes are what VRS has in the pinned snapshot,
 * not checked against airline schedules.
 */
class BundledRoutesTest {

    private companion object {
        // Gradle runs unit tests with the module directory (app/) as the working directory.
        val bytes = File("src/main/assets/routes/routes.bin").readBytes()
        val data = RouteFile.read(bytes)
        val search = AirportSearch(AirportFile.parse(File("src/main/assets/airports/airports.tsv").readText()))
    }

    private fun lookup(text: String) = FlightLookup.of(text, data, search::byRouteCode)

    private fun legs(text: String) = (lookup(text) as FlightLookup.Found).suggestions.first().legs.map { it.codes }

    @Test
    fun `is the pinned VRS snapshot in format 1`() {
        assertEquals(RouteFile.FORMAT_VERSION, data.formatVersion)
        assertEquals("bd5f5d5af341ed9d75e1758e4a6bb740e8247f93", data.source)
        assertTrue("${data.flightCount} flights", data.flightCount in 300_000..600_000)
        assertTrue("${bytes.size} bytes", bytes.size < 3_000_000)
    }

    @Test
    fun `BA117 suggests London Heathrow to New York JFK`() {
        val found = lookup("BA117") as FlightLookup.Found
        val first = found.suggestions.first()
        assertEquals("BAW117", first.callsign)
        assertEquals("British Airways", first.airline.name)
        assertEquals(listOf(FlightCodes("LHR", "JFK")), first.legs.map { it.codes })
        assertEquals("London Heathrow Airport", first.legs.single().origin.name)
        // Typed differently, the same route.
        for (text in listOf("ba 117", "BA0117", "BAW117", "baw 117")) assertEquals(text, listOf(FlightCodes("LHR", "JFK")), legs(text))
    }

    @Test
    fun `well-known flights`() {
        assertEquals(listOf(FlightCodes("JFK", "LHR")), legs("AA100"))
        assertEquals(listOf(FlightCodes("FRA", "JFK")), legs("LH400"))
        assertEquals(listOf(FlightCodes("DXB", "LHR")), legs("EK1"))
        assertEquals(listOf(FlightCodes("SIN", "EWR")), legs("SQ22"))
        assertEquals(listOf(FlightCodes("MXP", "LGW")), legs("U2 8001"))
        assertEquals(listOf(FlightCodes("BOM", "LHR")), legs("6E1"))
    }

    @Test
    fun `BA15 has a stop, so two legs to pick from`() {
        assertEquals(listOf(FlightCodes("LHR", "SIN"), FlightCodes("SIN", "SYD")), legs("BA15"))
    }

    @Test
    fun `an unknown flight finds nothing`() {
        assertEquals(FlightLookup.NotFound(FlightNumber("ZZ", 9999, AirlineCodeKind.IATA)), lookup("ZZ9999"))
    }

    /**
     * Loading must not hold up the UI (it runs off the main thread, once). Prints the time on
     * this machine; a phone is a few times slower. The bound is loose, for slow CI runners.
     */
    @Test
    fun `loads quickly`() {
        repeat(3) { RouteFile.read(bytes) } // warm up the JIT
        val times = (1..5).map {
            val start = System.nanoTime()
            RouteFile.read(bytes)
            (System.nanoTime() - start) / 1_000_000.0
        }
        val start = System.nanoTime()
        repeat(1000) { FlightLookup.of("BA117", data, search::byRouteCode) }
        val lookupMicros = (System.nanoTime() - start) / 1000.0 / 1000
        println("routes.bin: ${bytes.size} bytes, ${data.flightCount} flights; load ms ${times.joinToString { "%.0f".format(it) }}; lookup %.1f µs".format(lookupMicros))
        assertTrue("load took ${times.min()} ms", times.min() < 1000)
    }
}
