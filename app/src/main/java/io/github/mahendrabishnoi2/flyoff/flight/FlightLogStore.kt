package io.github.mahendrabishnoi2.flyoff.flight

import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The flight log on disk: one gzipped [FlightRecordFormat] file per flight in [dir], named
 * `<id>.flight.gz`. A flight is about 12 kB per hour of flight (see FlightLogStoreTest).
 *
 * Saving writes a temporary file and renames it over the old one, so a flight is never half
 * written; saving the same flight again (the same [FlightRecord.id]) replaces it. Files that
 * can't be read are left alone and skipped.
 *
 * Plain file I/O: call it off the main thread, one call at a time.
 */
class FlightLogStore(private val dir: File) {

    /** Every readable flight's entry, newest first. */
    fun entries(): List<FlightEntry> {
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.name.endsWith(SUFFIX) && FlightRecord.isValidId(it.name.removeSuffix(SUFFIX)) }
            .mapNotNull(::readEntry)
            .sortedWith(compareByDescending<FlightEntry> { it.startMillis }.thenByDescending { it.id })
    }

    /** The flight [id], or null when there is none (or it can't be read). */
    fun read(id: String): FlightRecord? {
        val file = fileOf(id) ?: return null
        return try {
            GZIPInputStream(file.inputStream()).use { FlightRecordFormat.decode(it.readBytes().toString(Charsets.UTF_8)) }
        } catch (_: IOException) {
            null
        }
    }

    /** Writes [record], replacing an earlier copy of the same flight; returns the file's size in bytes. */
    fun save(record: FlightRecord): Long {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("can't create $dir")
        val file = File(dir, record.id + SUFFIX)
        val temp = File(dir, record.id + SUFFIX + ".tmp")
        try {
            GZIPOutputStream(temp.outputStream()).use { it.write(FlightRecordFormat.encode(record).toByteArray(Charsets.UTF_8)) }
            if (!temp.renameTo(file)) throw IOException("can't rename $temp")
        } finally {
            temp.delete()
        }
        return file.length()
    }

    /** Deletes the flight [id]; false when there was none. */
    fun delete(id: String): Boolean = fileOf(id)?.delete() ?: false

    private fun fileOf(id: String): File? =
        if (FlightRecord.isValidId(id)) File(dir, id + SUFFIX).takeIf { it.isFile } else null

    private fun readEntry(file: File): FlightEntry? = try {
        GZIPInputStream(file.inputStream()).bufferedReader(Charsets.UTF_8).use { reader ->
            FlightRecordFormat.decodeEntry(reader.lineSequence())
        }
    } catch (_: IOException) {
        null
    }

    private companion object {
        const val SUFFIX = ".flight.gz"
    }
}
