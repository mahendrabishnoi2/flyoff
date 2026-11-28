package io.github.mahendrabishnoi2.flyoff.flight

import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalRuleTest {

    private val lhr = LatLon(51.4706, -0.461941)
    private val cdg = LatLon(49.0097, 2.5479)

    private fun point(latLon: LatLon, speed: Double?) = TrackPoint(0L, latLon, 100.0, speed)

    @Test
    fun `airborne is flying speed`() {
        assertTrue(ArrivalRule.isAirborne(point(lhr, 80.0)))
        assertFalse(ArrivalRule.isAirborne(point(lhr, 5.0)))
        assertFalse(ArrivalRule.isAirborne(point(lhr, null)))
    }

    @Test
    fun `arrived once slow near the destination after flying`() {
        assertTrue(ArrivalRule.hasArrived(wasAirborne = true, point = point(cdg, 3.0), origin = lhr, destination = cdg))
    }

    @Test
    fun `not arrived while still fast on the approach`() {
        assertFalse(ArrivalRule.hasArrived(wasAirborne = true, point = point(cdg, 70.0), origin = lhr, destination = cdg))
    }

    @Test
    fun `not arrived when slow far from the destination (taxiing out)`() {
        assertFalse(ArrivalRule.hasArrived(wasAirborne = true, point = point(lhr, 3.0), origin = lhr, destination = cdg))
    }

    @Test
    fun `not arrived without having flown (tracking started at the destination)`() {
        assertFalse(ArrivalRule.hasArrived(wasAirborne = false, point = point(cdg, 0.0), origin = lhr, destination = cdg))
    }

    @Test
    fun `not arrived with an unknown speed`() {
        assertFalse(ArrivalRule.hasArrived(wasAirborne = true, point = point(cdg, null), origin = lhr, destination = cdg))
    }
}
