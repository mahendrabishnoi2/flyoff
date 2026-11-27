package io.github.mahendrabishnoi2.flyoff.location

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect

/**
 * True when Battery Saver is on in a mode that stops GPS with the screen off
 * ([gpsLimitedWhenScreenOff]; Android 9+ only). Read whenever the app comes to the foreground,
 * and again on each `ACTION_POWER_SAVE_MODE_CHANGED` while it is visible.
 */
@Composable
fun rememberBatterySaverLimitsGps(): Boolean {
    val context = LocalContext.current
    var limited by remember { mutableStateOf(false) }
    LifecycleStartEffect(context) {
        val powerManager = context.getSystemService(PowerManager::class.java)
        fun read() {
            limited = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                gpsLimitedWhenScreenOff(powerManager.isPowerSaveMode, powerManager.locationPowerSaveMode)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = read()
        }
        read()
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onStopOrDispose { context.unregisterReceiver(receiver) }
    }
    return limited
}

/** Opens Battery Saver settings, or the main settings screen where a phone doesn't have that one. */
fun openBatterySaverSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            // Nothing more to offer.
        }
    }
}
