package io.github.mahendrabishnoi2.flyoff.routes

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The flight routes, read once per process and kept for lookups: the file bundled in the APK,
 * or a newer one the user downloaded ([RouteUpdates]) if it is readable. Any problem with the
 * downloaded file falls back to the bundled one, so flight numbers work with zero downloads.
 */
// Plain android.util.Log, like the route map downloads.
@SuppressLint("LogNotTimber")
object RouteCatalog {

    private const val TAG = "Flyoff"

    /** The bundled routes file, in the APK's assets. */
    const val ASSET: String = "routes/routes.bin"

    private val lock = Mutex()
    private val mutableData = MutableStateFlow<RouteData?>(null)

    /** The routes in use, once loaded. */
    val data: StateFlow<RouteData?> = mutableData.asStateFlow()

    /** Where a downloaded update is kept, in app storage. */
    fun downloadedFile(context: Context): File = File(context.filesDir, "routes/routes-v${RouteFile.FORMAT_VERSION}.bin")

    /** The routes, read off the main thread on first use. Throws [IOException] if even the bundled file can't be read (a bug). */
    suspend fun load(context: Context): RouteData {
        data.value?.let { return it }
        return lock.withLock {
            data.value ?: withContext(Dispatchers.IO) { read(context.applicationContext) }.also { mutableData.value = it }
        }
    }

    /** Uses [routes], a verified download just stored, from now on. */
    fun replace(routes: RouteData) {
        mutableData.value = routes
    }

    /** The bundled file's header (cheap: only its first bytes are decompressed). */
    fun bundledHeader(context: Context): RouteFile.Header = context.assets.open(ASSET).use { RouteFile.header(it) }

    /**
     * Whether to use a downloaded file with header [downloaded] rather than the bundled one with
     * [bundled]: only a file of this app's format that is newer than the bundled one (an app
     * update may bring newer data than an old download).
     */
    fun preferDownloaded(bundled: RouteFile.Header, downloaded: RouteFile.Header?): Boolean =
        downloaded != null && downloaded.formatVersion == RouteFile.FORMAT_VERSION && downloaded.dateSeconds > bundled.dateSeconds

    private fun read(context: Context): RouteData {
        val bundled = bundledHeader(context)
        val file = downloadedFile(context)
        if (file.isFile) {
            val downloaded = try {
                file.inputStream().use { RouteFile.header(it) }
            } catch (e: IOException) {
                Log.w(TAG, "routes: the downloaded file is unreadable (${e.message}); using the bundled one")
                null
            }
            if (preferDownloaded(bundled, downloaded)) {
                try {
                    return RouteFile.read(file.readBytes()).also { Log.i(TAG, "routes: downloaded file of ${it.dateSeconds}") }
                } catch (e: IOException) {
                    Log.w(TAG, "routes: the downloaded file is damaged (${e.message}); using the bundled one")
                }
            }
            // Older than the bundled data, another format or damaged: never useful again.
            file.delete()
        }
        return context.assets.open(ASSET).use { RouteFile.read(it) }.also { Log.i(TAG, "routes: bundled file of ${it.dateSeconds}") }
    }
}
