package zen.mirage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActiveShieldGeometryTest {
    @Test
    fun interceptsAnIncomingHeadOnBulletBetweenShooterAndTarget() {
        val intercept =
            ActiveShieldGeometry.intercept(
                shooterX = 400.0,
                shooterY = 300.0,
                targetX = 400.0,
                targetY = 530.0,
                targetVelocityX = 0.0,
                targetVelocityY = -14.0,
                projectileSpeed = 19.7,
            )

        assertNotNull(intercept)
        assertEquals(400.0, intercept.x, 1e-9)
        assertTrue(intercept.y in 300.0..530.0)
        assertTrue(intercept.ticks > 0.0)
    }

    @Test
    fun returnsNullWhenAnEquallyFastTargetMovesDirectlyAway() {
        val intercept =
            ActiveShieldGeometry.intercept(
                shooterX = 0.0,
                shooterY = 0.0,
                targetX = 0.0,
                targetY = 100.0,
                targetVelocityX = 0.0,
                targetVelocityY = 19.7,
                projectileSpeed = 19.7,
            )

        assertNull(intercept)
    }

    @Test
    fun leadsALaterallyMovingTarget() {
        val intercept =
            ActiveShieldGeometry.intercept(
                shooterX = 0.0,
                shooterY = 0.0,
                targetX = 0.0,
                targetY = 100.0,
                targetVelocityX = 10.0,
                targetVelocityY = 0.0,
                projectileSpeed = 20.0,
            )

        assertNotNull(intercept)
        assertTrue(intercept.x > 0.0)
        assertEquals(100.0, intercept.y, 1e-9)
    }
}
