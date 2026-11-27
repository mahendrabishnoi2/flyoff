package io.github.mahendrabishnoi2.flyoff.download

import io.github.mahendrabishnoi2.flyoff.download.Routes.CDG
import io.github.mahendrabishnoi2.flyoff.download.Routes.DEL
import io.github.mahendrabishnoi2.flyoff.download.Routes.FRA
import io.github.mahendrabishnoi2.flyoff.download.Routes.JFK
import io.github.mahendrabishnoi2.flyoff.download.Routes.LAX
import io.github.mahendrabishnoi2.flyoff.download.Routes.LHR
import io.github.mahendrabishnoi2.flyoff.download.Routes.NRT
import io.github.mahendrabishnoi2.flyoff.download.Routes.OVER_POLE_FROM
import io.github.mahendrabishnoi2.flyoff.download.Routes.OVER_POLE_TO
import io.github.mahendrabishnoi2.flyoff.download.Routes.POLAR_80_FROM
import io.github.mahendrabishnoi2.flyoff.download.Routes.POLAR_80_TO
import io.github.mahendrabishnoi2.flyoff.download.Routes.SFO
import io.github.mahendrabishnoi2.flyoff.download.Routes.SIN
import io.github.mahendrabishnoi2.flyoff.download.Routes.SYD
import io.github.mahendrabishnoi2.flyoff.geo.Geodesy
import io.github.mahendrabishnoi2.flyoff.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CorridorPlannerTest {

    private val h = CorridorGeometry.DEFAULT_HALF_WIDTH_METERS
    private val sizes = TileSizes.fromBytes(bundledTileSizes)

    private val routes = listOf(
        LHR to JFK, FRA to DEL, SYD to LAX, NRT to SFO, JFK to SIN,
        POLAR_80_FROM to POLAR_80_TO, OVER_POLE_FROM to OVER_POLE_TO, LHR to CDG,
    )

    private val plans: Map<Pair<Pair<LatLon, LatLon>, DownloadKind>, CorridorPlan> by lazy {
        routes.flatMap { r -> DownloadKind.entries.map { k -> (r to k) to CorridorPlanner.plan(r.first, r.second, k, sizes) } }.toMap()
    }

    private fun plan(route: Pair<LatLon, LatLon>, kind: DownloadKind) = plans.getValue(route to kind)

    private fun children(tiles: Set<TileId>) =
        tiles.flatMapTo(HashSet()) { t -> listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1).map { (dx, dy) -> TileId(t.z + 1, 2 * t.x + dx, 2 * t.y + dy) } }

    @Test
    fun `targets leave a margin below OpenFreeMap's per-download limits`() {
        assertEquals(3_000, CorridorPlanner.MAX_TILES_PER_DOWNLOAD)
        assertEquals(50_000_000L, CorridorPlanner.MAX_BYTES_PER_DOWNLOAD)
        assertEquals(2_700, CorridorPlanner.TARGET_TILES_PER_CHUNK)
        assertEquals(40_000_000L, CorridorPlanner.TARGET_BYTES_PER_CHUNK)
    }

    @Test
    fun `download kinds have the agreed zooms`() {
        assertEquals(6..8, DownloadKind.ROUTE.zooms)
        assertEquals(6..7, DownloadKind.ROUTE.squaresZooms)
        assertEquals(8, DownloadKind.ROUTE.stripZoom)
        assertEquals(9..9, DownloadKind.DETAILED.zooms)
        assertTrue(DownloadKind.DETAILED.squaresZooms.isEmpty())
        assertEquals(9, DownloadKind.DETAILED.stripZoom)
    }

    @Test
    fun `every chunk stays within the per-download targets over both regions`() {
        for ((key, plan) in plans) {
            for (chunk in plan.chunks) {
                assertEquals(chunk.regions.sumOf { it.tileCount }, chunk.tileCount)
                assertEquals(chunk.regions.sumOf { it.estimatedBytes }, chunk.estimatedBytes)
                assertTrue("$key chunk ${chunk.index}: ${chunk.tileCount} tiles", chunk.tileCount in 1..CorridorPlanner.TARGET_TILES_PER_CHUNK)
                assertTrue("$key chunk ${chunk.index}: ${chunk.estimatedBytes} B", chunk.estimatedBytes <= CorridorPlanner.TARGET_BYTES_PER_CHUNK)
            }
        }
    }

    @Test
    fun `the squares region is the owned zoom-6 tiles whole, with their zoom-7 children`() {
        for ((key, plan) in plans.filterKeys { it.second == DownloadKind.ROUTE }) {
            for (chunk in plan.chunks) {
                val squares = chunk.squaresRegion
                if (chunk.ownedBaseTiles.isEmpty()) {
                    assertNull(squares)
                    continue
                }
                squares!!
                assertEquals(6..7, squares.zooms)
                assertTrue(chunk.ownedBaseTiles.all { it.z == 6 })
                assertEquals("$key", chunk.ownedBaseTiles, TileCover.tiles(squares.polygons, 6))
                assertEquals("$key", children(chunk.ownedBaseTiles), TileCover.tiles(squares.polygons, 7))
                assertEquals(5 * chunk.ownedBaseTiles.size, squares.tileCount)
                assertEquals(sizes.rawBytes(chunk.ownedBaseTiles) + sizes.rawBytes(children(chunk.ownedBaseTiles)), squares.estimatedBytes)
            }
        }
    }

    @Test
    fun `the strip region is the strip at one zoom`() {
        for ((key, plan) in plans) {
            val zoom = key.second.stripZoom
            for (chunk in plan.chunks) {
                val strip = chunk.stripRegion
                assertEquals(zoom..zoom, strip.zooms)
                val tiles = TileCover.tiles(strip.polygons, zoom)
                assertEquals(tiles.size, strip.tileCount)
                assertEquals(sizes.rawBytes(tiles), strip.estimatedBytes)
                if (key.second == DownloadKind.DETAILED) {
                    assertNull(chunk.squaresRegion)
                    assertTrue(chunk.ownedBaseTiles.isEmpty())
                }
            }
        }
    }

    @Test
    fun `each zoom-6 tile the corridor touches is downloaded once, by the first chunk that touches it`() {
        for (route in routes) {
            val plan = plan(route, DownloadKind.ROUTE)
            val seen = HashSet<TileId>()
            for (chunk in plan.chunks) {
                val touched = TileCover.tiles(chunk.stripRegion.polygons, 6)
                assertEquals(touched - seen, chunk.ownedBaseTiles)
                seen += touched
            }
            assertEquals(plan.chunks.sumOf { it.ownedBaseTiles.size }, plan.baseTiles.size)
            assertEquals("$route", TileCover.tiles(CorridorGeometry.strip(route.first, route.second), 6), plan.baseTiles)
        }
    }

    @Test
    fun `chunks follow each other from origin to destination`() {
        for ((key, plan) in plans) {
            val chunks = plan.chunks
            val (a, b) = key.first
            val length = Geodesy.distanceMeters(a, b)
            assertEquals(0.0, chunks.first().fromMeters, 0.0)
            assertEquals(length, chunks.last().toMeters, 1e-6)
            chunks.forEachIndexed { i, chunk ->
                assertEquals(i, chunk.index)
                assertTrue(chunk.toMeters > chunk.fromMeters)
                if (i > 0) assertEquals(chunks[i - 1].toMeters, chunk.fromMeters, 0.0)
            }
        }
    }

    @Test
    fun `the strips leave no gap in the corridor`() {
        for ((a, b) in listOf(LHR to JFK, FRA to DEL, SYD to LAX, JFK to SIN)) {
            for (kind in DownloadKind.entries) {
                val chunks = plan(a to b, kind).chunks
                val length = Geodesy.distanceMeters(a, b)
                var along = 0.0
                while (along <= length) {
                    for (side in listOf(-(h - 2_000.0), 0.0, h - 2_000.0)) {
                        val p = offsetPoint(a, b, along, side)
                        if (abs(p.latitude) < 85.0) assertTrue("$a-$b $kind gap at $along, $side", chunks.any { insideAny(it.stripRegion.polygons, p) })
                    }
                    along += 5_000.0
                }
            }
        }
    }

    @Test
    fun `the greedy split uses no more chunks than needed`() {
        // Joining any chunk to the next one would break a target.
        for ((a, b) in listOf(FRA to DEL, SYD to LAX, JFK to SIN)) {
            for (kind in DownloadKind.entries) {
                val chunks = plan(a to b, kind).chunks
                for (i in 0 until chunks.size - 1) {
                    val ownedBefore = chunks.take(i).flatMapTo(HashSet()) { it.ownedBaseTiles }
                    val joined = CorridorPlanner.chunk(a, b, kind, sizes, chunks[i].fromMeters, chunks[i + 1].toMeters, 0, ownedBefore)
                    assertFalse(
                        "$a-$b $kind chunks $i and ${i + 1} fit together",
                        joined.tileCount <= CorridorPlanner.TARGET_TILES_PER_CHUNK && joined.estimatedBytes <= CorridorPlanner.TARGET_BYTES_PER_CHUNK,
                    )
                }
            }
        }
    }

    @Test
    fun `totals count tiles shared by neighbouring strips once`() {
        for ((key, plan) in plans) {
            val distinct = HashSet<TileId>()
            for (chunk in plan.chunks) {
                distinct += chunk.ownedBaseTiles
                distinct += children(chunk.ownedBaseTiles)
                distinct += TileCover.tiles(chunk.stripRegion.polygons, key.second.stripZoom)
            }
            assertEquals("$key", distinct.size, plan.totalTiles)
            assertEquals(sizes.rawBytes(distinct), plan.totalEstimatedBytes)
            assertTrue(plan.totalTiles <= plan.chunks.sumOf { it.tileCount })
        }
    }

    @Test
    fun `LHR to JFK is one download of whole zoom-6 and zoom-7 tiles and the zoom-8 strip`() {
        val route = plan(LHR to JFK, DownloadKind.ROUTE)
        assertEquals(1, route.chunks.size)
        val chunk = route.chunks.single()
        assertEquals(33, chunk.ownedBaseTiles.size)
        assertEquals(33 + 132, chunk.squaresRegion!!.tileCount)
        assertTrue(chunk.stripRegion.tileCount in 275..285)
        assertEquals(chunk.tileCount, route.totalTiles)
        val detailed = plan(LHR to JFK, DownloadKind.DETAILED)
        assertTrue(detailed.totalTiles in 960..975)
        assertEquals(2, detailed.chunks.size)
    }

    @Test
    fun `a short route is one chunk`() {
        for (kind in DownloadKind.entries) {
            val plan = plan(LHR to CDG, kind)
            assertEquals(1, plan.chunks.size)
            assertEquals(plan.chunks.single().tileCount, plan.totalTiles)
            assertEquals(plan.chunks.single().estimatedBytes, plan.totalEstimatedBytes)
            assertEquals(CorridorGeometry.strip(LHR, CDG), plan.chunks.single().stripRegion.polygons)
        }
    }

    @Test
    fun `a flight back to the same airport is one round chunk`() {
        val plan = CorridorPlanner.plan(LHR, LHR, DownloadKind.ROUTE, sizes)
        assertEquals(1, plan.chunks.size)
        assertTrue(insideAny(plan.chunks.single().stripRegion.polygons, Geodesy.destination(LHR, 123.0, h - 5_000.0)))
    }

    @Test
    fun `flat sizes need more chunks and a narrower strip fewer tiles`() {
        assertTrue(CorridorPlanner.plan(LHR, JFK, DownloadKind.ROUTE, TileSizes.FLAT).chunks.size >= 3)
        val narrow = CorridorPlanner.plan(LHR, JFK, DownloadKind.ROUTE, sizes, halfWidthMeters = 50_000.0)
        assertTrue(narrow.totalTiles < plan(LHR to JFK, DownloadKind.ROUTE).totalTiles)
    }

    @Test
    fun `region polygons are valid for MapLibre`() {
        for (plan in plans.values) {
            for (region in plan.chunks.flatMap { it.regions }) {
                for (ring in region.polygons) {
                    assertEquals(ring.first(), ring.last())
                    assertTrue(ring.all { it.longitude in -180.0..180.0 && abs(it.latitude) <= WebMercator.MAX_LATITUDE })
                }
            }
        }
    }
}
