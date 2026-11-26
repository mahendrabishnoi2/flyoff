package io.github.mahendrabishnoi2.flyoff.location

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationAccessTest {

    private fun access(
        fine: Boolean = false,
        coarse: Boolean = false,
        rationale: Boolean = false,
        asked: Boolean = false,
    ) = locationAccess(fine, coarse, rationale, asked)

    @Test
    fun `fine location is precise whatever else is true`() {
        assertEquals(LocationAccess.PRECISE, access(fine = true, coarse = true))
        assertEquals(LocationAccess.PRECISE, access(fine = true, coarse = true, rationale = true, asked = true))
    }

    @Test
    fun `nothing granted and never asked`() {
        assertEquals(LocationAccess.NOT_ASKED, access())
    }

    @Test
    fun `denied once, system still shows its dialog`() {
        assertEquals(LocationAccess.DENIED, access(rationale = true, asked = true))
        // Denied in an earlier session: the rationale flag alone says so.
        assertEquals(LocationAccess.DENIED, access(rationale = true, asked = false))
    }

    @Test
    fun `denied with don't ask again needs app settings`() {
        assertEquals(LocationAccess.BLOCKED, access(rationale = false, asked = true))
    }

    @Test
    fun `approximate only, can still ask for precise`() {
        assertEquals(LocationAccess.APPROXIMATE, access(coarse = true, rationale = true, asked = true))
        assertEquals(LocationAccess.APPROXIMATE, access(coarse = true, rationale = false, asked = false))
    }

    @Test
    fun `approximate only, precise blocked`() {
        assertEquals(LocationAccess.APPROXIMATE_BLOCKED, access(coarse = true, rationale = false, asked = true))
    }
}
