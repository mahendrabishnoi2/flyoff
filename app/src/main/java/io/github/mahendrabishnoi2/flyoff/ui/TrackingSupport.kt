package io.github.mahendrabishnoi2.flyoff.ui

import android.location.Location
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.github.mahendrabishnoi2.flyoff.flight.DeadReckoning
import kotlinx.coroutines.delay

/** How often an estimated position moves on. */
private const val ESTIMATE_TICK_MS = 1_000L

/**
 * How old [location] is, in milliseconds of `elapsedRealtime` (from the fix's
 * `elapsedRealtimeNanos`, so changes to the phone's clock don't matter); 0 without a fix.
 *
 * It doesn't tick while the fix is fresh: one wake-up when it turns stale, then once a second so
 * an estimate can move, only while [enabled] (a flight is set) and the app is visible. Fixes
 * arriving keep resetting it, so with a working GPS nothing extra recomposes.
 */
@Composable
fun rememberFixAgeMillis(location: Location?, enabled: Boolean): Long {
    var nowNanos by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(location, enabled, visible) {
        nowNanos = SystemClock.elapsedRealtimeNanos()
        if (location == null || !enabled || !visible) return@LaunchedEffect
        val age = (nowNanos - location.elapsedRealtimeNanos) / 1_000_000
        if (age < DeadReckoning.STALE_AFTER_MILLIS) delay(DeadReckoning.STALE_AFTER_MILLIS - age)
        while (true) {
            nowNanos = SystemClock.elapsedRealtimeNanos()
            delay(ESTIMATE_TICK_MS)
        }
    }
    // A fix newer than the last reading of the clock (it arrived since) is 0 ms old, not negative.
    return location?.let { (maxOf(nowNanos, it.elapsedRealtimeNanos) - it.elapsedRealtimeNanos) / 1_000_000 } ?: 0L
}

/** Whether the app may post notifications, read again each time it comes back to the foreground. */
@Composable
fun rememberNotificationsEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(true) }
    LifecycleResumeEffect(context) {
        enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    return enabled
}
