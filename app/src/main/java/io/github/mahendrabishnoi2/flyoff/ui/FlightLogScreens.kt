package io.github.mahendrabishnoi2.flyoff.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mahendrabishnoi2.flyoff.R
import io.github.mahendrabishnoi2.flyoff.airports.AirportCatalog
import io.github.mahendrabishnoi2.flyoff.flight.ExportFlight
import io.github.mahendrabishnoi2.flyoff.flight.FlightEntry
import io.github.mahendrabishnoi2.flyoff.flight.FlightRecord
import io.github.mahendrabishnoi2.flyoff.flight.FlightSummary
import io.github.mahendrabishnoi2.flyoff.flight.FlightSummaryCalculator
import io.github.mahendrabishnoi2.flyoff.flight.GpxWriter
import io.github.mahendrabishnoi2.flyoff.flight.KmlWriter
import io.github.mahendrabishnoi2.flyoff.flight.TrackExport
import io.github.mahendrabishnoi2.flyoff.flight.toExport
import io.github.mahendrabishnoi2.flyoff.geo.Geodesy
import io.github.mahendrabishnoi2.flyoff.geo.unwrapLongitudes
import io.github.mahendrabishnoi2.flyoff.map.MAX_ZOOM
import io.github.mahendrabishnoi2.flyoff.map.MapInsets
import io.github.mahendrabishnoi2.flyoff.map.MapLibreMap
import io.github.mahendrabishnoi2.flyoff.map.MapRoute
import io.github.mahendrabishnoi2.flyoff.tracking.FlightLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Every flight in the log, newest first: date, route, duration and distance; simulated ones say
 * so. Tapping one opens its summary. [hiddenId] (the flight being tracked right now) isn't listed:
 * it isn't a past flight yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(entries: List<FlightEntry>?, hiddenId: String?, onOpen: (String) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val locale = LocalConfiguration.current.locales[0]
    val shown = entries?.filter { it.id != hiddenId }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.flight_history)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close))
                    }
                },
            )
        },
    ) { padding ->
        when {
            shown == null -> Unit
            shown.isEmpty() -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.history_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.history_empty_body), style = MaterialTheme.typography.bodyMedium)
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(padding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 16.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shown, key = { it.id }) { entry -> HistoryCard(entry, locale, onClick = { onOpen(entry.id) }) }
            }
        }
    }
}

