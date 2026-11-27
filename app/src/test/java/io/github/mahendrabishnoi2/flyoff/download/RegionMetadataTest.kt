package io.github.mahendrabishnoi2.flyoff.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RegionMetadataTest {

    private val metadata = RegionMetadata(
        origin = "LHR",
        destination = "JFK",
        kind = DownloadKind.ROUTE,
        chunkIndex = 1,
        chunkCount = 2,
        minZoom = 6,
        maxZoom = 8,
        createdAtMillis = 1_795_000_000_000L,
        landedAtMillis = 1_795_030_000_000L,
        tileUrlTemplate = "https://tiles.openfreemap.org/planet/20261122_124456_pt/{z}/{x}/{y}.pbf",
    )

    @Test
    fun `round trip`() {
        assertEquals(metadata, RegionMetadata.decode(metadata.encode()))
        val detailed = metadata.copy(kind = DownloadKind.DETAILED, chunkIndex = 0, chunkCount = 1, minZoom = 9, maxZoom = 9, landedAtMillis = null)
        assertEquals(detailed, RegionMetadata.decode(detailed.encode()))
    }

    @Test
    fun `landing time is optional`() {
        val notLanded = metadata.copy(landedAtMillis = null)
        assertTrue("landedAt" !in notLanded.encode().toString(Charsets.UTF_8))
        assertEquals(notLanded, RegionMetadata.decode(notLanded.encode()))
        assertNull(RegionMetadata.decode(notLanded.encode() + "landedAt=soon\n".toByteArray()))
    }

    @Test
    fun `the encoding is small, readable text with a version`() {
        val text = metadata.encode().toString(Charsets.UTF_8)
        assertTrue(text.startsWith("flyoff-region 1\n"))
        assertTrue(text.contains("origin=LHR\n"))
        assertTrue(metadata.encode().size < 512)
    }

    @Test
    fun `garbage decodes to null`() {
        assertNull(RegionMetadata.decode(ByteArray(0)))
        assertNull(RegionMetadata.decode("hello".toByteArray()))
        assertNull(RegionMetadata.decode("{\"name\":\"LHR-JFK\"}".toByteArray()))
        assertNull(RegionMetadata.decode(byteArrayOf(0xC3.toByte(), 0x28, 0x0A)))
        val random = Random(1)
        repeat(200) { assertNull(RegionMetadata.decode(random.nextBytes(random.nextInt(0, 300)))) }
    }

    @Test
    fun `another version decodes to null`() {
        val text = metadata.encode().toString(Charsets.UTF_8).replaceFirst("flyoff-region 1", "flyoff-region 2")
        assertNull(RegionMetadata.decode(text.toByteArray()))
    }

    @Test
    fun `damaged metadata decodes to null`() {
        val lines = metadata.encode().toString(Charsets.UTF_8).trimEnd('\n').split('\n')
        // Any one line missing, except the optional landing time.
        for (i in lines.indices) {
            if (lines[i].startsWith("landedAt=")) continue
            val text = lines.filterIndexed { j, _ -> j != i }.joinToString("\n", postfix = "\n")
            assertNull("without line $i", RegionMetadata.decode(text.toByteArray()))
        }
        fun with(key: String, value: String) = lines.map { if (it.startsWith("$key=")) "$key=$value" else it }.joinToString("\n", postfix = "\n").toByteArray()
        assertNull(RegionMetadata.decode(with("chunkIndex", "two")))
        assertNull(RegionMetadata.decode(with("chunkIndex", "2")))
        assertNull(RegionMetadata.decode(with("kind", "WORLD")))
        assertNull(RegionMetadata.decode(with("minZoom", "9")))
        assertNull(RegionMetadata.decode(with("origin", "")))
        assertNull(RegionMetadata.decode(with("createdAt", "9223372036854775808")))
        // A repeated key.
        assertNull(RegionMetadata.decode((lines + "origin=CDG").joinToString("\n", postfix = "\n").toByteArray()))
    }

    @Test
    fun `unknown keys are ignored so later versions can add fields`() {
        val text = metadata.encode().toString(Charsets.UTF_8) + "aircraft=A350\n"
        assertEquals(metadata, RegionMetadata.decode(text.toByteArray()))
    }

    @Test
    fun `invalid metadata cannot be built`() {
        assertThrows(IllegalArgumentException::class.java) { metadata.copy(chunkIndex = 2) }
        assertThrows(IllegalArgumentException::class.java) { metadata.copy(chunkIndex = -1) }
        assertThrows(IllegalArgumentException::class.java) { metadata.copy(chunkCount = 0, chunkIndex = 0) }
        assertThrows(IllegalArgumentException::class.java) { metadata.copy(minZoom = 9, maxZoom = 8) }
        assertThrows(IllegalArgumentException::class.java) { metadata.copy(maxZoom = 23) }
        assertThrows(IllegalArgumentException::class.java) { metadata.copy(origin = "LH") }
        assertThrows(IllegalArgumentException::class.java) { metadata.copy(destination = "jfk") }
        assertThrows(IllegalArgumentException::class.java) { metadata.copy(tileUrlTemplate = "https://a/{z}\n/{x}/{y}") }
        assertThrows(IllegalArgumentException::class.java) { metadata.copy(tileUrlTemplate = "") }
    }
}
