package io.github.mahendrabishnoi2.flyoff.map

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CorridorStyleTest {

    // Gradle runs unit tests with the module directory (app/) as the working directory.
    private val assets = File("src/main/assets")
    private val worldText = File(assets, STYLE_URI.removePrefix("asset://")).readText()
    private val corridorText = File(assets, CORRIDOR_STYLE_ASSET).readText()
    private val world = JSONObject(worldText)
    private val corridor = JSONObject(corridorText)

    private val tilesUrl = "https://tiles.openfreemap.org/planet/20261122_124456_pt/{z}/{x}/{y}.pbf"
    private val maxLatitude = 85.0511287798066

    // ---- Mask ----

    /** The mask's rectangles as [west, south, east, north], in the order written. */
    private fun rectangles(geoJson: String): List<List<Double>> {
        val features = JSONObject(geoJson).getJSONArray("features")
        if (features.length() == 0) return emptyList()
        val geometry = features.getJSONObject(0).getJSONObject("geometry")
        assertEquals("MultiPolygon", geometry.getString("type"))
        val polygons = geometry.getJSONArray("coordinates")
        return (0 until polygons.length()).map { i ->
            val ring = polygons.getJSONArray(i).getJSONArray(0)
            assertEquals("one closed ring of 4 corners", 5, ring.length())
            val points = (0 until 5).map { ring.getJSONArray(it) }.map { it.getDouble(0) to it.getDouble(1) }
            assertEquals("ring is closed", points.first(), points.last())
            val lons = points.map { it.first }
            val lats = points.map { it.second }
            listOf(lons.min(), lats.min(), lons.max(), lats.max())
        }
    }

    private fun assertRectangle(expected: List<Double>, actual: List<Double>) {
        for (i in 0 until 4) assertEquals("$actual", expected[i], actual[i], 1e-9)
    }

    @Test
    fun `no tiles give an empty mask`() {
        val geoJson = corridorMaskGeoJson(emptyList())
        assertEquals("FeatureCollection", JSONObject(geoJson).getString("type"))
        assertTrue(rectangles(geoJson).isEmpty())
    }

    @Test
    fun `one tile covers exactly its square`() {
        val geoJson = corridorMaskGeoJson(listOf(MapTile(1, 0, 0)))
        assertEquals(1, rectangles(geoJson).size)
        assertRectangle(listOf(-180.0, 0.0, 0.0, maxLatitude), rectangles(geoJson).single())
    }

    @Test
    fun `the ring is counter-clockwise`() {
        val ring = JSONObject(corridorMaskGeoJson(listOf(MapTile(6, 31, 20)))).getJSONArray("features")
            .getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0).getJSONArray(0)
        var twiceArea = 0.0
        for (i in 0 until ring.length() - 1) {
            val a = ring.getJSONArray(i)
            val b = ring.getJSONArray(i + 1)
            twiceArea += a.getDouble(0) * b.getDouble(1) - b.getDouble(0) * a.getDouble(1)
        }
        assertTrue("signed area $twiceArea", twiceArea > 0)
    }

    @Test
    fun `side-by-side tiles in a row make one rectangle`() {
        // Zoom-6 tiles 30..32 of row 20: 11.25 W to 5.625 E, about 52.48 N to 55.78 N.
        val tiles = listOf(MapTile(6, 32, 20), MapTile(6, 30, 20), MapTile(6, 31, 20))
        val rects = rectangles(corridorMaskGeoJson(tiles))
        assertEquals(1, rects.size)
        assertRectangle(listOf(-11.25, 52.48278, 5.625, 55.776573), rects.single().map { Math.round(it * 1e6) / 1e6 })
    }

    @Test
    fun `a gap splits a row and each row is separate`() {
        val tiles = listOf(MapTile(3, 1, 2), MapTile(3, 2, 2), MapTile(3, 5, 2), MapTile(3, 2, 3))
        val rects = rectangles(corridorMaskGeoJson(tiles))
        assertEquals(3, rects.size)
        assertEquals(-135.0, rects[0][0], 1e-9)
        assertEquals(-45.0, rects[0][2], 1e-9)
        assertEquals(45.0, rects[1][0], 1e-9)
        assertEquals(-90.0, rects[2][0], 1e-9)
        // Rows meet exactly, so no hairline gap shows between them.
        assertEquals(rects[0][1], rects[2][3], 0.0)
    }

    @Test
    fun `duplicate tiles are drawn once`() {
        val rects = rectangles(corridorMaskGeoJson(listOf(MapTile(6, 31, 20), MapTile(6, 31, 20))))
        assertEquals(1, rects.size)
    }

    @Test
    fun `mask tiles must share one zoom`() {
        assertThrows(IllegalArgumentException::class.java) {
            corridorMaskGeoJson(listOf(MapTile(6, 31, 20), MapTile(7, 62, 40)))
        }
    }

    @Test
    fun `tiles outside the zoom's grid are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { MapTile(2, 4, 0) }
        assertThrows(IllegalArgumentException::class.java) { MapTile(2, 0, -1) }
    }

    // ---- Merge ----

    private fun merged(maxZoom: Int = 8, mask: String = corridorMaskGeoJson(listOf(MapTile(6, 31, 20)))) =
        JSONObject(mergeCorridorStyle(worldText, corridorText, tilesUrl, maxZoom, mask))

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    private fun ids(style: JSONObject) = style.getJSONArray("layers").objects().map { it.getString("id") }

    @Test
    fun `corridor layers go right below and right above the anchor`() {
        val worldIds = ids(world)
        val corridorLayers = corridor.getJSONArray("layers").objects()
        val below = corridorLayers.filter { it.getJSONObject("metadata").getString("flyoff:slot") == "below-anchor" }.map { it.getString("id") }
        val above = corridorLayers.filter { it.getJSONObject("metadata").getString("flyoff:slot") == "above-anchor" }.map { it.getString("id") }
        val at = worldIds.indexOf(ROUTE_BELOW_LAYER)
        val expected = worldIds.subList(0, at) + below + ROUTE_BELOW_LAYER + above + worldIds.subList(at + 1, worldIds.size)
        assertEquals(expected, ids(merged()))
        assertEquals("the mask is the first corridor layer", "corridor-mask", below.first())
    }

    @Test
    fun `the corridor's anchor is the route's anchor`() {
        assertEquals(ROUTE_BELOW_LAYER, corridor.getJSONObject("metadata").getString("flyoff:anchor"))
    }

    @Test
    fun `no label is drawn below the anchor`() {
        // The route line goes directly below the anchor, so it stays under every label.
        val layers = merged().getJSONArray("layers").objects()
        val at = layers.indexOfFirst { it.getString("id") == ROUTE_BELOW_LAYER }
        for (layer in layers.subList(0, at)) {
            assertNotEquals("${layer.getString("id")} is below the route", "symbol", layer.getString("type"))
        }
    }

    @Test
    fun `the merge sets the tile URL, maxzoom and mask`() {
        val mask = corridorMaskGeoJson(listOf(MapTile(6, 31, 20)))
        val sources = merged(maxZoom = 9, mask = mask).getJSONObject("sources")
        val tiles = sources.getJSONObject(CORRIDOR_SOURCE)
        assertEquals(listOf(tilesUrl), tiles.getJSONArray("tiles").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertEquals(9, tiles.getInt("maxzoom"))
        assertEquals(CORRIDOR_MIN_ZOOM, tiles.getInt("minzoom"))
        assertEquals(JSONObject(mask).toString(), sources.getJSONObject(CORRIDOR_MASK_SOURCE).getJSONObject("data").toString())
        assertTrue("the world source stays", sources.has("openmaptiles"))
    }

    @Test
    fun `maxzoom below the corridor's minzoom is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { merged(maxZoom = 5) }
    }

    // ---- corridor.json ----

    @Test
    fun `corridor layer ids are unique and new`() {
        val corridorIds = ids(corridor)
        assertEquals(corridorIds.size, corridorIds.toSet().size)
        assertTrue(corridorIds.intersect(ids(world).toSet()).isEmpty())
        assertTrue(corridor.getJSONObject("sources").keys().asSequence().toSet().intersect(world.getJSONObject("sources").keys().asSequence().toSet()).isEmpty())
    }

    @Test
    fun `corridor layers use only the corridor and mask sources`() {
        for (layer in corridor.getJSONArray("layers").objects()) {
            val source = layer.getString("source")
            assertTrue("${layer.getString("id")}: $source", source == CORRIDOR_SOURCE || source == CORRIDOR_MASK_SOURCE)
        }
    }

    @Test
    fun `the corridor source starts at the mask's zoom`() {
        assertEquals(CORRIDOR_MIN_ZOOM, corridor.getJSONObject("sources").getJSONObject(CORRIDOR_SOURCE).getInt("minzoom"))
        val mask = corridor.getJSONArray("layers").objects().single { it.getString("source") == CORRIDOR_MASK_SOURCE }
        assertEquals(CORRIDOR_MIN_ZOOM, mask.getInt("minzoom"))
    }

    @Test
    fun `the mask is the world's land colour`() {
        val land = world.getJSONArray("layers").objects().single { it.getString("type") == "background" }
            .getJSONObject("paint").getString("background-color")
        val mask = corridor.getJSONArray("layers").objects().single { it.getString("id") == "corridor-mask" }
        assertEquals(land, mask.getJSONObject("paint").getString("fill-color"))
        assertFalse("antialiasing would draw seams between the mask's rectangles", mask.getJSONObject("paint").getBoolean("fill-antialias"))
    }

    @Test
    fun `the corridor tiles are OpenFreeMap's, with the same credit as the world map`() {
        val source = corridor.getJSONObject("sources").getJSONObject(CORRIDOR_SOURCE)
        val tiles = source.getJSONArray("tiles")
        assertEquals(1, tiles.length())
        assertTrue(tiles.getString(0), tiles.getString(0).startsWith("https://tiles.openfreemap.org/"))
        assertEquals(
            world.getJSONObject("sources").getJSONObject("openmaptiles").getString("attribution"),
            source.getString("attribution"),
        )
    }

    @Test
    fun `every corridor font stack is bundled`() {
        val glyphs = world.getString("glyphs")
        val ranges = (0 until 6).map { "${it * 256}-${it * 256 + 255}" }
        for (layer in corridor.getJSONArray("layers").objects()) {
            val fonts = layer.optJSONObject("layout")?.optJSONArray("text-font") ?: continue
            val stack = (0 until fonts.length()).joinToString(",") { fonts.getString(it) }
            for (range in ranges) {
                val file = File(assets, glyphs.removePrefix("asset://").replace("{fontstack}", stack).replace("{range}", range))
                assertTrue("${layer.getString("id")}: missing $file", file.isFile && file.length() > 0)
            }
        }
    }

    // ---- Offline region style ----

    @Test
    fun `the region style has only the corridor source, pinned, with zooms 6 to 9`() {
        val style = JSONObject(corridorRegionStyle(corridorText, tilesUrl))
        assertEquals(8, style.getInt("version"))
        assertEquals(0, style.getJSONArray("layers").length())
        assertFalse(style.has("glyphs"))
        assertFalse(style.has("sprite"))
        val sources = style.getJSONObject("sources")
        assertEquals(listOf(CORRIDOR_SOURCE), sources.keys().asSequence().toList())
        val source = sources.getJSONObject(CORRIDOR_SOURCE)
        assertEquals("vector", source.getString("type"))
        assertEquals(1, source.getJSONArray("tiles").length())
        assertEquals(tilesUrl, source.getJSONArray("tiles").getString(0))
        assertEquals(CORRIDOR_MIN_ZOOM, source.getInt("minzoom"))
        assertEquals(9, source.getInt("maxzoom"))
        assertFalse("no TileJSON to fetch", source.has("url"))
        val attribution = source.getString("attribution")
        assertEquals(corridor.getJSONObject("sources").getJSONObject(CORRIDOR_SOURCE).getString("attribution"), attribution)
        for (credit in listOf("OpenFreeMap", "OpenMapTiles", "OpenStreetMap contributors")) assertTrue(credit, credit in attribution)
    }
}
