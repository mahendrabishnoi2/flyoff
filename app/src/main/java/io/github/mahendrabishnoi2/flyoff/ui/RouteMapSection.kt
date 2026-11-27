package io.github.mahendrabishnoi2.flyoff.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mahendrabishnoi2.flyoff.R
import io.github.mahendrabishnoi2.flyoff.download.CorridorPlan
import io.github.mahendrabishnoi2.flyoff.download.DownloadKind
import io.github.mahendrabishnoi2.flyoff.download.DownloadProblem
import io.github.mahendrabishnoi2.flyoff.download.FlightMaps
import io.github.mahendrabishnoi2.flyoff.download.RunningDownload

/** What the flight card shows about the flight's route maps. */
data class RouteMapControls(
    /** The route download plan, or null while it is worked out. */
    val routePlan: CorridorPlan?,
    /** The detailed download plan, or null (worked out once the route map is ready). */
    val detailedPlan: CorridorPlan?,
    /** What is stored for this flight, or null for nothing. */
    val maps: FlightMaps?,
    /** This flight's running download, or null. */
    val running: RunningDownload?,
    /** Another flight's map is downloading: no download can start. */
    val otherRunning: Boolean,
    val problem: DownloadProblem?,
    val mobileDataAllowed: Boolean,
    /** While tracking: only the "Ready for offline" badge, if it applies. */
    val compact: Boolean,
)

/**
 * The route map part of the flight card: before the first tap the total size and number of
 * downloads, then one button per download (one tap = one download), progress with Cancel while
 * one runs, and "Ready for offline ✓" once every part of the route map is stored, with the
 * optional detailed map below it.
 */
@Composable
fun RouteMapSection(
    controls: RouteMapControls,
    onDownload: (CorridorPlan, Int) -> Unit,
    onCancel: () -> Unit,
    onReplace: () -> Unit,
    onOpenStoredMaps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val maps = controls.maps
    val ready = maps?.isReady(DownloadKind.ROUTE) == true
    if (controls.compact) {
        if (ready) ReadyBadge(modifier)
        return
    }
    val locale = LocalConfiguration.current.locales[0]
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ready) {
                ReadyBadge(Modifier.weight(1f, fill = false))
            } else {
                Text(stringResource(R.string.route_map_title), style = MaterialTheme.typography.labelLarge)
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onOpenStoredMaps) { Text(stringResource(R.string.stored_maps)) }
            }
        }
        val running = controls.running
        if (running != null) {
            Running(running, onCancel)
            return@Column
        }
        controls.problem?.let { problem ->
            Text(
                text = stringResource(
                    when (problem) {
                        DownloadProblem.WIFI_REQUIRED -> R.string.problem_wifi_required
                        DownloadProblem.NO_CONNECTION -> R.string.problem_no_connection
                        DownloadProblem.OVER_LIMIT -> R.string.problem_over_limit
                        DownloadProblem.BUILD_GONE -> R.string.problem_build_gone
                        DownloadProblem.STORAGE -> R.string.problem_storage
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (problem == DownloadProblem.BUILD_GONE) {
                OutlinedButton(onClick = onReplace, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.replace_maps)) }
            }
        }
        val plan = controls.routePlan
        if (plan == null) {
            Text(stringResource(R.string.route_map_planning), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val parts = plan.chunks.size
        val detailedConflict = controls.detailedPlan?.let { maps?.conflictsWith(DownloadKind.DETAILED, it.chunks.size) } == true
        if (maps != null && (maps.conflictsWith(DownloadKind.ROUTE, parts) || detailedConflict)) {
            Text(stringResource(R.string.problem_other_version), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onReplace, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.replace_maps)) }
            return@Column
        }
        if (!ready) {
            val done = maps?.completeChunks(DownloadKind.ROUTE)?.size ?: 0
            val summary = when {
                done > 0 -> pluralStringResource(R.plurals.route_map_parts_done, parts, done, parts)
                parts == 1 -> stringResource(R.string.route_map_summary_one, formatUpToMegabytes(plan.totalEstimatedBytes, locale))
                else -> pluralStringResource(R.plurals.route_map_summary_parts, parts, formatUpToMegabytes(plan.totalEstimatedBytes, locale), parts)
            }
            val network = stringResource(if (controls.mobileDataAllowed) R.string.route_map_wifi_or_mobile else R.string.route_map_wifi_only)
            Text("$summary $network", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val next = maps?.nextChunk(DownloadKind.ROUTE, parts) ?: 0
            if (maps?.hasStarted(DownloadKind.ROUTE, next) == true) Unfinished()
            val size = formatUpToMegabytes(plan.chunks[next].estimatedBytes, locale)
            DownloadButton(
                text = if (parts == 1) stringResource(R.string.download_route_map, size) else stringResource(R.string.download_route_part, next + 1, parts, size),
                enabled = !controls.otherRunning,
                onClick = { onDownload(plan, next) },
            )
        } else {
            val detailed = controls.detailedPlan ?: return@Column
            val detailedParts = detailed.chunks.size
            val next = maps.nextChunk(DownloadKind.DETAILED, detailedParts)
            if (next == null) {
                Text(stringResource(R.string.detailed_map_ready), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val total = formatUpToMegabytes(detailed.totalEstimatedBytes, locale)
                Text(
                    text = if (detailedParts == 1) stringResource(R.string.detailed_map_summary, total) else pluralStringResource(R.plurals.detailed_map_summary_parts, detailedParts, total, detailedParts),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (maps.hasStarted(DownloadKind.DETAILED, next)) Unfinished()
                val size = formatUpToMegabytes(detailed.chunks[next].estimatedBytes, locale)
                DownloadButton(
                    text = if (detailedParts == 1) stringResource(R.string.download_detailed_map, size) else stringResource(R.string.download_detailed_part, next + 1, detailedParts, size),
                    enabled = !controls.otherRunning,
                    onClick = { onDownload(detailed, next) },
                )
            }
        }
        if (controls.otherRunning) {
            Text(stringResource(R.string.download_other_flight), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Unfinished() {
    Text(stringResource(R.string.download_unfinished), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ReadyBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.route_map_ready),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun DownloadButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        FilledTonalButton(onClick = onClick, enabled = enabled) { Text(text) }
    }
}

@Composable
private fun Running(running: RunningDownload, onCancel: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val detailed = running.kind == DownloadKind.DETAILED
    val title = when {
        running.chunkCount == 1 -> stringResource(if (detailed) R.string.downloading_detailed_map else R.string.downloading_route_map)
        else -> stringResource(if (detailed) R.string.downloading_detailed_part else R.string.downloading_route_part, running.chunkIndex + 1, running.chunkCount)
    }
    Text(title, style = MaterialTheme.typography.bodyMedium)
    LinearProgressIndicator(progress = { running.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    R.string.download_progress,
                    formatProgress(running.fraction, locale),
                    formatMegabytes(running.bytes, locale),
                    formatUpToMegabytes(running.estimatedBytes, locale),
                ),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = stringResource(if (running.retrying) R.string.download_retrying else R.string.download_keep_open),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
}
