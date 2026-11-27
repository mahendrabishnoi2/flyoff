package io.github.mahendrabishnoi2.flyoff.routes

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** What the update manifest says about the routes file next to it. */
data class RouteManifest(
    val formatVersion: Int,
    /** The file's date (its VRS snapshot's time), in Unix seconds. */
    val dateSeconds: Long,
    /** The file's size in bytes. */
    val size: Long,
    /** The file's SHA-256, 64 lower-case hex digits. */
    val sha256: String,
)

/**
 * The rules of the flight data update, without the network: where it comes from, which
 * manifest and file are accepted, and how the file is stored. [RouteUpdates] does the rest.
 *
 * The data comes only from Flyoff's own GitHub Release (tag `routes-data`), which
 * `.github/workflows/routes.yml` refreshes weekly with a manifest (`routes-v1.json`) and the
 * file (`routes-v1.bin`), both named after the format so a new format never reaches an old app.
 */
object RouteUpdate {

    const val RELEASE_URL: String = "https://github.com/mahendrabishnoi2/flyoff/releases/download/routes-data/"
    val MANIFEST_URL: String = "${RELEASE_URL}routes-v${RouteFile.FORMAT_VERSION}.json"
    val FILE_URL: String = "${RELEASE_URL}routes-v${RouteFile.FORMAT_VERSION}.bin"

    /** The manifest is a few hundred bytes; anything much bigger isn't one. */
    const val MAX_MANIFEST_BYTES: Long = 16_384

    /** The file is about 1 MB; the build refuses more than 3 MB. Anything over this isn't ours. */
    const val MAX_FILE_BYTES: Long = 10_000_000

    private val sha256Pattern = Regex("[0-9a-f]{64}")

    /** The manifest in [json], or null when it isn't a valid manifest for this app's format. */
    fun parseManifest(json: String): RouteManifest? = try {
        val o = JSONObject(json)
        val version = o.get("format_version")
        val manifest = RouteManifest(
            formatVersion = if (version is Int) version else return null,
            dateSeconds = o.getLong("date_seconds"),
            size = o.getLong("size"),
            sha256 = o.getString("sha256"),
        )
        manifest.takeIf {
            it.formatVersion == RouteFile.FORMAT_VERSION && it.dateSeconds > 0 && it.size in 1..MAX_FILE_BYTES &&
                sha256Pattern.matches(it.sha256)
        }
    } catch (e: JSONException) {
        null
    }

    /** Whether [manifest] offers data newer than the routes in use, dated [currentSeconds]. */
    fun isNewer(manifest: RouteManifest, currentSeconds: Long): Boolean = manifest.dateSeconds > currentSeconds

    /**
     * The routes in the downloaded [bytes], checked against [manifest] (size, SHA-256) and
     * against themselves (format, the same date as the manifest, newer than [currentSeconds]).
     *
     * @throws IOException naming the first check that failed.
     */
    fun verify(bytes: ByteArray, manifest: RouteManifest, currentSeconds: Long): RouteData {
        if (bytes.size.toLong() != manifest.size) throw IOException("size ${bytes.size}, the manifest says ${manifest.size}")
        val sha = sha256(bytes)
        if (sha != manifest.sha256) throw IOException("checksum $sha, the manifest says ${manifest.sha256}")
        val data = RouteFile.read(bytes)
        if (data.dateSeconds != manifest.dateSeconds) throw IOException("date ${data.dateSeconds}, the manifest says ${manifest.dateSeconds}")
        if (data.dateSeconds <= currentSeconds) throw IOException("data of ${data.dateSeconds} is not newer than $currentSeconds")
        return data
    }

    /**
     * Writes [bytes] to [target] atomically: to a temporary file next to it, synced to disk, then
     * renamed over it. On failure the old [target] is left as it was.
     */
    fun store(bytes: ByteArray, target: File) {
        val dir = target.absoluteFile.parentFile ?: throw IOException("no directory for $target")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("can't create $dir")
        val tmp = File(dir, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use {
                it.write(bytes)
                it.fd.sync()
            }
            if (!tmp.renameTo(target)) throw IOException("can't rename $tmp to $target")
        } catch (e: IOException) {
            if (tmp.isFile) tmp.delete()
            throw e
        }
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
