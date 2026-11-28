package io.github.mahendrabishnoi2.flyoff.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import io.github.mahendrabishnoi2.flyoff.flight.FlightEntry
import io.github.mahendrabishnoi2.flyoff.flight.FlightLogStore
import io.github.mahendrabishnoi2.flyoff.flight.FlightRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

/** What the UI shows of the flight log. */
data class FlightLogState(
    /** Every flight kept, newest first; null until the log has been read. */
    val entries: List<FlightEntry>? = null,
    /** The flight whose summary is open, or null. */
    val summaryId: String? = null,
)

/**
 * The flight log for the whole process: every tracked flight, kept in `filesDir/flights`
 * ([FlightLogStore]), and which summary is open. [Tracker] copies the track file in whenever
 * tracking ends; the simulator saves a simulated flight when it touches down. Nothing here uses
 * the network: the log only leaves the phone when the user exports a flight to a place they pick.
 *
 * All file work runs on [Tracker.fileIo], in order with the track file's writes.
 */
// Log lines, like RouteMaps': the CI smoke test reads them (no Timber dependency for a few lines).
@SuppressLint("LogNotTimber")
object FlightLog {
    private const val TAG = "Flyoff"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val mutableState = MutableStateFlow(FlightLogState())
    val state: StateFlow<FlightLogState> = mutableState.asStateFlow()

    private fun store(context: Context) = FlightLogStore(File(context.filesDir, "flights"))

    /** Reads the list of flights again. */
    suspend fun refresh(context: Context) {
        val entries = withContext(Tracker.fileIo) { store(context).entries() }
        mutableState.update { it.copy(entries = entries) }
    }

    /** The flight [id] with its track, or null when it is gone or can't be read. */
    suspend fun read(context: Context, id: String): FlightRecord? = withContext(Tracker.fileIo) { store(context).read(id) }

    /** Keeps [record] (replacing an earlier copy of the same flight); false if it couldn't be stored. */
    suspend fun save(context: Context, record: FlightRecord): Boolean = withContext(Tracker.fileIo) { saveNow(context, record) }

    /**
     * Deletes the flight [id]. If it is the flight in the track file (and not being tracked),
     * that goes too, with its line on the map and any offer to resume it: otherwise the next
     * time tracking ends the flight would be copied back into the log.
     */
    suspend fun delete(context: Context, id: String) {
        withContext(Tracker.fileIo) {
            store(context).delete(id)
            if (!Tracker.state.value.active) {
                val file = TrackFile(Tracker.trackFile(context))
                val contents = file.contents()
                val first = contents?.points?.firstOrNull()
                if (first != null && FlightRecord.idOf(contents.codes, first.timeMillis) == id) {
                    file.delete()
                    Tracker.onTrackDeleted(context)
                }
            }
            val entries = store(context).entries()
            mutableState.update { it.copy(entries = entries, summaryId = it.summaryId.takeIf { open -> open != id }) }
        }
        Log.i(TAG, "flight-deleted id=$id")
    }

    /** [save] in the process's own scope, then opens the flight's summary if [openSummary]. */
    fun saveLater(context: Context, record: FlightRecord, openSummary: Boolean) {
        val app = context.applicationContext
        scope.launch {
            if (save(app, record) && openSummary) openSummary(record.id)
        }
    }

    /** [delete], in the process's own scope: it finishes even if the screen that asked goes away. */
    fun deleteLater(context: Context, id: String) {
        val app = context.applicationContext
        scope.launch { delete(app, id) }
    }

    fun openSummary(id: String) {
        mutableState.update { it.copy(summaryId = id) }
    }

    fun closeSummary() {
        mutableState.update { it.copy(summaryId = null) }
    }

    /** [save] on [Tracker.fileIo] already. */
    internal fun saveNow(context: Context, record: FlightRecord): Boolean {
        val store = store(context)
        return try {
            val bytes = store.save(record)
            val hours = (record.endMillis - record.startMillis) / 3_600_000.0
            Log.i(
                TAG,
                "flight-saved id=${record.id} simulated=${record.simulated} points=${record.points.size} bytes=$bytes " +
                    "hours=${String.format(Locale.ROOT, "%.3f", hours)}",
            )
            val entries = store.entries()
            mutableState.update { it.copy(entries = entries) }
            true
        } catch (e: IOException) {
            Log.w(TAG, "flight-save-failed id=${record.id}: ${e.message}")
            false
        }
    }
}
