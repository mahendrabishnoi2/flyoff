package io.github.mahendrabishnoi2.flyoff.airports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AirportFileTest {

    private val header = "iata\ticao\tname\tcity\tcountry\tlat\tlon\tsize\ttz\tkeywords\n"
    private val lhr = "LHR\tEGLL\tLondon Heathrow Airport\tLondon\tGB\t51.4707\t-0.4599\tL\tEurope/London\tLON,Londres\n"
    private val cxh = "CXH\t\tVancouver Harbour Seaplane Base\tVancouver\tCA\t49.2950\t-123.1210\tO\tAmerica/Vancouver\t\n"

    @Test
    fun `parses every field`() {
        val airports = AirportFile.parse(header + lhr + cxh)

        assertEquals(
            listOf(
                Airport("LHR", "EGLL", "London Heathrow Airport", "London", "GB", 51.4707, -0.4599, AirportSize.LARGE, "Europe/London", listOf("LON", "Londres")),
                Airport("CXH", null, "Vancouver Harbour Seaplane Base", "Vancouver", "CA", 49.295, -123.121, AirportSize.OTHER, "America/Vancouver"),
            ),
            airports,
        )
    }

    @Test
    fun `maps every size code`() {
        val rows = listOf("L", "M", "S", "O").mapIndexed { i, code ->
            "AA${'A' + i}\t\tName\tCity\tGB\t1\t2\t$code\tEurope/London\t\n"
        }
        val sizes = AirportFile.parse(header + rows.joinToString("")).map { it.size }
        assertEquals(listOf(AirportSize.LARGE, AirportSize.MEDIUM, AirportSize.SMALL, AirportSize.OTHER), sizes)
    }

    @Test
    fun `keywords are optional in code and empty in the file`() {
        val airport = Airport("ABC", null, "Name", "City", "GB", 0.0, 0.0, AirportSize.SMALL, "Europe/London")
        assertEquals(emptyList<String>(), airport.keywords)
        assertEquals(emptyList<String>(), AirportFile.parse(header + cxh).single().keywords)
    }

    @Test
    fun `keywords keep their spaces and accents`() {
        val sgn = "SGN\tVVTS\tTan Son Nhat International Airport\tHo Chi Minh City\tVN\t10.8188\t106.6520\tL\tAsia/Ho_Chi_Minh\tSaigon,Sân bay Quốc tế,Tan Son Nhut Air Base\n"
        assertEquals(listOf("Saigon", "Sân bay Quốc tế", "Tan Son Nhut Air Base"), AirportFile.parse(header + sgn).single().keywords)
    }

    @Test
    fun `header only is an empty list`() {
        assertEquals(emptyList<Airport>(), AirportFile.parse(header))
    }

    @Test
    fun `tolerates windows line ends and a missing final line break`() {
        val crlf = (header + lhr + cxh).replace("\n", "\r\n").removeSuffix("\r\n")
        assertEquals(AirportFile.parse(header + lhr + cxh), AirportFile.parse(crlf))
    }

    @Test
    fun `text fields are taken literally with no quoting or escapes`() {
        val odd = "ODD\t\t\"Quoted\" Name, with \\t and 'Faʻaʻā'\t\tGB\t0\t0\tS\tEurope/London\t\n"
        val airport = AirportFile.parse(header + odd).single()
        assertEquals("\"Quoted\" Name, with \\t and 'Faʻaʻā'", airport.name)
        assertEquals("", airport.city)
    }

    @Test
    fun `a tab inside a field shows up as a wrong field count`() {
        val broken = "BRK\t\tName\twith tab\tCity\tGB\t0\t0\tS\tEurope/London\t\n"
        assertFails(header + lhr + broken, "line 3", "11 fields")
    }

    @Test
    fun `rejects malformed files with the line number`() {
        assertFails("", "header")
        assertFails("code\tname\n$lhr", "header")
        assertFails("iata\ticao\tname\tcity\tcountry\tlat\tlon\tsize\ttz\n", "header")
        assertFails(header + lhr.replace("\tL\t", "\tL\tExtra\t"), "line 2", "11 fields")
        assertFails(header + lhr.replace("\tLON,Londres", ""), "line 2", "9 fields")
        assertFails(header + lhr.replace("LON,Londres", "LON,,Londres"), "line 2", "keyword")
        assertFails(header + lhr.replace("LON,Londres", "LON,"), "line 2", "keyword")
        assertFails(header + lhr.replace("LHR", "LH"), "line 2", "IATA")
        assertFails(header + lhr.replace("LHR", "lhr"), "line 2", "IATA")
        assertFails(header + lhr.replace("EGLL", "EGL"), "line 2", "ICAO")
        assertFails(header + lhr.replace("London Heathrow Airport", ""), "line 2", "name")
        assertFails(header + lhr.replace("51.4707", "north"), "line 2", "latitude")
        assertFails(header + lhr.replace("51.4707", "91.0"), "line 2", "latitude")
        assertFails(header + lhr.replace("-0.4599", "-180.5"), "line 2", "longitude")
        assertFails(header + lhr.replace("-0.4599", "NaN"), "line 2", "longitude")
        assertFails(header + lhr.replace("\tL\t", "\tX\t"), "line 2", "size")
        assertFails(header + lhr.replace("Europe/London", ""), "line 2", "time zone")
        assertFails(header + lhr + "\n" + cxh, "line 3")
        assertFails(header + lhr + cxh + lhr, "line 4", "duplicate", "LHR")
    }

    private fun assertFails(text: String, vararg inMessage: String) {
        val error = assertThrows(IllegalArgumentException::class.java) { AirportFile.parse(text) }
        for (part in inMessage) {
            assertTrue("'${error.message}' should mention '$part'", error.message.orEmpty().contains(part))
        }
    }
}
