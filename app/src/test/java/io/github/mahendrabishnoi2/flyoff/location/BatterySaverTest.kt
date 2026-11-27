package io.github.mahendrabishnoi2.flyoff.location

import android.os.PowerManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatterySaverTest {

    private val allModes = listOf(
        PowerManager.LOCATION_MODE_NO_CHANGE,
        PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF,
        PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF,
        PowerManager.LOCATION_MODE_FOREGROUND_ONLY,
        PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF,
    )

    @Test
    fun `modes that stop or throttle GPS with the screen off are limiting`() {
        assertTrue(gpsLimitedWhenScreenOff(true, PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF))
        assertTrue(gpsLimitedWhenScreenOff(true, PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF))
        assertTrue(gpsLimitedWhenScreenOff(true, PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF))
    }

    @Test
    fun `no change is not limiting`() {
        assertFalse(gpsLimitedWhenScreenOff(true, PowerManager.LOCATION_MODE_NO_CHANGE))
    }

    @Test
    fun `foreground only is not limiting because a location foreground service counts as foreground`() {
        assertFalse(gpsLimitedWhenScreenOff(true, PowerManager.LOCATION_MODE_FOREGROUND_ONLY))
    }

    @Test
    fun `nothing is limiting while Battery Saver is off`() {
        allModes.forEach { assertFalse("mode $it", gpsLimitedWhenScreenOff(false, it)) }
    }

    @Test
    fun `unknown future modes are not limiting`() {
        listOf(-1, 5, 99, Int.MAX_VALUE).forEach { assertFalse("mode $it", gpsLimitedWhenScreenOff(true, it)) }
    }
}
