package io.github.mahendrabishnoi2.flyoff.airports

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The bundled airports, read once per process and kept for searching. Rotation and every other
 * screen get the same [AirportSearch]; a failed read is not kept, so it can be tried again.
 */
object AirportCatalog {

    private val lock = Mutex()

    @Volatile
    var loaded: AirportSearch? = null
        private set

    /** The search over all bundled airports, read and indexed off the main thread on first use. */
    suspend fun load(context: Context): AirportSearch {
        loaded?.let { return it }
        return lock.withLock {
            loaded ?: withContext(Dispatchers.Default) {
                AirportSearch(AirportAssets.load(context.applicationContext))
            }.also { loaded = it }
        }
    }
}