@Composable
private fun HistoryCard(entry: FlightEntry, locale: Locale, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.flight_route, entry.codes.origin, entry.codes.destination),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    R.string.history_entry,
                    formatLogDate(entry.startMillis, locale),
                    formatDuration(entry.durationMillis / 1000),
                    formatDistance(entry.distanceMeters, locale),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (entry.simulated) {
                Text(
                    text = stringResource(R.string.simulated_flight),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

/**
 * The summary of the logged flight [id]: the route, the track on the offline map, distance
 * flown against the great circle, duration, the GPS maxima, average speed and the time on an
 * estimated position. From here the flight is exported (GPX or KML, to a place the user picks
 * with Android's file picker: no storage permission, nothing sent anywhere) or deleted.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FlightSummaryScreen(id: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    var loaded by remember(id) { mutableStateOf<LoadedFlight?>(null) }
    LaunchedEffect(id) {
        val record = FlightLog.read(context, id)
        loaded = LoadedFlight(record, record?.let(FlightSummaryCalculator::compute))
    }
    var message by remember(id) { mutableStateOf<ExportMessage?>(null) }
    var confirmDelete by rememberSaveable(id) { mutableStateOf(false) }
    val record = loaded?.record
    fun exportTo(uri: Uri?, write: (ExportFlight, Appendable) -> Unit) {
        val flight = record ?: return
        if (uri == null) return
        scope.launch {
            val name = exportFlight(context, uri, flight, write)
            message = if (name != null) ExportMessage.Saved(name) else ExportMessage.Failed
        }
    }
    val gpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(GPX_MIME)) { exportTo(it, GpxWriter::write) }
    val kml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(KML_MIME)) { exportTo(it, KmlWriter::write) }
    fun pick(launch: (String) -> Unit, extension: String) {
        val flight = record ?: return
        try {
            launch(TrackExport.fileName(flight, extension, ZoneId.systemDefault()))
        } catch (_: ActivityNotFoundException) {
            message = ExportMessage.NoPicker
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.flight_summary)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            val current = loaded
            when {
                current == null -> Text(stringResource(R.string.summary_loading), Modifier.padding(16.dp))
                current.record == null || current.summary == null -> Text(stringResource(R.string.summary_missing), Modifier.padding(16.dp))
                else -> {
                    SummaryMap(current.record)
                    SummaryDetails(current.record, current.summary, locale)
                    // The actions right under the numbers, so they are on screen without scrolling.
                    FlowRow(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = { pick(gpx::launch, "gpx") }) { Text(stringResource(R.string.export_gpx)) }
                        OutlinedButton(onClick = { pick(kml::launch, "kml") }) { Text(stringResource(R.string.export_kml)) }
                        TextButton(onClick = { confirmDelete = true }) {
                            Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        message?.let { Text(it.text(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
                        SummaryNote(stringResource(R.string.summary_note))
                        SummaryNote(stringResource(R.string.export_note))
                    }
                }
            }
        }
    }
    if (confirmDelete && record != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_flight_title, stringResource(R.string.flight_route, record.codes.origin, record.codes.destination))) },
            text = { Text(stringResource(R.string.delete_flight_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        // Closes this summary too.
                        FlightLog.deleteLater(context, id)
                    },
                ) { Text(stringResource(R.string.delete_flight)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private class LoadedFlight(val record: FlightRecord?, val summary: FlightSummary?)

private sealed interface ExportMessage {
    data class Saved(val name: String) : ExportMessage

    data object Failed : ExportMessage

    data object NoPicker : ExportMessage
}

@Composable
private fun ExportMessage.text(): String = when (this) {
    is ExportMessage.Saved -> stringResource(R.string.export_saved, name)
    ExportMessage.Failed -> stringResource(R.string.export_failed)
    ExportMessage.NoPicker -> stringResource(R.string.export_no_picker)
}

private const val GPX_MIME = "application/gpx+xml"
private const val KML_MIME = "application/vnd.google-earth.kml+xml"

/** The flight's great circle and its track on the offline map, both in view. Nothing to tap: it only shows. */
@Composable
private fun SummaryMap(record: FlightRecord) {
    val route = remember(record) {
        if (record.origin != null && record.destination != null) {
            MapRoute(Geodesy.greatCirclePoints(record.origin, record.destination), record.codes.origin, record.codes.destination)
        } else {
            null
        }
    }
    val track = remember(record) { unwrapLongitudes(record.points.map { it.latLon }) }
    val fit = remember(route, track) { unwrapLongitudes(route?.points.orEmpty() + track) }
    val margin = with(LocalDensity.current) { 24.dp.roundToPx() }
    MapLibreMap(
        location = null,
        showLocation = false,
        estimated = false,
        following = false,
        onFollowingChange = {},
        route = route,
        fitRoute = true,
        routePadding = MapInsets(margin, margin, margin, margin),
        onCameraMovedByUser = {},
        ornamentInsets = MapInsets(),
        onLoadFailed = {},
        track = track,
        trackHead = null,
        styleJson = null,
        maxZoom = MAX_ZOOM,
        onCorridorShown = {},
        fitPoints = fit,
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryDetails(record: FlightRecord, summary: FlightSummary, locale: Locale) {
    val airports = AirportCatalog.loaded
    val origin = airports?.byIata(record.codes.origin)?.name
    val destination = airports?.byIata(record.codes.destination)?.name
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val routeText = stringResource(R.string.flight_route, record.codes.origin, record.codes.destination)
        val routeDescription = if (origin != null && destination != null) {
            stringResource(R.string.flight_route_description, origin, destination)
        } else {
            routeText
        }
        Text(
            text = routeText,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics {
                heading()
                contentDescription = routeDescription
            },
        )
        Text(
            text = stringResource(
                R.string.summary_times,
                formatLogDate(record.startMillis, locale),
                formatLogTime(record.startMillis, locale),
                formatLogTime(record.endMillis, locale),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (record.simulated) {
            Text(
                text = stringResource(R.string.simulated_flight),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        FlowRow(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            SummaryStat(stringResource(R.string.summary_distance_flown), formatDistance(summary.distanceFlownMeters, locale))
            SummaryStat(stringResource(R.string.summary_great_circle), formatDistance(summary.greatCircleMeters, locale))
            SummaryStat(stringResource(R.string.summary_duration), formatDuration(summary.durationMillis / 1000))
            SummaryStat(stringResource(R.string.summary_max_altitude), formatAltitude(summary.maxAltitudeMeters, locale))
            SummaryStat(stringResource(R.string.summary_max_speed), formatSpeed(summary.maxGroundSpeedMetersPerSecond, locale))
            SummaryStat(stringResource(R.string.summary_average_speed), formatSpeed(summary.averageSpeedMetersPerSecond, locale))
            SummaryStat(stringResource(R.string.summary_estimated), formatDuration(summary.estimatedMillis / 1000))
        }
    }
}

@Composable
private fun SummaryNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A label over its value; read (by screen readers and the CI smoke test) as "Label: value". */
@Composable
private fun SummaryStat(label: String, value: String) {
    val description = stringResource(R.string.stat_description, label, value)
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
    }
}

/**
 * Writes [record] with [write] to [uri] (a document the user just created with Android's file
 * picker) and returns the name it got there, or null if it couldn't be written.
 */
private suspend fun exportFlight(context: Context, uri: Uri, record: FlightRecord, write: (ExportFlight, Appendable) -> Unit): String? =
    withContext(Dispatchers.IO) {
        val airports = AirportCatalog.loaded
        val flight = record.toExport { code -> airports?.byIata(code)?.name }
        try {
            val stream = context.contentResolver.openOutputStream(uri) ?: return@withContext null
            stream.bufferedWriter(Charsets.UTF_8).use { write(flight, it) }
            displayName(context, uri) ?: uri.lastPathSegment ?: ""
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

private fun displayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
} catch (_: RuntimeException) {
    null
}

private fun formatLogDate(millis: Long, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

private fun formatLogTime(millis: Long, locale: Locale): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
