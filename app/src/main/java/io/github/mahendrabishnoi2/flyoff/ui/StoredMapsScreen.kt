package io.github.mahendrabishnoi2.flyoff.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mahendrabishnoi2.flyoff.R
import io.github.mahendrabishnoi2.flyoff.download.CorridorRetention
import io.github.mahendrabishnoi2.flyoff.download.DownloadKind
import io.github.mahendrabishnoi2.flyoff.download.FlightMaps
import io.github.mahendrabishnoi2.flyoff.download.RouteMapsState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The stored maps, grouped by flight: what each flight has, its size on disk and date, when it
 * will be deleted, and Delete; the total; and the mobile data setting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoredMapsScreen(
    state: RouteMapsState,
    onMobileDataAllowed: (Boolean) -> Unit,
    onDelete: (FlightMaps) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val locale = LocalConfiguration.current.locales[0]
    var confirm by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stored_maps)) },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = state.mobileDataAllowed, role = Role.Switch, onValueChange = onMobileDataAllowed),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.use_mobile_data), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.use_mobile_data_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.mobileDataAllowed, onCheckedChange = null)
            }
            if (state.flights.isEmpty()) {
                Text(stringResource(R.string.stored_maps_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    text = stringResource(R.string.stored_maps_total, formatMegabytes(state.flights.sumOf { it.bytes }, locale)),
                    style = MaterialTheme.typography.titleMedium,
                )
                for (flight in state.flights) {
                    val running = state.running?.let { it.origin == flight.origin && it.destination == flight.destination } == true
                    FlightMapsCard(flight, running, locale, onDelete = { confirm = flight.origin to flight.destination })
                }
            }
            Text(stringResource(R.string.stored_maps_rule), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    confirm?.let { (origin, destination) ->
        val flight = state.flights.find { it.origin == origin && it.destination == destination }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.delete_maps_title, stringResource(R.string.flight_route, origin, destination))) },
            text = { Text(stringResource(R.string.delete_maps_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = null
                        flight?.let(onDelete)
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun FlightMapsCard(flight: FlightMaps, running: Boolean, locale: Locale, onDelete: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.flight_route, flight.origin, flight.destination),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            for (kind in DownloadKind.entries) {
                val count = flight.regions(kind).maxOfOrNull { it.metadata.chunkCount } ?: continue
                val done = flight.completeChunks(kind).size
                Text(
                    text = pluralStringResource(if (kind == DownloadKind.ROUTE) R.plurals.stored_route_parts else R.plurals.stored_detailed_parts, count, done, count),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = stringResource(R.string.stored_size_date, formatMegabytes(flight.bytes, locale), formatDate(flight.createdAtMillis, locale)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val landed = flight.landedAtMillis
            Text(
                text = when {
                    running -> stringResource(R.string.stored_downloading)
                    landed != null -> stringResource(R.string.stored_deleted_on, formatDate(CorridorRetention.deleteAtMillis(landed), locale))
                    else -> stringResource(R.string.stored_kept)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
            }
        }
    }
}

private fun formatDate(millis: Long, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
