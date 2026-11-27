package io.github.mahendrabishnoi2.flyoff.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.mahendrabishnoi2.flyoff.download.CorridorPlan
import io.github.mahendrabishnoi2.flyoff.download.CorridorPlanner
import io.github.mahendrabishnoi2.flyoff.download.DownloadKind
import io.github.mahendrabishnoi2.flyoff.download.FlightMaps
import io.github.mahendrabishnoi2.flyoff.download.TileId
import io.github.mahendrabishnoi2.flyoff.download.TileSizes
import io.github.mahendrabishnoi2.flyoff.flight.FlightCodes
import io.github.mahendrabishnoi2.flyoff.flight.FlightPlan
import io.github.mahendrabishnoi2.flyoff.map.CORRIDOR_STYLE_ASSET
import io.github.mahendrabishnoi2.flyoff.map.MapTile
import io.github.mahendrabishnoi2.flyoff.map.STYLE_URI
import io.github.mahendrabishnoi2.flyoff.map.corridorMaskGeoJson
import io.github.mahendrabishnoi2.flyoff.map.mergeCorridorStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** The download plans of flights, computed once per flight and kind, off the main thread. */
private object CorridorPlans {
    private const val SIZES_ASSET = "basemap/tile_sizes.bin"
    private var sizes: TileSizes? = null
    private val plans = HashMap<Pair<FlightCodes, DownloadKind>, CorridorPlan>()

    @Synchronized
    fun get(context: Context, flight: FlightPlan, kind: DownloadKind): CorridorPlan = plans.getOrPut(flight.codes to kind) {
        val table = sizes ?: TileSizes.fromBytes(
            try {
                context.assets.open(SIZES_ASSET).use { it.readBytes() }
            } catch (e: IOException) {
                // TileSizes falls back to conservative flat sizes.
                null
            },
        ).also { sizes = it }
        CorridorPlanner.plan(flight.originLatLon, flight.destinationLatLon, kind, table)
    }
}

/** The [kind] download plan of [flight], or null while it is being worked out (or [enabled] is false). */
@Composable
fun rememberCorridorPlan(flight: FlightPlan?, kind: DownloadKind, enabled: Boolean = true): CorridorPlan? {
    val context = LocalContext.current.applicationContext
    val plan by produceState<CorridorPlan?>(null, flight?.codes, kind, enabled) {
        value = null
        if (flight == null || !enabled) return@produceState
        value = withContext(Dispatchers.Default) { CorridorPlans.get(context, flight, kind) }
    }
    return plan
}

/** The map style with a flight's downloaded corridor ([mergeCorridorStyle]), and its highest zoom. */
data class CorridorDisplay(val styleJson: String, val maxZoom: Int)

private data class CorridorKey(val tilesUrl: String, val mask: Set<TileId>, val maxZoom: Int)

/**
 * The style that shows [maps]' completed route downloads, or null for the bundled style alone:
 * when nothing is complete, and while any download runs ([downloading]), so MapLibre, connected
 * for the download, can't fetch corridor tiles for the map on screen.
 */
@Composable
fun rememberCorridorStyle(maps: FlightMaps?, downloading: Boolean): CorridorDisplay? {
    val context = LocalContext.current.applicationContext
    val key = remember(maps, downloading) {
        val template = maps?.tileUrlTemplate
        val mask = maps?.maskTiles.orEmpty()
        if (downloading || template == null || mask.isEmpty()) null else CorridorKey(template, mask, maps.maxZoom)
    }
    val display by produceState<CorridorDisplay?>(null, key) {
        if (key == null) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            fun asset(path: String) = context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
            val mask = corridorMaskGeoJson(key.mask.map { MapTile(it.z, it.x, it.y) })
            CorridorDisplay(
                mergeCorridorStyle(asset(STYLE_URI.removePrefix("asset://")), asset(CORRIDOR_STYLE_ASSET), key.tilesUrl, key.maxZoom, mask),
                key.maxZoom,
            )
        }
    }
    return display
}
