package io.github.mahendrabishnoi2.flyoff.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationAccessTest {

    private fun access(
        fine: Boolean = false,
        coarse: Boolean = false,
        rationale: Boolean = false,
        asked: Boolean = false,
        blocked: Boolean = false,
    ) = locationAccess(fine, coarse, rationale, asked, blocked)

    @Test
    fun `fine location is precise whatever else is true`() {
        assertEquals(LocationAccess.PRECISE, access(fine = true, coarse = true))
        assertEquals(LocationAccess.PRECISE, access(fine = true, coarse = true, asked = true, blocked = true))
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
    fun `dismissed dialog or unknown history is denied, not blocked`() {
        // Android 11+: tapping outside the dialog leaves the rationale flag false.
        assertEquals(LocationAccess.DENIED, access(rationale = false, asked = true, blocked = false))
    }

    @Test
    fun `confirmed don't ask again needs app settings`() {
        assertEquals(LocationAccess.BLOCKED, access(rationale = false, asked = true, blocked = true))
    }

    @Test
    fun `approximate only, can still ask for precise`() {
        assertEquals(LocationAccess.APPROXIMATE, access(coarse = true, rationale = true, asked = true))
        assertEquals(LocationAccess.APPROXIMATE, access(coarse = true))
        assertEquals(LocationAccess.APPROXIMATE, access(coarse = true, asked = true))
    }

    @Test
    fun `approximate only, precise confirmed blocked`() {
        assertEquals(LocationAccess.APPROXIMATE_BLOCKED, access(coarse = true, asked = true, blocked = true))
    }

    @Test
    fun `second deny after a shown dialog confirms the block`() {
        assertTrue(blockedAfterRequest(blockedBefore = false, rationaleBefore = true, fineGrantedAfter = false, rationaleAfter = false))
    }

    @Test
    fun `dismissing or a first deny does not confirm a block`() {
        // Dismissed after an earlier deny: the flag stays true.
        assertFalse(blockedAfterRequest(blockedBefore = false, rationaleBefore = true, fineGrantedAfter = false, rationaleAfter = true))
        // First request dismissed, or blocked in an earlier session and returned at once.
        assertFalse(blockedAfterRequest(blockedBefore = false, rationaleBefore = false, fineGrantedAfter = false, rationaleAfter = false))
        // First request denied: the flag turns true.
        assertFalse(blockedAfterRequest(blockedBefore = false, rationaleBefore = false, fineGrantedAfter = false, rationaleAfter = true))
    }

    @Test
    fun `a grant or a renewed rationale clears the block`() {
        assertFalse(blockedAfterRequest(blockedBefore = true, rationaleBefore = false, fineGrantedAfter = true, rationaleAfter = false))
        assertFalse(blockedAfterRequest(blockedBefore = true, rationaleBefore = false, fineGrantedAfter = false, rationaleAfter = true))
        assertTrue(blockedAfterRequest(blockedBefore = true, rationaleBefore = false, fineGrantedAfter = false, rationaleAfter = false))
    }
}
