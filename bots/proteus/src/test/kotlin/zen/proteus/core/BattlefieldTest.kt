package zen.proteus.core

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BattlefieldTest {
    private val field = Battlefield(800.0, 600.0)

    @Test
    fun `clamp keeps a robot square inside the field`() {
        assertEquals(Battlefield.ROBOT_HALF_SIZE, field.clampX(-50.0), 1e-12)
        assertEquals(800.0 - Battlefield.ROBOT_HALF_SIZE, field.clampX(900.0), 1e-12)
        assertEquals(Battlefield.ROBOT_HALF_SIZE, field.clampY(-50.0), 1e-12)
        assertEquals(600.0 - Battlefield.ROBOT_HALF_SIZE, field.clampY(900.0), 1e-12)
    }

    @Test
    fun `smoothWall leaves headings that already fit unchanged`() {
        val desired = PI / 2.0 // east, from the field center: fits everywhere
        assertEquals(desired, field.smoothWall(400.0, 300.0, desired, 1.0), 1e-12)
    }

    @Test
    fun `smoothWall rotates a wall-facing heading back inside`() {
        // Near the east wall, heading straight east: the stick pokes out.
        val smoothed = field.smoothWall(780.0, 300.0, PI / 2.0, 1.0)
        val endX = 780.0 + kotlin.math.sin(smoothed) * Battlefield.STICK_LENGTH
        val endY = 300.0 + kotlin.math.cos(smoothed) * Battlefield.STICK_LENGTH
        assertTrue(endX <= 800.0 - Battlefield.WALL_MARGIN + 1e-9)
        assertTrue(endY >= Battlefield.WALL_MARGIN - 1e-9)
        assertTrue(endY <= 600.0 - Battlefield.WALL_MARGIN + 1e-9)
    }
}
