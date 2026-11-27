package io.github.mahendrabishnoi2.flyoff.location

import android.os.PowerManager

/**
 * Whether Battery Saver, as configured, can stop or throttle GPS while the screen is off
 * (PowerManager.isPowerSaveMode and PowerManager.getLocationPowerSaveMode, Android 9+).
 *
 * [powerSaveMode] is `isPowerSaveMode()`; [locationPowerSaveMode] is `getLocationPowerSaveMode()`,
 * which says what Battery Saver does to location while it is on. Only the screen-off modes count:
 *
 * - `LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF`, `LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF`:
 *   the GPS (or every provider) is turned off while the screen is off.
 * - `LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF`: documented as throttling, but in AOSP's
 *   `LocationProviderManager.isActive` it falls through to the same check as
 *   ALL_DISABLED_WHEN_SCREEN_OFF, so requests get no fixes at all while the screen is off.
 * - `LOCATION_MODE_FOREGROUND_ONLY` is **not** limiting for us. The docs say fixes "should only be
 *   provided to foreground apps" without defining foreground. AOSP decides it with
 *   `AppForegroundHelper.isForeground(importance)`: `importance <= IMPORTANCE_FOREGROUND_SERVICE`,
 *   so an app running a foreground service (our location service) is foreground, screen on or
 *   off. Android 10's `LocationManagerService` used the same cutoff (`FOREGROUND_IMPORTANCE_CUTOFF
 *   = IMPORTANCE_FOREGROUND_SERVICE`, checked through `UpdateRecord.mIsForegroundUid`), and
 *   Android 9's did not act on this mode at all. Sources checked (Nov 2026):
 *   android.googlesource.com/platform/frameworks/base, `main` branch,
 *   services/core/java/com/android/server/location/provider/LocationProviderManager.java and
 *   location/injector/AppForegroundHelper.java; tags android-9.0.0_r1 and android-10.0.0_r1,
 *   services/core/java/com/android/server/LocationManagerService.java; and the PowerManager
 *   reference on developer.android.com.
 * - `LOCATION_MODE_NO_CHANGE` and any value added in a later Android release: not limiting.
 *   (Better to miss a warning than to warn about something we don't understand.)
 *
 * While Battery Saver is off nothing is limiting, whatever [locationPowerSaveMode] says.
 */
fun gpsLimitedWhenScreenOff(powerSaveMode: Boolean, locationPowerSaveMode: Int): Boolean {
    if (!powerSaveMode) return false
    return when (locationPowerSaveMode) {
        PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF,
        PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF,
        PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF,
        -> true
        else -> false
    }
}
