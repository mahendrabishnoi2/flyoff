package io.github.mahendrabishnoi2.flyoff.map

import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/** A camera position: centre and zoom. */
data class CameraFit(val latitude: Double, val longitude: Double, val zoom: Double)

/** The screen rectangle of an overlay (the bottom panel), in pixels. */
data class PanelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Web Mercator stops here; the map can't show further north or south. */
private const val MAX_MERCATOR_LATITUDE = 85.05112878

/** Never squeeze the route into less than this, whatever the padding. */
private const val MIN_FREE_PX = 64

/**
 * The camera that shows all of [points] as large as possible inside a [widthPx] × [heightPx]
 * map view, keeping clear of [padding] on each side. [worldSizePx] is the width of the whole
 * world at zoom 0 in pixels (MapLibre: 512 × the screen density). Longitudes may be unwrapped
 * past ±180 (as [io.github.mahendrabishnoi2.flyoff.geo.Geodesy.greatCirclePoints] returns them),
 * so a route across the antimeridian is fitted across it; the returned longitude is in -180..180.
 *
 * Returns null when there is nothing to fit or no view to fit it in.
 */
fun fitCamera(
    points: List<LatLon>,
    widthPx: Int,
    heightPx: Int,
    padding: MapInsets,
    worldSizePx: Double,
    minZoom: Double,
    maxZoom: Double,
): CameraFit? {
    if (points.isEmpty() || widthPx <= 0 || heightPx <= 0) return null
    // World coordinates: 0..1 across the world at zoom 0 (x may go past 1 for unwrapped longitudes).
    val xs = points.map { it.longitude / 360.0 + 0.5 }
    val ys = points.map { mercatorY(it.latitude) }
    val minX = xs.min()
    val maxX = xs.max()
    val minY = ys.min()
    val maxY = ys.max()

    val freeWidth = max(widthPx - padding.left - padding.right, min(MIN_FREE_PX, widthPx))
    val freeHeight = max(heightPx - padding.top - padding.bottom, min(MIN_FREE_PX, heightPx))
    val scaleX = if (maxX > minX) freeWidth / ((maxX - minX) * worldSizePx) else Double.POSITIVE_INFINITY
    val scaleY = if (maxY > minY) freeHeight / ((maxY - minY) * worldSizePx) else Double.POSITIVE_INFINITY
    val zoom = log2(min(scaleX, scaleY)).coerceIn(minZoom, maxZoom)

    // Put the middle of the route in the middle of the free area, not of the whole view.
    val worldPx = worldSizePx * 2.0.pow(zoom)
    val freeLeft = (widthPx - freeWidth).coerceAtMost(padding.left).coerceAtLeast(0)
    val freeTop = (heightPx - freeHeight).coerceAtMost(padding.top).coerceAtLeast(0)
    val shiftX = (freeLeft + freeWidth / 2.0) - widthPx / 2.0
    val shiftY = (freeTop + freeHeight / 2.0) - heightPx / 2.0
    val centreX = (minX + maxX) / 2 - shiftX / worldPx
    val centreY = ((minY + maxY) / 2 - shiftY / worldPx).coerceIn(0.0, 1.0)

    return CameraFit(
        latitude = latitudeOf(centreY).coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE),
        longitude = normalizeLongitude((centreX - 0.5) * 360.0),
        zoom = zoom,
    )
}

/**
 * Padding that keeps a fitted route clear of the system bars ([safe]) and of the [panel]: the
 * route goes in the largest free area, above the panel or beside it (in landscape the panel
 * sits at one side). [margin] is added all round.
 */
fun routePadding(widthPx: Int, heightPx: Int, panel: PanelBounds?, safe: MapInsets, margin: Int): MapInsets {
    val base = MapInsets(safe.left + margin, safe.top + margin, safe.right + margin, safe.bottom + margin)
    if (panel == null) return base
    return when (freeSide(widthPx, heightPx, panel)) {
        FreeSide.ABOVE -> base.copy(bottom = max(base.bottom, heightPx - panel.top + margin))
        FreeSide.RIGHT -> base.copy(left = max(base.left, panel.right + margin))
        FreeSide.LEFT -> base.copy(right = max(base.right, widthPx - panel.left + margin))
    }
}

/**
 * Margins for MapLibre's logo and attribution (bottom left) and compass (top right), so they
 * stay in the free map area and can always be seen and tapped: above a bottom panel, or beside
 * a side panel at the bottom of the screen (never pushed up under the status bar).
 */
fun ornamentInsets(widthPx: Int, heightPx: Int, panel: PanelBounds?, safe: MapInsets): MapInsets {
    if (panel == null) return safe
    return when (freeSide(widthPx, heightPx, panel)) {
        FreeSide.ABOVE -> safe.copy(bottom = max(safe.bottom, heightPx - panel.top))
        FreeSide.RIGHT -> safe.copy(left = max(safe.left, panel.right))
        // The panel is on the right: the bottom-left corner is already free.
        FreeSide.LEFT -> safe
    }
}

/** Which part of the screen the map shows in: above the panel or beside it. */
enum class FreeSide { ABOVE, LEFT, RIGHT }

/** The largest free area next to [panel]: above it, or to its left or right. */
fun freeSide(widthPx: Int, heightPx: Int, panel: PanelBounds): FreeSide {
    val above = panel.top.toLong() * widthPx
    val leftOf = panel.left.toLong() * heightPx
    val rightOf = (widthPx - panel.right).toLong() * heightPx
    return when (maxOf(above, leftOf, rightOf)) {
        above -> FreeSide.ABOVE
        rightOf -> FreeSide.RIGHT
        else -> FreeSide.LEFT
    }
}

/** What a route fit was worked out for. */
data class FitRequest(val points: List<LatLon>, val widthPx: Int, val heightPx: Int, val padding: MapInsets)

/**
 * Whether the camera should be fitted again for [current], after last fitting for [previous]
 * (null: not fitted yet). A new route or view size refits; padding only when a side changed by
 * more than [thresholdPx], so a stat that wraps to a second line doesn't make the map jump.
 */
fun needsRefit(previous: FitRequest?, current: FitRequest, thresholdPx: Int): Boolean {
    if (previous == null) return true
    if (previous.points != current.points) return true
    if (previous.widthPx != current.widthPx || previous.heightPx != current.heightPx) return true
    val a = previous.padding
    val b = current.padding
    return maxOf(abs(a.left - b.left), abs(a.top - b.top), abs(a.right - b.right), abs(a.bottom - b.bottom)) > thresholdPx
}

private fun mercatorY(latitude: Double): Double {
    val lat = Math.toRadians(latitude.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE))
    return 0.5 - ln(tan(PI / 4 + lat / 2)) / (2 * PI)
}

private fun latitudeOf(mercatorY: Double): Double = Math.toDegrees(2 * atan(exp((0.5 - mercatorY) * 2 * PI)) - PI / 2)

private fun normalizeLongitude(longitude: Double): Double {
    val lon = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    return if (lon == -180.0 && longitude > 0) 180.0 else lon
}
