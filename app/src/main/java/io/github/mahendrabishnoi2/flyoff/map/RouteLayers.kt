package io.github.mahendrabishnoi2.flyoff.map

import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * The flight's route on the map: the great-circle line, and a dot and IATA label at each end.
 *
 * Longitudes in [points] may be unwrapped past ±180; MapLibre draws such a line straight across
 * the antimeridian. The end markers use the first and last point, so they sit on the same
 * world copy as the line.
 */
data class MapRoute(val points: List<LatLon>, val originLabel: String, val destinationLabel: String)

/**
 * The route line goes under this layer, the lowest label layer in `flyoff.json`, so it is drawn
 * over land, water and borders and every map label stays readable on top of it.
 */
const val ROUTE_BELOW_LAYER = "label-water"

/** A font stack bundled in `assets/fonts` (the style's glyph URL is local, so no network). */
const val AIRPORT_LABEL_FONT = "Noto Sans Medium"

private const val ROUTE_SOURCE = "flyoff-route"
private const val AIRPORT_SOURCE = "flyoff-airports"
private const val ROUTE_LAYER = "flyoff-route-line"
private const val AIRPORT_DOT_LAYER = "flyoff-airport-dot"
private const val AIRPORT_LABEL_LAYER = "flyoff-airport-label"
private const val IATA_PROPERTY = "iata"
private const val TRACK_SOURCE = "flyoff-track"
private const val TRACK_HEAD_SOURCE = "flyoff-track-head"
private const val TRACK_LAYER = "flyoff-track-line"
private const val TRACK_HEAD_LAYER = "flyoff-track-head-line"

// A magenta that stands apart from the style's beige land, blue water and purple-grey borders.
private const val ROUTE_COLOR = "#c2185b"
private const val LABEL_COLOR = "#7a0f3a"
private const val WHITE = "#ffffff"

/**
 * The flown track: a strong blue, unlike the magenta route, the pale blue water (#a9cbe3) and the
 * purple-grey borders.
 */
const val TRACK_COLOR = "#1565c0"

/**
 * Adds the (empty) route sources and layers. Call it once per style load, before the location
 * component is activated, so the GPS dot is drawn above the route and its end markers.
 */
fun addRouteLayers(style: Style) {
    style.addSource(GeoJsonSource(ROUTE_SOURCE))
    style.addSource(GeoJsonSource(AIRPORT_SOURCE))

    val line = LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
        lineColor(ROUTE_COLOR),
        lineWidth(3f),
        lineCap(Property.LINE_CAP_ROUND),
        lineJoin(Property.LINE_JOIN_ROUND),
    )
    addBelowLabels(style, line)

    // The flown track goes over the planned route, still under the labels. Two sources: the
    // recorded points change at most every 5 s, the short head to the live fix every second.
    style.addSource(GeoJsonSource(TRACK_SOURCE))
    style.addSource(GeoJsonSource(TRACK_HEAD_SOURCE))
    for ((id, source) in listOf(TRACK_LAYER to TRACK_SOURCE, TRACK_HEAD_LAYER to TRACK_HEAD_SOURCE)) {
        addBelowLabels(
            style,
            LineLayer(id, source).withProperties(
                lineColor(TRACK_COLOR),
                lineWidth(3.5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
    }

    // The end markers go on top of the map labels: the airports matter more than a city name,
    // and symbol layers higher up are placed first, so city labels make way for them.
    style.addLayer(
        CircleLayer(AIRPORT_DOT_LAYER, AIRPORT_SOURCE).withProperties(
            circleRadius(5f),
            circleColor(ROUTE_COLOR),
            circleStrokeColor(WHITE),
            circleStrokeWidth(2f),
        ),
    )
    style.addLayer(
        SymbolLayer(AIRPORT_LABEL_LAYER, AIRPORT_SOURCE).withProperties(
            textField(Expression.get(IATA_PROPERTY)),
            textFont(arrayOf(AIRPORT_LABEL_FONT)),
            textSize(14f),
            textColor(LABEL_COLOR),
            textHaloColor(WHITE),
            textHaloWidth(1.5f),
            textAnchor(Property.TEXT_ANCHOR_TOP),
            textOffset(arrayOf(0f, 0.6f)),
            textAllowOverlap(true),
            textIgnorePlacement(false),
        ),
    )
}

/** Shows [route], or removes it when null. */
fun showRoute(style: Style, route: MapRoute?) {
    val line = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return
    val airports = style.getSourceAs<GeoJsonSource>(AIRPORT_SOURCE) ?: return
    if (route == null || route.points.isEmpty()) {
        line.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        airports.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        return
    }
    val points = route.points.map { Point.fromLngLat(it.longitude, it.latitude) }
    line.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(points)))
    airports.setGeoJson(
        FeatureCollection.fromFeatures(
            listOf(
                endFeature(points.first(), route.originLabel),
                endFeature(points.last(), route.destinationLabel),
            ),
        ),
    )
}

/**
 * Shows the recorded track [points] (longitudes already unwrapped, see `unwrapLongitudes`);
 * fewer than two points draw nothing. Call it only when the points change.
 */
fun showTrack(style: Style, points: List<LatLon>) {
    style.getSourceAs<GeoJsonSource>(TRACK_SOURCE)?.setGeoJson(lineOrNothing(points))
}

/** The track's head: from its last recorded point [from] to the live fix [to] (either may be null). */
fun showTrackHead(style: Style, from: LatLon?, to: LatLon?) {
    style.getSourceAs<GeoJsonSource>(TRACK_HEAD_SOURCE)?.setGeoJson(lineOrNothing(listOfNotNull(from, to)))
}

private fun lineOrNothing(points: List<LatLon>): FeatureCollection =
    if (points.size < 2) {
        FeatureCollection.fromFeatures(emptyList())
    } else {
        FeatureCollection.fromFeature(
            Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })),
        )
    }

private fun addBelowLabels(style: Style, layer: LineLayer) {
    if (style.getLayer(ROUTE_BELOW_LAYER) != null) style.addLayerBelow(layer, ROUTE_BELOW_LAYER) else style.addLayer(layer)
}

private fun endFeature(point: Point, label: String): Feature =
    Feature.fromGeometry(point).also { it.addStringProperty(IATA_PROPERTY, label) }
