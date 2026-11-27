package io.github.mahendrabishnoi2.flyoff.routes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class RouteUpdateTest {

    private val current = 1_795_754_966L
    private val newer = current + 7 * 86_400
    private val file = RouteFileWriter.file(RouteFileWriter.SAMPLE, date = newer)
    private val sha = MessageDigest.getInstance("SHA-256").digest(file).joinToString("") { "%02x".format(it) }
    private val manifest = RouteManifest(1, newer, file.size.toLong(), sha)

    /** As tools/routes/build_routes.py writes it. */
    private fun json(
        version: Any = 1,
        seconds: Any = newer,
        size: Any = file.size,
        sha256: Any = "\"$sha\"",
    ) = """
        {
          "format_version": $version,
          "date": "2026-12-04T04:49:26Z",
          "date_seconds": $seconds,
          "file": "routes-v1.bin",
          "size": $size,
          "sha256": $sha256,
          "source": "https://github.com/vradarserver/standing-data/commit/${"0".repeat(40)}"
        }
    """.trimIndent()

    @Test
    fun `the urls are our own release, for this format`() {
        assertEquals("https://github.com/mahendrabishnoi2/flyoff/releases/download/routes-data/routes-v1.json", RouteUpdate.MANIFEST_URL)
        assertEquals("https://github.com/mahendrabishnoi2/flyoff/releases/download/routes-data/routes-v1.bin", RouteUpdate.FILE_URL)
    }

    @Test
    fun `reads the manifest`() {
        assertEquals(manifest, RouteUpdate.parseManifest(json()))
    }

    @Test
    fun `refuses a bad manifest`() {
        val bad = listOf(
            "not json",
            "{}",
            json(version = 2),
            json(version = "\"1\""),
            json(seconds = 0),
            json(seconds = -5),
            json(size = 0),
            json(size = RouteUpdate.MAX_FILE_BYTES + 1),
            json(sha256 = "\"abc\""),
            json(sha256 = "\"${sha.uppercase()}\""),
            json(sha256 = "null"),
        )
        for (text in bad) assertNull(text, RouteUpdate.parseManifest(text))
    }

    @Test
    fun `only a newer build is offered`() {
        assertTrue(RouteUpdate.isNewer(manifest, current))
        assertFalse(RouteUpdate.isNewer(manifest, newer))
        assertFalse(RouteUpdate.isNewer(manifest, newer + 1))
    }

    @Test
    fun `accepts the file the manifest describes`() {
        val data = RouteUpdate.verify(file, manifest, current)
        assertEquals(newer, data.dateSeconds)
        assertEquals(10, data.flightCount)
    }

    @Test
    fun `refuses a file that doesn't match the manifest`() {
        val cases = listOf(
            "size" to file.copyOf(file.size - 1),
            "checksum" to file.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() },
        )
        for ((what, bytes) in cases) {
            val e = assertThrows(what, IOException::class.java) { RouteUpdate.verify(bytes, manifest, current) }
            assertTrue(e.message, what in e.message!!)
        }
    }

    @Test
    fun `refuses a file whose own date or format is not what the manifest says`() {
        val otherDate = RouteFileWriter.file(RouteFileWriter.SAMPLE, date = newer + 1)
        assertThrows(IOException::class.java) { RouteUpdate.verify(otherDate, manifestFor(otherDate, newer), current) }
        val v2 = RouteFileWriter.gzip(RouteFileWriter.raw(RouteFileWriter.SAMPLE, date = newer, version = 2))
        assertThrows(IOException::class.java) { RouteUpdate.verify(v2, manifestFor(v2, newer), current) }
        val notRoutes = RouteFileWriter.gzip("hello".toByteArray())
        assertThrows(IOException::class.java) { RouteUpdate.verify(notRoutes, manifestFor(notRoutes, newer), current) }
    }

    @Test
    fun `refuses a file that is not newer than the routes in use`() {
        val e = assertThrows(IOException::class.java) { RouteUpdate.verify(file, manifest, newer) }
        assertTrue(e.message, "not newer" in e.message!!)
    }

    @Test
    fun `stores atomically, replacing the old file`() {
        val dir = Files.createTempDirectory("routes").toFile()
        val target = File(dir, "routes/routes-v1.bin")
        RouteUpdate.store(byteArrayOf(1, 2, 3), target)
        RouteUpdate.store(file, target)
        assertArrayEquals(file, target.readBytes())
        assertEquals(listOf("routes-v1.bin"), target.parentFile!!.list()!!.toList())
    }

    @Test
    fun `a failed store leaves the old file`() {
        val dir = Files.createTempDirectory("routes").toFile()
        val target = File(dir, "routes-v1.bin")
        RouteUpdate.store(file, target)
        // The temporary file's place is taken by a directory, so writing it fails.
        File(dir, "routes-v1.bin.tmp").mkdirs()
        assertThrows(IOException::class.java) { RouteUpdate.store(byteArrayOf(9), target) }
        assertArrayEquals(file, target.readBytes())
    }

    private fun manifestFor(bytes: ByteArray, date: Long) =
        RouteManifest(1, date, bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
}
