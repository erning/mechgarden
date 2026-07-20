package zen.proteus.move

import kotlin.test.Test
import kotlin.test.assertEquals

class MovementSimTest {
    @Test
    fun `accelerates at one per tick`() {
        assertEquals(1.0, MovementSim.nextVelocity(0.0, 8.0), 1e-12)
        assertEquals(5.0, MovementSim.nextVelocity(4.0, 8.0), 1e-12)
        assertEquals(-1.0, MovementSim.nextVelocity(0.0, -8.0), 1e-12)
    }

    @Test
    fun `brakes at two per tick`() {
        assertEquals(6.0, MovementSim.nextVelocity(8.0, 0.0), 1e-12)
        assertEquals(6.0, MovementSim.nextVelocity(8.0, 2.0), 1e-12)
        assertEquals(2.0, MovementSim.nextVelocity(4.0, -8.0), 1e-12)
    }

    @Test
    fun `does not overshoot the target`() {
        assertEquals(8.0, MovementSim.nextVelocity(7.5, 8.0), 1e-12)
        assertEquals(0.0, MovementSim.nextVelocity(1.0, 0.0), 1e-12)
    }
}
