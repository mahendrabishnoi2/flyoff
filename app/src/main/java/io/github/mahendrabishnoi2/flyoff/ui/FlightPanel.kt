package io.github.mahendrabishnoi2.flyoff.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mahendrabishnoi2.flyoff.R
import io.github.mahendrabishnoi2.flyoff.flight.FlightPlan
import io.github.mahendrabishnoi2.flyoff.flight.FlightStats
import io.github.mahendrabishnoi2.flyoff.flight.SimulationTimeline
import java.text.NumberFormat
import kotlin.math.floor

/** What the simulator controls show. */
data class SimulatorControls(
    val speed: Int,
    val running: Boolean,
    val atStart: Boolean,
    val finished: Boolean,
    /** Whether the simulated GPS drops out during the gaps. */
    val gaps: Boolean,
)

/** A line under the stats: the "GPS ready" check, or that the dot is an estimate. */
data class StatusLine(val text: String, val estimated: Boolean)

/** What the tracking row shows. */
data class TrackingControls(
    /** Tracking may be started: a flight is set, precise location is granted, not simulating. */
    val canStart: Boolean,
    val active: Boolean,
    /** Points recorded so far. */
    val points: Int,
    /** Notifications are off, so the notification is missing (tracking still runs). */
    val notificationsOff: Boolean,
)

/**
 * The flight card in the bottom panel: the route, then the live stats once there is a position
 * (real, simulated or estimated), a status line, then either the simulator's controls or
 * tracking and the actions. Tracking isn't offered while simulating, nor the simulator while
 * tracking.
 *
 * The simulator controls are the card's last row, so they keep their place on screen as the
 * stats above them change (the panel grows upwards from the bottom).
 */
@Composable
fun FlightCard(
    plan: FlightPlan,
    stats: FlightStats?,
    eta: EtaText?,
    simulator: SimulatorControls?,
    statusLine: StatusLine?,
    tracking: TrackingControls,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onChange: () -> Unit,
    onSimulate: () -> Unit,
    onSimulatorPlayPause: () -> Unit,
    onSimulatorSpeed: (Int) -> Unit,
    onSimulatorStop: () -> Unit,
    onSimulatorGaps: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val routeDescription = stringResource(R.string.flight_route_description, plan.origin.name, plan.destination.name)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.flight_route, plan.origin.iata, plan.destination.iata),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics {
                        heading()
                        contentDescription = routeDescription
                    },
                )
                Text(
                    text = stringResource(R.string.route_length, formatDistance(plan.routeLengthMeters, locale)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                )
                if (simulator == null) {
                    TextButton(onClick = onChange) { Text(stringResource(R.string.change_flight)) }
                }
            }
            if (stats != null) {
                Progress(stats.progress, formatProgress(stats.progress, locale))
                StatsGrid(stats, eta ?: EtaText(UNKNOWN, inUtc = false))
            }
            if (statusLine != null) {
                Text(
                    text = statusLine.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (statusLine.estimated) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                simulator != null -> SimulatorBar(simulator, onSimulatorPlayPause, onSimulatorSpeed, onSimulatorStop, onSimulatorGaps)
                tracking.active -> TrackingBar(tracking, onStopTracking)
                else -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    TextButton(onClick = onSimulate) { Text(stringResource(R.string.simulate_flight)) }
                    if (tracking.canStart) {
                        FilledTonalButton(onClick = onStartTracking) { Text(stringResource(R.string.start_tracking)) }
                    }
                }
            }
        }
    }
}

/** While tracking: how much is recorded, and Stop. */
@Composable
private fun TrackingBar(tracking: TrackingControls, onStop: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = pluralStringResource(
                    R.plurals.recording_track,
                    tracking.points,
                    NumberFormat.getIntegerInstance(locale).format(tracking.points),
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onStop) { Text(stringResource(R.string.stop_tracking)) }
        }
        if (tracking.notificationsOff) {
            Text(
                text = stringResource(R.string.notifications_off),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Progress(progress: Double, text: String) {
    val description = stringResource(R.string.progress_description, text)
    // In steps of 0.1 %: the bar looks the same, and its semantics (read by accessibility
    // services and uiautomator) don't change every second while the dot moves.
    val stepped = (floor(progress.coerceIn(0.0, 1.0) * 1000) / 1000).toFloat()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        LinearProgressIndicator(progress = { stepped }, modifier = Modifier.weight(1f))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The stats in a grid of equal cells: three columns on a phone in portrait, more when wider,
 * fewer with large text ([statColumns]). The ETA takes two cells, as its text is the longest.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsGrid(stats: FlightStats, eta: EtaText) {
    val locale = LocalConfiguration.current.locales[0]
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = statColumns(maxWidth.value, LocalDensity.current.fontScale)
        val cell = maxWidth / columns
        FlowRow(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(stringResource(R.string.stat_remaining), formatDistance(stats.distanceRemainingMeters, locale), cell)
            Stat(stringResource(R.string.stat_time_left), formatDuration(stats.timeRemainingSeconds), cell)
            Stat(
                stringResource(if (eta.inUtc) R.string.stat_eta_utc else R.string.stat_eta),
                eta.text,
                cell * 2,
            )
            Stat(stringResource(R.string.stat_flown), formatDistance(stats.distanceFlownMeters, locale), cell)
            Stat(stringResource(R.string.stat_altitude), formatAltitude(stats.altitudeMeters, locale), cell)
            Stat(stringResource(R.string.stat_speed), formatSpeed(stats.groundSpeedMetersPerSecond, locale), cell)
            Stat(stringResource(R.string.stat_heading), formatHeading(stats.headingDegrees, locale), cell)
        }
    }
}

/** A label over its value. Screen readers (and the CI smoke test) read it as "Label: value". */
@Composable
private fun Stat(label: String, value: String, width: Dp) {
    val description = stringResource(R.string.stat_description, label, value)
    Column(
        Modifier
            .width(width)
            .padding(end = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = value, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The simulator's controls, on a tinted strip that says this is a simulation: start, pause or
 * resume; stop; the speed; and GPS gaps.
 */
@Composable
private fun SimulatorBar(
    controls: SimulatorControls,
    onPlayPause: () -> Unit,
    onSpeed: (Int) -> Unit,
    onStop: () -> Unit,
    onGaps: (Boolean) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.simulated_flight),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val playPause = when {
                    controls.running -> R.string.sim_pause
                    controls.finished -> R.string.sim_replay
                    controls.atStart -> R.string.sim_start
                    else -> R.string.sim_resume
                }
                // A fixed minimum width keeps the button in place as its label changes.
                TextButton(onClick = onPlayPause, modifier = Modifier.widthIn(min = 96.dp)) {
                    Text(stringResource(playPause))
                }
                TextButton(onClick = onStop) { Text(stringResource(R.string.sim_stop)) }
            }
            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                val speeds = SimulationTimeline.SPEEDS
                speeds.forEachIndexed { index, speed ->
                    val description = pluralStringResource(R.plurals.sim_speed_description, speed, speed)
                    SegmentedButton(
                        selected = speed == controls.speed,
                        onClick = { onSpeed(speed) },
                        shape = SegmentedButtonDefaults.itemShape(index, speeds.size),
                        icon = {},
                        modifier = Modifier.semantics { contentDescription = description },
                    ) {
                        Text(stringResource(R.string.sim_speed, speed), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = controls.gaps, role = Role.Switch, onValueChange = onGaps)
                    .padding(bottom = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.sim_gps_gaps),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = controls.gaps, onCheckedChange = null)
            }
        }
    }
}
