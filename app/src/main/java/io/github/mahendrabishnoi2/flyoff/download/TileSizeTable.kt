package io.github.mahendrabishnoi2.flyoff.download

import java.nio.ByteBuffer

/**
 * Stored OpenFreeMap tile sizes by area: for each tile at [baseZoom] and each zoom in [zooms],
 * the mean and the largest stored (compressed) size of its descendants at that zoom. Absent
 * tiles count as 0 in the mean.
 *
 * Built by `tools/basemap/tile_size_table.py` from the pinned planet's PMTiles directories and
 * bundled as `assets/basemap/tile_sizes.bin`; that script's docstring documents the format.
 */
class TileSizeTable private constructor(
    /** The OpenFreeMap build the sizes come from, e.g. "20261101_080013_pt". */
    val build: String,
    /** The PMTiles tile compression code (2 = gzip). */
    val tileCompression: Int,
    /** The zoom of the areas (5: 1,024 areas). */
    val baseZoom: Int,
    /** The zooms with sizes. */
    val zooms: IntRange,
    private val unit: Int,
    private val cells: IntArray,
) {
    /** The mean stored size, in bytes, of tiles at [tile]'s zoom in [tile]'s base-zoom area. */
    fun storedMeanBytes(tile: TileId): Long = cell(tile, 0)

    /** The largest stored size, in bytes, of tiles at [tile]'s zoom in [tile]'s base-zoom area. */
    fun storedMaxBytes(tile: TileId): Long = cell(tile, 1)

    private fun cell(tile: TileId, which: Int): Long {
        require(tile.z in zooms) { "no sizes for zoom ${tile.z} (only $zooms)" }
        val shift = tile.z - baseZoom
        val side = 1 shl baseZoom
        val index = ((tile.z - zooms.first) * side * side + (tile.y shr shift) * side + (tile.x shr shift)) * 2 + which
        return cells[index].toLong() * unit
    }

    companion object {
        private val MAGIC = "FTSZ".toByteArray(Charsets.US_ASCII)
        private const val VERSION = 1

        /** The table in [bytes], or null if they are not a valid version-1 table. */
        fun fromBytes(bytes: ByteArray): TileSizeTable? {
            if (bytes.size < 12 || !bytes.copyOfRange(0, 4).contentEquals(MAGIC)) return null
            val buffer = ByteBuffer.wrap(bytes) // big-endian
            buffer.position(4)
            val version = buffer.get().toInt() and 0xFF
            val baseZoom = buffer.get().toInt() and 0xFF
            val first = buffer.get().toInt() and 0xFF
            val last = buffer.get().toInt() and 0xFF
            val compression = buffer.get().toInt() and 0xFF
            val unit = buffer.short.toInt() and 0xFFFF
            val nameLength = buffer.get().toInt() and 0xFF
            if (version != VERSION || baseZoom > 8 || first <= baseZoom || last < first || last > TileId.MAX_ZOOM || unit == 0) return null
            val side = 1 shl baseZoom
            val cellCount = (last - first + 1) * side * side * 2
            if (bytes.size.toLong() != 12L + nameLength + cellCount * 2L) return null
            val name = ByteArray(nameLength).also { buffer.get(it) }
            if (name.any { it < 0x20 || it > 0x7E }) return null
            val cells = IntArray(cellCount) { buffer.short.toInt() and 0xFFFF }
            return TileSizeTable(String(name, Charsets.US_ASCII), compression, baseZoom, first..last, unit, cells)
        }
    }
}
