package io.github.mahendrabishnoi2.flyoff.airports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/** The airport list bundled in the APK, read straight from the assets folder. */
class BundledAirportsTest {

    private companion object {
        // Gradle runs unit tests with the module directory (app/) as the working directory.
        val airports = AirportFile.parse(File("src/main/assets/airports/airports.tsv").readText())
        val search = AirportSearch(airports)
    }

    @Test
    fun `holds the scheduled-service airports`() {
        assertTrue("${airports.size} airports", airports.size in 4000..4500)
    }

    @Test
    fun `looks up Heathrow and JFK`() {
        val lhr = search.byIata("lhr")!!
        assertEquals("London Heathrow Airport", lhr.name)
        assertEquals("EGLL", lhr.icao)
        assertEquals("GB", lhr.countryCode)
        assertEquals("Europe/London", lhr.timeZone)
        assertEquals(51.47, lhr.latitude, 0.01)
        assertEquals(-0.46, lhr.longitude, 0.01)

        val jfk = search.byIata("JFK")!!
        assertEquals("New York", jfk.city)
        assertEquals("America/New_York", jfk.timeZone)
        assertEquals(AirportSize.LARGE, jfk.size)
    }

    @Test
    fun `london finds Heathrow first`() {
        assertEquals("LHR", search.search("london").first().iata)
    }

    @Test
    fun `everyday queries find the expected airports`() {
        assertEquals("JFK", search.search("jfk").first().iata)
        assertEquals("LHR", search.search("EGLL").first().iata)
        assertEquals("ZRH", search.search("zurich").first().iata)
        assertEquals("LHR", search.search("heathrow").first().iata)
        val newYork = search.search("new york").map { it.iata }
        assertTrue(newYork.toString(), newYork.take(2).containsAll(listOf("JFK", "LGA")))
        val saoPaulo = search.search("sao paulo").map { it.iata }
        assertTrue(saoPaulo.toString(), saoPaulo.take(2).containsAll(listOf("GRU", "CGH")))
    }

    @Test
    fun `every time zone is a zone the JVM knows`() {
        val unknown = airports.map { it.timeZone }.toSet().filter { runCatching { ZoneId.of(it) }.isFailure }
        assertEquals(emptyList<String>(), unknown)
    }

    @Test
    fun `search is fast enough for every keystroke`() {
        val queries = listOf("l", "lo", "lon", "lond", "london", "n", "new", "new y", "new york", "a", "s", "sao p", "zur")
        repeat(3) { queries.forEach { search.search(it) } } // warm up
        val start = System.nanoTime()
        repeat(10) { queries.forEach { search.search(it) } }
        val perQueryMs = (System.nanoTime() - start) / 1e6 / (10 * queries.size)
        println("Airport search: %.3f ms per query".format(perQueryMs))
        // Generous bound for slow CI machines; a phone is slower than a desktop JVM.
        assertTrue("%.2f ms per query".format(perQueryMs), perQueryMs < 20)
    }
}
