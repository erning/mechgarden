package zen.mirage

import kotlin.test.Test
import kotlin.test.assertEquals

class OrbitDirectionTest {
    @Test
    fun stoppedTargetKeepsItsLastNonZeroOrbitDirection() {
        assertEquals(-1, OrbitDirection.sign(0.0, stickyDirection = -1))
        assertEquals(1, OrbitDirection.sign(0.0, stickyDirection = 1))
    }

    @Test
    fun lateralVelocityIsTheColdStartFallback() {
        assertEquals(-1, OrbitDirection.sign(-0.1, stickyDirection = 0))
        assertEquals(1, OrbitDirection.sign(0.0, stickyDirection = 0))
        assertEquals(1, OrbitDirection.sign(0.1, stickyDirection = 0))
    }
}
