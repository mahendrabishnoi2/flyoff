package io.github.mahendrabishnoi2.flyoff.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpsCourseTest {

    @Test
    fun `a moving fix's bearing is its course`() {
        assertEquals(274.0, gpsCourse(hasBearing = true, bearing = 274f, hasSpeed = true, speed = 230f)!!, 0.0)
        assertEquals(0.0, gpsCourse(hasBearing = true, bearing = 0f, hasSpeed = true, speed = 230f)!!, 0.0)
    }

    @Test
    fun `no bearing means no course`() {
        assertNull(gpsCourse(hasBearing = false, bearing = 0f, hasSpeed = true, speed = 230f))
    }

    @Test
    fun `at a standstill the bearing is noise`() {
        // Below the speed at which the map's arrow turns back into a dot.
        assertNull(gpsCourse(hasBearing = true, bearing = 0f, hasSpeed = true, speed = 0.2f))
        assertNull(gpsCourse(hasBearing = true, bearing = 90f, hasSpeed = false, speed = 0f))
        assertEquals(90.0, gpsCourse(hasBearing = true, bearing = 90f, hasSpeed = true, speed = ARROW_OFF_SPEED)!!, 0.0)
    }
}
