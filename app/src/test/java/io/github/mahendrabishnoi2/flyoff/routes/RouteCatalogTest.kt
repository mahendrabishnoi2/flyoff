package io.github.mahendrabishnoi2.flyoff.routes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCatalogTest {

    private val bundled = RouteFile.Header(RouteFile.FORMAT_VERSION, 1_000, "a")

    @Test
    fun `a newer download of this format replaces the bundled routes`() {
        assertTrue(RouteCatalog.preferDownloaded(bundled, RouteFile.Header(RouteFile.FORMAT_VERSION, 1_001, "b")))
    }

    @Test
    fun `otherwise the bundled routes are used`() {
        assertFalse(RouteCatalog.preferDownloaded(bundled, null))
        // An app update brought newer (or the same) data than the old download.
        assertFalse(RouteCatalog.preferDownloaded(bundled, RouteFile.Header(RouteFile.FORMAT_VERSION, 1_000, "b")))
        assertFalse(RouteCatalog.preferDownloaded(bundled, RouteFile.Header(RouteFile.FORMAT_VERSION, 999, "b")))
        // Another format, even if newer.
        assertFalse(RouteCatalog.preferDownloaded(bundled, RouteFile.Header(RouteFile.FORMAT_VERSION + 1, 2_000, "b")))
    }
}
