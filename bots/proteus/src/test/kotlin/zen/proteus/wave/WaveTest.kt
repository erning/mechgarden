package zen.proteus.wave

import kotlin.math.PI
import kotlin.math.asin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WaveTest {
    private fun wave(
        power: Double = 3.0,
        fireTime: Long = 10,
    ) = Wave(
        originX = 0.0,
        originY = 0.0,
        power = power,
        fireTime = fireTime,
        directAngleRadians = PI / 2.0,
        lateralDirection = 1.0,
    )

    @Test
    fun `radius includes the spawn turn's first move`() {
        val w = wave(fireTime = 10)
        assertEquals(0.0, w.radius(9), 1e-12)
        assertEquals(w.speed, w.radius(10), 1e-12)
        assertEquals(3 * w.speed, w.radius(12), 1e-12)
    }

    @Test
    fun `guessFactor maps direct angle to zero and MEA to one`() {
        val w = wave()
        assertEquals(0.0, w.guessFactor(PI / 2.0), 1e-9)
        assertEquals(asin(8.0 / w.speed), w.maxEscapeAngleRadians, 1e-12)
        assertEquals(1.0, w.guessFactor(PI / 2.0 + w.maxEscapeAngleRadians), 1e-9)
        assertEquals(-1.0, w.guessFactor(PI / 2.0 - w.maxEscapeAngleRadians), 1e-9)
    }

    @Test
    fun `intersection finds the band crossing the robot square`() {
        val w = wave()
        // Robot square centered east of the origin at (100, 0): x in [82, 118].
        val interval = w.intersection(100.0, 0.0, 80.0, 100.0)
        assertNotNull(interval)
        val center = PI / 2.0
        assertTrue(interval[0] < center && interval[1] > center)
        // The subtended half-angle of the square is atan(18 / 100) at most.
        val halfWidth = kotlin.math.max(interval[1] - center, center - interval[0])
        assertTrue(halfWidth > 0.05 && halfWidth < 0.5)
    }

    @Test
    fun `intersection is null before the ring arrives`() {
        val w = wave()
        assertNull(w.intersection(100.0, 0.0, 30.0, 45.0))
    }

    @Test
    fun `intersection is null after the ring passed`() {
        val w = wave()
        assertNull(w.intersection(100.0, 0.0, 150.0, 170.0))
    }

    @Test
    fun `intersection covers everything when the origin is inside the square`() {
        val w = wave()
        val interval = w.intersection(5.0, 5.0, 10.0, 20.0)
        assertNotNull(interval)
        assertEquals(-PI, interval[0], 1e-12)
        assertEquals(PI, interval[1], 1e-12)
    }
}
