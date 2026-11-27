package io.github.mahendrabishnoi2.flyoff.map

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * MapLibre Native takes the zoom range from the PMTiles JSON metadata before the
 * header. If the metadata promised more zooms than the tiles hold (the planet
 * extract says "maxzoom": "14"), Native would ask for zoom 6+ tiles, get nothing
 * and draw an empty map at zoom 6-8 instead of overzooming the zoom-5 tiles.
 */
class BasemapTest {

    private val file = File("src/main/assets/basemap/world.pmtiles")

    @Test
    fun `metadata zoom range matches the header`() {
        val archive = readArchive()
        assertEquals("metadata minzoom", archive.minZoom, archive.metadata.get("minzoom").toString().toInt())
        assertEquals("metadata maxzoom", archive.maxZoom, archive.metadata.get("maxzoom").toString().toInt())
    }

    @Test
    fun `no vector layer claims zooms past the header's maxzoom`() {
        val archive = readArchive()
        val layers = archive.metadata.getJSONArray("vector_layers")
        assertTrue("no vector_layers", layers.length() > 0)
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            if (!layer.has("maxzoom")) continue
            val maxZoom = layer.get("maxzoom").toString().toInt()
            assertTrue("layer ${layer.getString("id")} maxzoom $maxZoom > ${archive.maxZoom}", maxZoom <= archive.maxZoom)
        }
    }

    private class Archive(val minZoom: Int, val maxZoom: Int, val metadata: JSONObject)

    /** Reads the PMTiles v3 header (127 bytes) and the JSON metadata it points to. */
    private fun readArchive(): Archive = RandomAccessFile(file, "r").use { raf ->
        val header = ByteArray(127).also(raf::readFully)
        assertEquals("not a PMTiles v3 file", "PMTiles\u0003", String(header, 0, 8, Charsets.ISO_8859_1))
        val le = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val metaOffset = le.getLong(24)
        val metaLength = le.getLong(32).toInt()
        val gzipped = header[97].toInt() == 2 // internal compression: 2 = gzip
        val raw = ByteArray(metaLength)
        raf.seek(metaOffset)
        raf.readFully(raw)
        val json = if (gzipped) GZIPInputStream(raw.inputStream()).readBytes() else raw
        Archive(
            minZoom = header[100].toInt() and 0xFF,
            maxZoom = header[101].toInt() and 0xFF,
            metadata = JSONObject(String(json, Charsets.UTF_8)),
        )
    }
}
