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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(9..9, DownloadKind.DETAILED.zooms)
    }

    @Test
    fun `every chunk stays within the per-download targets`() {
        for ((a, b) in routes) {
            for (kind in DownloadKind.entries) {
                val plan = CorridorPlanner.plan(a, b, kind, sizes)
                assertEquals(kind, plan.kind)
                for (chunk in plan.chunks) {
                    assertEquals(kind.zooms, chunk.zooms)
                    assertTrue("$a-$b $kind chunk ${chunk.index}: ${chunk.tileCount} tiles", chunk.tileCount <= CorridorPlanner.TARGET_TILES_PER_CHUNK)
                    assertTrue("$a-$b $kind chunk ${chunk.index}: ${chunk.estimatedBytes} B", chunk.estimatedBytes <= CorridorPlanner.TARGET_BYTES_PER_CHUNK)
                    assertTrue(chunk.tileCount > 0)
                }
            }
        }
    }

    @Test
    fun `a chunk's counts are those of its own polygons`() {
        for ((a, b) in listOf(LHR to JFK, SYD to LAX)) {
            for (kind in DownloadKind.entries) {
                for (chunk in CorridorPlanner.plan(a, b, kind, sizes).chunks) {
                    val perZoom = kind.zooms.map { it to TileCover.tiles(chunk.polygons, it).size }
                    assertEquals(perZoom.sumOf { it.second }, chunk.tileCount)
                    assertEquals(kind.zooms.sumOf { sizes.rawBytes(TileCover.tiles(chunk.polygons, it)) }, chunk.estimatedBytes)
                }
            }
        }
    }

    @Test
    fun `chunks follow each other from origin to destination`() {
        for ((a, b) in routes) {
            for (kind in DownloadKind.entries) {
                val chunks = CorridorPlanner.plan(a, b, kind, sizes).chunks
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
    }

    @Test
    fun `the chunks leave no gap in the corridor`() {
        for ((a, b) in listOf(LHR to JFK, FRA to DEL, SYD to LAX, JFK to SIN)) {
            for (kind in DownloadKind.entries) {
                val chunks = CorridorPlanner.plan(a, b, kind, sizes).chunks
                val length = Geodesy.distanceMeters(a, b)
                var along = 0.0
                while (along <= length) {
                    for (side in listOf(-(h - 2_000.0), 0.0, h - 2_000.0)) {
                        val p = offsetPoint(a, b, along, side)
                        if (abs(p.latitude) < 85.0) assertTrue("$a-$b $kind gap at $along, $side", chunks.any { insideAny(it.polygons, p) })
                    }
                    along += 5_000.0
                }
            }
        }
    }

    @Test
    fun `the greedy split uses no more chunks than needed`() {
        // Joining any chunk to the next one would break a target.
        for ((a, b) in listOf(LHR to JFK, FRA to DEL, SYD to LAX)) {
            for (kind in DownloadKind.entries) {
                val chunks = CorridorPlanner.plan(a, b, kind, sizes).chunks
                for (i in 0 until chunks.size - 1) {
                    val joined = CorridorPlanner.chunk(a, b, kind, sizes, chunks[i].fromMeters, chunks[i + 1].toMeters, index = 0)
                    assertFalse(
                        "$a-$b $kind chunks $i and ${i + 1} fit together",
                        joined.tileCount <= CorridorPlanner.TARGET_TILES_PER_CHUNK && joined.estimatedBytes <= CorridorPlanner.TARGET_BYTES_PER_CHUNK,
                    )
                }
            }
        }
    }

    @Test
    fun `totals count tiles shared by neighbouring chunks once`() {
        for ((a, b) in listOf(LHR to JFK, FRA to DEL, SYD to LAX, JFK to SIN)) {
            for (kind in DownloadKind.entries) {
                val plan = CorridorPlanner.plan(a, b, kind, sizes)
                val distinct = kind.zooms.associateWith { z -> plan.chunks.flatMapTo(HashSet()) { TileCover.tiles(it.polygons, z) } }
                assertEquals(distinct.values.sumOf { it.size }, plan.totalTiles)
                assertEquals(distinct.values.sumOf { sizes.rawBytes(it) }, plan.totalEstimatedBytes)
                assertTrue(plan.totalTiles <= plan.chunks.sumOf { it.tileCount })
                if (plan.chunks.size > 1) assertTrue(plan.totalTiles < plan.chunks.sumOf { it.tileCount })
            }
        }
    }

    @Test
    fun `a short route is one chunk`() {
        for (kind in DownloadKind.entries) {
            val plan = CorridorPlanner.plan(LHR, CDG, kind, sizes)
            assertEquals(1, plan.chunks.size)
            assertEquals(plan.chunks.single().tileCount, plan.totalTiles)
            assertEquals(plan.chunks.single().estimatedBytes, plan.totalEstimatedBytes)
        }
    }

    @Test
    fun `the whole strip of a single chunk is the route's strip`() {
        val chunk = CorridorPlanner.plan(LHR, CDG, DownloadKind.ROUTE, sizes).chunks.single()
        assertEquals(CorridorGeometry.strip(LHR, CDG), chunk.polygons)
    }

    @Test
    fun `a flight back to the same airport is one round chunk`() {
        val plan = CorridorPlanner.plan(LHR, LHR, DownloadKind.ROUTE, sizes)
        assertEquals(1, plan.chunks.size)
        assertTrue(insideAny(plan.chunks.single().polygons, Geodesy.destination(LHR, 123.0, h - 5_000.0)))
    }

    @Test
    fun `land-aware sizes keep ocean routes in few chunks`() {
        val lhrJfk = CorridorPlanner.plan(LHR, JFK, DownloadKind.ROUTE, sizes)
        val fraDel = CorridorPlanner.plan(FRA, DEL, DownloadKind.ROUTE, sizes)
        // The byte estimate binds, not the tile count: zoom 6-8 corridors are only a few hundred tiles.
        assertTrue(lhrJfk.totalTiles in 380..440)
        assertEquals(1, lhrJfk.chunks.size)
        assertTrue(fraDel.chunks.size >= 2)
        val detailed = CorridorPlanner.plan(LHR, JFK, DownloadKind.DETAILED, sizes)
        assertTrue(detailed.totalTiles in 940..1_000)
        assertEquals(2, detailed.chunks.size)
        // Flat sizes are far more cautious.
        assertTrue(CorridorPlanner.plan(LHR, JFK, DownloadKind.ROUTE, TileSizes.FLAT).chunks.size >= 3)
    }

    @Test
    fun `a narrower strip needs fewer tiles`() {
        val wide = CorridorPlanner.plan(LHR, JFK, DownloadKind.ROUTE, sizes)
        val narrow = CorridorPlanner.plan(LHR, JFK, DownloadKind.ROUTE, sizes, halfWidthMeters = 50_000.0)
        assertTrue(narrow.totalTiles < wide.totalTiles)
    }

    @Test
    fun `chunk polygons are valid for MapLibre`() {
        for ((a, b) in routes) {
            for (chunk in CorridorPlanner.plan(a, b, DownloadKind.DETAILED, sizes).chunks) {
                for (ring in chunk.polygons) {
                    assertEquals(ring.first(), ring.last())
                    assertTrue(ring.all { it.longitude in -180.0..180.0 && abs(it.latitude) <= WebMercator.MAX_LATITUDE })
                }
            }
        }
    }
}
