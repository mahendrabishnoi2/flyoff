package io.github.mahendrabishnoi2.flyoff.map

import kotlin.math.max

/** The bundled map style. Every URL in it is an `asset://` one, so it loads with no network. */
const val STYLE_URI = "asset://style/flyoff.json"

/** At zoom 0 the whole world is 512 dp wide, about a phone's width. */
const val MIN_ZOOM = 0.0

/**
 * The bundled tiles stop at zoom 5 and MapLibre overzooms them. A z5 tile's
 * 4096-unit grid is about 300 m per unit, which is one screen pixel at zoom 8.
 * Past that, coastlines turn visibly blocky and the map has nothing more to show.
 */
const val MAX_ZOOM = 8.0

/** With a detailed corridor (zoom-9 tiles) downloaded, the user may zoom one level further. */
const val MAX_CORRIDOR_ZOOM = 9.0

/** The camera on a cold start: the whole world, centred a little north of the equator where most land is. */
const val START_LATITUDE = 20.0
const val START_LONGITUDE = 0.0
const val START_ZOOM = MIN_ZOOM

/** Zoom used when "Follow me" starts from a world view, so the dot's surroundings are readable. */
const val FOLLOW_ZOOM = 6.0

/** The zoom to use when "Follow me" is turned on: zoom in from a world view, never out. */
fun followZoom(currentZoom: Double): Double = max(currentZoom, FOLLOW_ZOOM).coerceAtMost(MAX_ZOOM)
