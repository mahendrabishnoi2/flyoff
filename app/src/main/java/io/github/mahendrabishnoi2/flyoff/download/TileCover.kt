package io.github.mahendrabishnoi2.flyoff.download

import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Which map tiles a download region needs.
 *
 * MapLibre's offline downloader fetches, at each zoom of a region, every tile that the region's
 * geometry intersects, with polygon edges drawn as straight lines on the web-mercator map. This
 * computes the same set the same way, but errs on the side of more: a tile counts when it
 * intersects a polygon or merely comes within [EDGE_MARGIN] of a tile width of its edge. So the
 * counts here are an upper bound of what MapLibre will download, which is what the OpenFreeMap
 * per-download limits need.
 */
object TileCover {

    /** How close, in tile widths, an edge may pass to a tile for the tile to count. */
    const val EDGE_MARGIN: Double = 1e-3

    /**
     * The tiles at [zoom] that [polygons] cover: each polygon is an outer ring of lon/lat points
     * (longitudes in [-180, 180], closed or not), as [CorridorGeometry.strip] returns.
     */
    fun tiles(polygons: List<List<LatLon>>, zoom: Int): Set<TileId> {
        require(zoom in 0..TileId.MAX_ZOOM) { "zoom $zoom is not in 0..${TileId.MAX_ZOOM}" }
        val n = 1 shl zoom
        val cover = HashSet<TileId>()
        for (ring in polygons) {
            if (ring.size < 3) continue
            val xs = DoubleArray(ring.size) { (ring[it].longitude.coerceIn(-180.0, 180.0) + 180.0) / 360.0 * n }
            val ys = DoubleArray(ring.size) { WebMercator.y(ring[it].latitude) * n }
            addBoundary(xs, ys, zoom, cover)
            addInterior(xs, ys, zoom, cover)
        }
        return cover
    }

    /** The number of tiles [polygons] cover, summed over [zooms]. */
    fun count(polygons: List<List<LatLon>>, zooms: IntRange): Int = zooms.sumOf { tiles(polygons, it).size }

    /** Adds every tile that an edge of the ring passes through or within [EDGE_MARGIN] of. */
    private fun addBoundary(xs: DoubleArray, ys: DoubleArray, zoom: Int, cover: MutableSet<TileId>) {
        val last = (1 shl zoom) - 1
        for (i in xs.indices) {
            val j = (i + 1) % xs.size
            val x0 = xs[i]
            val y0 = ys[i]
            val x1 = xs[j]
            val y1 = ys[j]
            val firstRow = floor(min(y0, y1) - EDGE_MARGIN).toInt().coerceAtLeast(0)
            val lastRow = floor(max(y0, y1) + EDGE_MARGIN).toInt().coerceAtMost(last)
            for (row in firstRow..lastRow) {
                // The part of the edge within this row, widened by the margin.
                var from = 0.0
                var to = 1.0
                if (y1 != y0) {
                    val a = (row - EDGE_MARGIN - y0) / (y1 - y0)
                    val b = (row + 1 + EDGE_MARGIN - y0) / (y1 - y0)
                    from = max(from, min(a, b))
                    to = min(to, max(a, b))
                    if (from > to) continue
                }
                val xa = x0 + from * (x1 - x0)
                val xb = x0 + to * (x1 - x0)
                val firstColumn = floor(min(xa, xb) - EDGE_MARGIN).toInt().coerceAtLeast(0)
                val lastColumn = floor(max(xa, xb) + EDGE_MARGIN).toInt().coerceAtMost(last)
                for (column in firstColumn..lastColumn) cover += TileId(zoom, column, row)
            }
        }
    }

    /** Adds every tile whose centre is inside the ring (even-odd rule), row by row. */
    private fun addInterior(xs: DoubleArray, ys: DoubleArray, zoom: Int, cover: MutableSet<TileId>) {
        val last = (1 shl zoom) - 1
        val firstRow = floor(ys.min()).toInt().coerceAtLeast(0)
        val lastRow = floor(ys.max()).toInt().coerceAtMost(last)
        val crossings = ArrayList<Double>()
        for (row in firstRow..lastRow) {
            val y = row + 0.5
            crossings.clear()
            for (i in xs.indices) {
                val j = (i + 1) % xs.size
                if ((ys[i] > y) != (ys[j] > y)) crossings += xs[i] + (y - ys[i]) / (ys[j] - ys[i]) * (xs[j] - xs[i])
            }
            crossings.sort()
            for (k in 0 until crossings.size - 1 step 2) {
                val firstColumn = ceil(crossings[k] - 0.5).toInt().coerceAtLeast(0)
                val lastColumn = floor(crossings[k + 1] - 0.5).toInt().coerceAtMost(last)
                for (column in firstColumn..lastColumn) cover += TileId(zoom, column, row)
            }
        }
    }
}
