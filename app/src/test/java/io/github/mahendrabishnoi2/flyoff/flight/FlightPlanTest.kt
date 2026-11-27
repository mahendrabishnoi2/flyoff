package io.github.mahendrabishnoi2.flyoff.flight

import io.github.mahendrabishnoi2.flyoff.airports.Airport
import io.github.mahendrabishnoi2.flyoff.airports.AirportSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FlightPlanTest {

    private fun airport(iata: String, lat: Double, lon: Double) = Airport(
        iata = iata,
        icao = null,
        name = "$iata Airport",
        city = iata,
        countryCode = "XX",
        latitude = lat,
        longitude = lon,
        size = AirportSize.LARGE,
        timeZone = "UTC",
    )

    private val lhr = airport("LHR", 51.47, -0.4543)
    private val jfk = airport("JFK", 40.6398, -73.7789)
    private val airports = mapOf("LHR" to lhr, "JFK" to jfk)

    @Test
    fun `picking fills each end`() {
        val draft = FlightDraft().pick(Endpoint.ORIGIN, "LHR")!!.pick(Endpoint.DESTINATION, "JFK")!!
        assertEquals(FlightDraft("LHR", "JFK"), draft)
        assertEquals(FlightCodes("LHR", "JFK"), draft.toCodes())
    }

    @Test
    fun `picking the other end's airport is rejected`() {
        assertNull(FlightDraft("LHR", null).pick(Endpoint.DESTINATION, "LHR"))
        assertNull(FlightDraft(null, "JFK").pick(Endpoint.ORIGIN, "JFK"))
    }

    @Test
    fun `picking the same airport again for the same end is fine`() {
        assertEquals(FlightDraft("LHR", "JFK"), FlightDraft("LHR", "JFK").pick(Endpoint.ORIGIN, "LHR"))
    }

    @Test
    fun `an incomplete draft has no codes`() {
        assertNull(FlightDraft().toCodes())
        assertNull(FlightDraft("LHR", null).toCodes())
        assertNull(FlightDraft(null, "JFK").toCodes())
    }

    @Test
    fun `swap exchanges the ends`() {
        assertEquals(FlightDraft("JFK", "LHR"), FlightDraft("LHR", "JFK").swapped())
        assertEquals(FlightDraft(null, "LHR"), FlightDraft("LHR", null).swapped())
    }

    @Test
    fun `clearing one end keeps the other`() {
        assertEquals(FlightDraft(null, "JFK"), FlightDraft("LHR", "JFK").cleared(Endpoint.ORIGIN))
        assertEquals(FlightDraft("LHR", null), FlightDraft("LHR", "JFK").cleared(Endpoint.DESTINATION))
    }

    @Test
    fun `codes need two different airports`() {
        assertThrows(IllegalArgumentException::class.java) { FlightCodes("LHR", "LHR") }
    }

    @Test
    fun `codes resolve to airports`() {
        val plan = FlightCodes("LHR", "JFK").resolve { airports[it] }!!
        assertEquals(lhr, plan.origin)
        assertEquals(jfk, plan.destination)
        assertEquals(FlightCodes("LHR", "JFK"), plan.codes)
    }

    @Test
    fun `codes for an unknown airport resolve to nothing`() {
        assertNull(FlightCodes("LHR", "XXX").resolve { airports[it] })
        assertNull(FlightCodes("XXX", "JFK").resolve { airports[it] })
    }

    @Test
    fun `stored codes are checked when read back`() {
        assertEquals(FlightCodes("LHR", "JFK"), FlightCodes.parse("LHR", "JFK"))
        assertEquals(FlightCodes("LHR", "JFK"), FlightCodes.parse("lhr", " jfk "))
        assertNull(FlightCodes.parse(null, "JFK"))
        assertNull(FlightCodes.parse("LHR", null))
        assertNull(FlightCodes.parse("LHR", "LHR"))
        assertNull(FlightCodes.parse("LH", "JFK"))
        assertNull(FlightCodes.parse("LHR", "J1K"))
    }

    @Test
    fun `a plan knows its route and simulated flight`() {
        val plan = FlightPlan(lhr, jfk)
        assertEquals(plan.originLatLon, plan.simulatedFlight.origin)
        assertEquals(plan.destinationLatLon, plan.simulatedFlight.destination)
        // LHR–JFK on the sphere is about 5,540 km.
        assertEquals(5_540_000.0, plan.routeLengthMeters, 5_000.0)
        assertEquals(plan.originLatLon, plan.routePoints.first())
        assertEquals(plan.destinationLatLon.latitude, plan.routePoints.last().latitude, 1e-9)
    }
}
