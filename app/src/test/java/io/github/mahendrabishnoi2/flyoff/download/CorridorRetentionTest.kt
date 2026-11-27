package io.github.mahendrabishnoi2.flyoff.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CorridorRetentionTest {

    private val landed = 1_790_000_000_000L // 2026-09-21
    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun `corridors are kept for three days after landing`() {
        assertEquals(3 * day, CorridorRetention.KEEP_AFTER_LANDING_MILLIS)
        assertEquals(landed + 3 * day, CorridorRetention.deleteAtMillis(landed))
    }

    @Test
    fun `not due before landing or during the three days`() {
        assertFalse(CorridorRetention.isDueForDeletion(landed, landed - day))
        assertFalse(CorridorRetention.isDueForDeletion(landed, landed))
        assertFalse(CorridorRetention.isDueForDeletion(landed, landed + 2 * day))
        assertFalse(CorridorRetention.isDueForDeletion(landed, landed + 3 * day - 1))
    }

    @Test
    fun `due from three days after landing`() {
        assertTrue(CorridorRetention.isDueForDeletion(landed, landed + 3 * day))
        assertTrue(CorridorRetention.isDueForDeletion(landed, landed + 30 * day))
    }

    @Test
    fun `a landing time near the end of time does not overflow`() {
        assertEquals(Long.MAX_VALUE, CorridorRetention.deleteAtMillis(Long.MAX_VALUE - day))
        assertFalse(CorridorRetention.isDueForDeletion(Long.MAX_VALUE - day, landed))
    }
}
