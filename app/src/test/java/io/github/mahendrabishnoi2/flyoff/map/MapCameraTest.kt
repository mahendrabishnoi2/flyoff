package io.github.mahendrabishnoi2.flyoff.map

import org.junit.Assert.assertEquals
import org.junit.Test

class MapCameraTest {

    @Test
    fun `follow zooms in from a world view`() {
        assertEquals(FOLLOW_ZOOM, followZoom(MIN_ZOOM), 0.0)
        assertEquals(FOLLOW_ZOOM, followZoom(2.5), 0.0)
    }

    @Test
    fun `follow never zooms out or past the max`() {
        assertEquals(7.2, followZoom(7.2), 0.0)
        assertEquals(MAX_ZOOM, followZoom(MAX_ZOOM + 3), 0.0)
    }
}
