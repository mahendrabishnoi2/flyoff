package io.github.mahendrabishnoi2.flyoff.map

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * The route corridor's extra map detail (Milestone 4): layers for downloaded OpenFreeMap tiles,
 * kept apart from [STYLE_URI]'s style and merged into it by [mergeCorridorStyle].
 */
const val CORRIDOR_STYLE_ASSET = "style/corridor.json"

/** The vector source of the downloaded corridor tiles in the merged style. */
const val CORRIDOR_SOURCE = "corridor"

/** The GeoJSON source that holds [corridorMaskGeoJson] in the merged style. */
const val CORRIDOR_MASK_SOURCE = "corridor-mask"

/** The corridor source's lowest zoom (corridor.json); the mask is made of the corridor's tiles at this zoom. */
const val CORRIDOR_MIN_ZOOM = 6

private const val SLOT = "flyoff:slot"
private const val BELOW_ANCHOR = "below-anchor"
private const val ABOVE_ANCHOR = "above-anchor"

/** A web-mercator map tile: zoom [z], column [x] from the antimeridian eastwards, row [y] from the north. */
data class MapTile(val z: Int, val x: Int, val y: Int) {
    init {
        require(z in 0..30) { "zoom $z" }
        val n = 1 shl z
        require(x in 0 until n && y in 0 until n) { "tile $z/$x/$y does not exist" }
    }
}

/**
 * The corridor mask for [CORRIDOR_MASK_SOURCE]: a GeoJSON FeatureCollection whose one feature
 * is a MultiPolygon covering exactly [tiles], one rectangle per run of side-by-side tiles in a row.
 *
 * Pass the corridor tiles downloaded at [CORRIDOR_MIN_ZOOM]. Every higher-zoom tile lies inside
 * one of them, and where a higher-zoom tile was not downloaded MapLibre draws its downloaded
 * parent, so the corridor is drawn on exactly this area at every zoom. A tile in the mask that
 * was not downloaded would show as an empty land-coloured square, so pass only tiles that were.
 *
 * All tiles must have the same zoom. No tiles give an empty FeatureCollection.
 */
fun corridorMaskGeoJson(tiles: Collection<MapTile>): String {
    if (tiles.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""
    val z = tiles.first().z
    require(tiles.all { it.z == z }) { "mask tiles must all have one zoom" }
    val rectangles = StringBuilder()
    for ((y, row) in tiles.groupBy { it.y }.toSortedMap()) {
        val xs = row.map { it.x }.distinct().sorted()
        var start = 0
        while (start < xs.size) {
            var end = start
            while (end + 1 < xs.size && xs[end + 1] == xs[end] + 1) end++
            val west = tileLongitude(xs[start], z)
            val east = tileLongitude(xs[end] + 1, z)
            val north = tileLatitude(y, z)
            val south = tileLatitude(y + 1, z)
            if (rectangles.isNotEmpty()) rectangles.append(',')
            // Counter-clockwise, as GeoJSON wants exterior rings.
            rectangles.append("[[[$west,$south],[$east,$south],[$east,$north],[$west,$north],[$west,$south]]]")
            start = end + 1
        }
    }
    return """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},""" +
        """"geometry":{"type":"MultiPolygon","coordinates":[$rectangles]}}]}"""
}

/**
 * The style JSON of [world] (flyoff.json) with [corridor] (corridor.json) merged in, for
 * `Style.Builder().fromJson()`.
 *
 * Adds corridor.json's two sources, with the corridor's tile URL template set to [tilesUrl] (the
 * exact template its tiles were downloaded with, as MapLibre's offline database keys tiles by URL)
 * and its maxzoom to [maxZoom] (the highest zoom downloaded), and the mask source's data set to
 * [maskGeoJson] (see [corridorMaskGeoJson]). The layers whose `flyoff:slot` is `below-anchor` go
 * directly below the anchor layer named in corridor.json's metadata ([ROUTE_BELOW_LAYER], the
 * world's lowest label layer), the `above-anchor` ones directly above it, each in file order.
 * The route and track lines that [addRouteLayers] then adds below the anchor end up above the
 * corridor's fills and lines and below every label.
 */
fun mergeCorridorStyle(world: String, corridor: String, tilesUrl: String, maxZoom: Int, maskGeoJson: String): String {
    require(maxZoom >= CORRIDOR_MIN_ZOOM) { "maxZoom $maxZoom is below the corridor's minzoom $CORRIDOR_MIN_ZOOM" }
    val style = JSONObject(world)
    val extra = JSONObject(corridor)
    val anchor = extra.getJSONObject("metadata").getString("flyoff:anchor")

    val worldLayers = style.getJSONArray("layers").objects()
    val at = worldLayers.indexOfFirst { it.getString("id") == anchor }
    require(at >= 0) { "the world style has no layer $anchor" }
    val corridorLayers = extra.getJSONArray("layers").objects()
    for (layer in corridorLayers) {
        val slot = layer.optJSONObject("metadata")?.optString(SLOT)
        require(slot == BELOW_ANCHOR || slot == ABOVE_ANCHOR) { "corridor layer ${layer.getString("id")} has no $SLOT" }
    }
    fun slot(name: String) = corridorLayers.filter { it.getJSONObject("metadata").getString(SLOT) == name }
    val merged = worldLayers.subList(0, at) + slot(BELOW_ANCHOR) + worldLayers[at] + slot(ABOVE_ANCHOR) +
        worldLayers.subList(at + 1, worldLayers.size)
    style.put("layers", JSONArray(merged))

    val sources = style.getJSONObject("sources")
    val extraSources = extra.getJSONObject("sources")
    for (name in extraSources.keys()) {
        require(!sources.has(name)) { "source $name is in both styles" }
        sources.put(name, extraSources.getJSONObject(name))
    }
    sources.getJSONObject(CORRIDOR_SOURCE)
        .put("tiles", JSONArray().put(tilesUrl))
        .put("maxzoom", maxZoom)
    sources.getJSONObject(CORRIDOR_MASK_SOURCE).put("data", JSONObject(maskGeoJson))
    return style.toString()
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

private fun tileLongitude(x: Int, z: Int): Double = x.toDouble() / (1 shl z) * 360.0 - 180.0

private fun tileLatitude(y: Int, z: Int): Double = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / (1 shl z)))))

/** The highest zoom any corridor download fetches (the detailed map's strip). */
const val CORRIDOR_MAX_ZOOM = 9

/**
 * The style an offline region is created with, from [corridor] (corridor.json): only its
 * [CORRIDOR_SOURCE] vector source, with [tilesUrl] (the pinned template), zooms
 * [CORRIDOR_MIN_ZOOM] to [CORRIDOR_MAX_ZOOM] and corridor.json's attribution. No layers, glyphs
 * or sprite: MapLibre's offline downloader fetches every source's tiles whatever the layers, so
 * a region downloads the style and its tiles and nothing else.
 */
fun corridorRegionStyle(corridor: String, tilesUrl: String): String {
    val source = JSONObject(corridor).getJSONObject("sources").getJSONObject(CORRIDOR_SOURCE)
    val only = JSONObject()
        .put("type", "vector")
        .put("tiles", JSONArray().put(tilesUrl))
        .put("minzoom", CORRIDOR_MIN_ZOOM)
        .put("maxzoom", CORRIDOR_MAX_ZOOM)
        .put("attribution", source.getString("attribution"))
    return JSONObject()
        .put("version", 8)
        .put("name", "Flyoff corridor download")
        .put("sources", JSONObject().put(CORRIDOR_SOURCE, only))
        .put("layers", JSONArray())
        .toString()
}
