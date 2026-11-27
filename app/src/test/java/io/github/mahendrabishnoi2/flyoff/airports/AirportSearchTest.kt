package io.github.mahendrabishnoi2.flyoff.airports

import io.github.mahendrabishnoi2.flyoff.airports.AirportSize.LARGE
import io.github.mahendrabishnoi2.flyoff.airports.AirportSize.MEDIUM
import io.github.mahendrabishnoi2.flyoff.airports.AirportSize.SMALL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AirportSearchTest {

    private fun airport(
        iata: String,
        name: String,
        city: String,
        size: AirportSize = LARGE,
        icao: String? = null,
        keywords: List<String> = emptyList(),
    ) = Airport(iata, icao, name, city, "XX", 0.0, 0.0, size, "Etc/UTC", keywords)

    // In the order of the bundled file: within a size, the biggest airport comes first.
    private val airports = listOf(
        airport("LHR", "London Heathrow Airport", "London", icao = "EGLL", keywords = listOf("LON", "Londres")),
        airport("LCY", "London City Airport", "London", MEDIUM, "EGLC", listOf("LON")),
        airport("LGW", "London Gatwick Airport", "London", icao = "EGKK", keywords = listOf("LON", "Crawley")),
        airport("YXU", "London International Airport", "London", MEDIUM, "CYXU"),
        airport("ELS", "King Phalo Airport", "East London", icao = "FAEL"),
        // Matched by "london" only through keywords: a keyword prefix, then a later keyword word.
        airport("OXF", "Oxford Airport", "Kidlington", MEDIUM, "EGTK", listOf("London Oxford Airport")),
        airport("XGL", "Rochford Field", "Rochford", SMALL, keywords = listOf("RAF Rochford", "Greater London")),
        airport("JFK", "John F. Kennedy International Airport", "New York", icao = "KJFK",
            keywords = listOf("Manhattan", "New York City", "NYC", "Idlewild")),
        airport("LGA", "LaGuardia Airport", "New York", icao = "KLGA", keywords = listOf("Manhattan", "New York City", "NYC")),
        airport("EWR", "Newark Liberty International Airport", "Newark", icao = "KEWR",
            keywords = listOf("Manhattan", "New York City", "NYC")),
        airport("SWF", "New York Stewart International Airport", "Newburgh", MEDIUM, "KSWF"),
        airport("HND", "Tokyo Haneda International Airport", "Tokyo", icao = "RJTT", keywords = listOf("TYO")),
        airport("NRT", "Narita International Airport", "Narita", icao = "RJAA",
            keywords = listOf("TYO", "Tokyo", "New Tokyo International Airport")),
        airport("BOM", "Chhatrapati Shivaji Maharaj International Airport", "Mumbai", icao = "VABB",
            keywords = listOf("Bombay", "Sahar International Airport")),
        airport("ZRH", "Zürich Airport", "Zurich", icao = "LSZH"),
        airport("GRU", "São Paulo/Guarulhos–Governor André Franco Montoro International Airport", "São Paulo", icao = "SBGR"),
        airport("CGH", "Congonhas–Deputado Freitas Nobre Airport", "São Paulo", icao = "SBSP"),
        airport("DUS", "Düsseldorf Airport", "Düsseldorf", icao = "EDDL"),
        airport("ORD", "Chicago O'Hare International Airport", "Chicago", icao = "KORD"),
        airport("LIH", "Līhuʻe Airport", "Līhuʻe", MEDIUM, "PHLI"),
        airport("DEL", "Indira Gandhi International Airport", "New Delhi", icao = "VIDP"),
        airport("DRT", "Del Rio International Airport", "Del Rio", SMALL, "KDRT"),
        airport("AGT", "Guaraní International Airport", "Ciudad del Este", MEDIUM, "SGES"),
        airport("VIE", "Vienna International Airport", "Vienna", icao = "LOWW"),
        airport("XLW", "Lowwood Airstrip", "Lowwood", SMALL),
    )
    private val search = AirportSearch(airports)

    private fun codes(query: String, limit: Int = 20) = search.search(query, limit).map { it.iata }

    @Test
    fun `blank queries find nothing`() {
        assertEquals(emptyList<String>(), codes(""))
        assertEquals(emptyList<String>(), codes("   "))
        assertEquals(emptyList<String>(), codes(" - / "))
    }

    @Test
    fun `no match finds nothing`() {
        assertEquals(emptyList<String>(), codes("xyzzy"))
        assertEquals(emptyList<String>(), codes("london xyzzy"))
    }

    @Test
    fun `byIata ignores case and returns the same airport`() {
        assertSame(airports[0], search.byIata("LHR"))
        assertSame(airports[7], search.byIata("jfk"))
        assertSame(airports[7], search.byIata(" Jfk "))
        assertNull(search.byIata("XXX"))
        assertNull(search.byIata(""))
    }

    @Test
    fun `byIcao ignores case`() {
        assertSame(airports[0], search.byIcao("egll"))
        assertSame(airports[2], search.byIcao(" EGKK "))
        assertNull(search.byIcao("ZZZZ"))
    }

    @Test
    fun `byRouteCode reads four characters as ICAO and three as IATA`() {
        assertSame(airports[0], search.byRouteCode("EGLL"))
        assertSame(airports[0], search.byRouteCode("LHR"))
        assertNull(search.byRouteCode("EG"))
        assertNull(search.byRouteCode("EGLLX"))
        assertNull(search.byRouteCode("ZZZZ"))
    }

    @Test
    fun `exact IATA code comes first, in any case`() {
        assertEquals("LHR", codes("lhr").first())
        assertEquals("LHR", codes("LHR").first())
    }

    @Test
    fun `exact ICAO code finds the airport`() {
        assertEquals(listOf("LHR"), codes("egll"))
        assertEquals(listOf("JFK"), codes("KJFK"))
    }

    @Test
    fun `IATA beats ICAO beats city prefix beats other word prefixes`() {
        // DEL by code, Del Rio by city prefix, Ciudad del Este by a later word.
        assertEquals(listOf("DEL", "DRT", "AGT"), codes("del"))
        // Vienna by its ICAO code LOWW, ahead of Lowwood, a city-prefix match.
        assertEquals(listOf("VIE", "XLW"), codes("loww"))
    }

    @Test
    fun `city search ranks larger airports first then list order`() {
        // Gatwick is listed after London City, but it is large. Then: Oxford by a
        // keyword prefix, East London by a later city word, and Rochford only by a
        // later keyword word.
        val london = listOf("LHR", "LGW", "LCY", "YXU", "OXF", "ELS", "XGL")
        assertEquals(london, codes("london"))
        assertEquals(london, codes("LONDON"))
    }

    @Test
    fun `keywords find airports below city and name matches`() {
        assertEquals(listOf("JFK", "LGA", "SWF", "EWR"), codes("new york"))
        assertEquals(listOf("HND", "NRT"), codes("tokyo"))
        assertEquals(listOf("JFK", "LGA", "EWR"), codes("nyc"))
        assertEquals(listOf("HND", "NRT"), codes("tyo"))
        assertEquals(listOf("BOM"), codes("bombay"))
        assertEquals(listOf("JFK"), codes("idlewild"))
        assertEquals(listOf("NRT"), codes("new tokyo"))
        assertEquals(listOf("BOM"), codes("sahar"))
    }

    @Test
    fun `query words may mix keywords with the name or city`() {
        assertEquals(listOf("NRT"), codes("tokyo narita"))
        assertEquals(listOf("LGW"), codes("crawley gatwick"))
    }

    @Test
    fun `multi-word queries match word prefixes`() {
        assertEquals(listOf("JFK", "LGA", "SWF", "EWR"), codes("new yo"))
        assertEquals(listOf("JFK"), codes("kennedy john"))
        assertEquals(listOf("JFK"), codes("john f kennedy"))
        assertEquals(listOf("ELS"), codes("east lon"))
    }

    @Test
    fun `airport names match by word prefix`() {
        assertEquals(listOf("LHR"), codes("heath"))
        assertEquals(listOf("LGW"), codes("gatwick"))
        assertEquals(listOf("GRU"), codes("guarulhos"))
    }

    @Test
    fun `accents and case are ignored both ways`() {
        assertEquals(listOf("ZRH"), codes("zurich"))
        assertEquals(listOf("ZRH"), codes("ZÜRICH"))
        assertEquals(listOf("GRU", "CGH"), codes("sao paulo"))
        assertEquals(listOf("GRU", "CGH"), codes("São Paulo"))
        assertEquals(listOf("DUS"), codes("dusseldorf"))
        assertEquals(listOf("LIH"), codes("lihue"))
        assertEquals(listOf("GRU"), codes("andre franco"))
    }

    @Test
    fun `apostrophes and punctuation are ignored`() {
        assertEquals(listOf("ORD"), codes("ohare"))
        assertEquals(listOf("ORD"), codes("O'Hare"))
        assertEquals(listOf("ORD"), codes("o’hare"))
        assertEquals(listOf("GRU"), codes("paulo/guarulhos"))
        assertEquals(listOf("JFK"), codes("John F. Kennedy"))
    }

    @Test
    fun `limit caps the results`() {
        assertEquals(listOf("LHR", "LGW"), codes("london", limit = 2))
        assertEquals(listOf("LHR", "LGW", "LCY", "YXU", "OXF"), codes("london", limit = 5))
        assertEquals(emptyList<String>(), codes("london", limit = 0))
    }
}
