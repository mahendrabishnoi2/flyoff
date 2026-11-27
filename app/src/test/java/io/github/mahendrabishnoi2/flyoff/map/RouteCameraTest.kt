package io.github.mahendrabishnoi2.flyoff.map

import io.github.mahendrabishnoi2.flyoff.geo.Geodesy
import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

class RouteCameraTest {

    /** 512 px at zoom 0, as on a 1x screen. */
    private val world = 512.0

    /** Where a point lands on screen with the camera centred on [fit], in pixels, for a [width] × [height] view. */
    private fun project(point: LatLon, fit: CameraFit, width: Int, height: Int): Pair<Double, Double> {
        val size = world * 2.0.pow(fit.zoom)
        fun x(lon: Double) = (lon / 360.0 + 0.5) * size
        fun y(lat: Double) = (0.5 - ln(tan(PI / 4 + Math.toRadians(lat) / 2)) / (2 * PI)) * size
        var dx = x(point.longitude) - x(fit.longitude)
        // The route may be unwrapped past ±180 while the centre is not.
        while (dx > size / 2) dx -= size
        while (dx < -size / 2) dx += size
        return (width / 2.0 + dx) to (height / 2.0 + y(point.latitude) - y(fit.latitude))
    }

    private fun assertInside(points: List<LatLon>, fit: CameraFit, width: Int, height: Int, padding: MapInsets) {
        for (p in points) {
            val (x, y) = project(p, fit, width, height)
            assertTrue("$p at x=$x outside ${padding.left}..${width - padding.right}", x >= padding.left - 1 && x <= width - padding.right + 1)
            assertTrue("$p at y=$y outside ${padding.top}..${height - padding.bottom}", y >= padding.top - 1 && y <= height - padding.bottom + 1)
        }
    }

    private val lhrJfk = Geodesy.greatCirclePoints(LatLon(51.47, -0.4543), LatLon(40.6398, -73.7789))
    private val sydLax = Geodesy.greatCirclePoints(LatLon(-33.9461, 151.177), LatLon(33.9425, -118.408))

    @Test
    fun `the route fits inside the padded view`() {
        val padding = MapInsets(left = 20, top = 40, right = 20, bottom = 400)
        val fit = fitCamera(lhrJfk, 1080, 2000, padding, world, 0.0, 8.0)!!
        assertInside(lhrJfk, fit, 1080, 2000, padding)
        assertTrue("zoom ${fit.zoom}", fit.zoom in 0.0..8.0)
    }

    @Test
    fun `the fit is tight along the limiting side`() {
        val padding = MapInsets(left = 0, top = 0, right = 0, bottom = 0)
        val fit = fitCamera(lhrJfk, 1000, 1000, padding, world, 0.0, 20.0)!!
        val xs = lhrJfk.map { project(it, fit, 1000, 1000).first }
        val ys = lhrJfk.map { project(it, fit, 1000, 1000).second }
        val widest = maxOf(xs.max() - xs.min(), ys.max() - ys.min())
        assertEquals(1000.0, widest, 1.0)
    }

    @Test
    fun `bottom padding moves the route up`() {
        val none = fitCamera(lhrJfk, 1000, 2000, MapInsets(), world, 0.0, 8.0)!!
        val padded = fitCamera(lhrJfk, 1000, 2000, MapInsets(bottom = 1000), world, 0.0, 8.0)!!
        // The camera centre goes south of the route's middle, so the route sits higher on screen.
        assertTrue(padded.latitude < none.latitude)
        val (_, y) = project(LatLon(51.47, -0.4543), padded, 1000, 2000)
        assertTrue("y=$y", y < 1000)
    }

    @Test
    fun `a route across the antimeridian is fitted across it, not around the world`() {
        val padding = MapInsets(left = 10, top = 10, right = 10, bottom = 10)
        val fit = fitCamera(sydLax, 1080, 1920, padding, world, 0.0, 8.0)!!
        // The middle of the Pacific, not the middle of Africa.
        assertTrue("centre longitude ${fit.longitude}", fit.longitude > 150 || fit.longitude < -110)
        assertTrue("longitude ${fit.longitude} not normalised", fit.longitude in -180.0..180.0)
        assertInside(sydLax, fit, 1080, 1920, padding)
    }

    @Test
    fun `zoom is clamped to the allowed range`() {
        val short = listOf(LatLon(51.47, -0.45), LatLon(51.48, -0.44))
        assertEquals(8.0, fitCamera(short, 1000, 1000, MapInsets(), world, 0.0, 8.0)!!.zoom, 0.0)
        val single = listOf(LatLon(51.47, -0.45))
        assertEquals(8.0, fitCamera(single, 1000, 1000, MapInsets(), world, 0.0, 8.0)!!.zoom, 0.0)
        val wide = listOf(LatLon(-60.0, -170.0), LatLon(70.0, 170.0))
        assertEquals(0.0, fitCamera(wide, 300, 300, MapInsets(), world, 0.0, 8.0)!!.zoom, 0.0)
    }

    @Test
    fun `padding larger than the view still leaves room`() {
        val fit = fitCamera(lhrJfk, 1000, 1000, MapInsets(bottom = 2000), world, 0.0, 8.0)
        assertTrue(fit != null && fit.zoom >= 0.0)
    }

    @Test
    fun `nothing to fit gives no camera`() {
        assertNull(fitCamera(emptyList(), 1000, 1000, MapInsets(), world, 0.0, 8.0))
        assertNull(fitCamera(lhrJfk, 0, 1000, MapInsets(), world, 0.0, 8.0))
    }

    @Test
    fun `a bottom panel pads the bottom`() {
        val panel = PanelBounds(left = 16, top = 1400, right = 1064, bottom = 1980)
        val padding = routePadding(1080, 2000, panel, MapInsets(top = 60, bottom = 40), margin = 32)
        assertEquals(MapInsets(left = 32, top = 92, right = 32, bottom = 2000 - 1400 + 32), padding)
    }

    @Test
    fun `a side panel pads its side`() {
        // Landscape: the panel sits at the bottom left and takes most of the height.
        val panel = PanelBounds(left = 0, top = 200, right = 900, bottom = 1080)
        val padding = routePadding(2200, 1080, panel, MapInsets(), margin = 20)
        assertEquals(MapInsets(left = 920, top = 20, right = 20, bottom = 20), padding)

        val right = PanelBounds(left = 1300, top = 200, right = 2200, bottom = 1080)
        assertEquals(MapInsets(left = 20, top = 20, right = 920, bottom = 20), routePadding(2200, 1080, right, MapInsets(), margin = 20))
    }

    @Test
    fun `no panel pads only the safe area`() {
        assertEquals(MapInsets(10, 70, 10, 50), routePadding(1080, 2000, null, MapInsets(0, 60, 0, 40), margin = 10))
    }
}
