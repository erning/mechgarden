package zen.proteus.wave

import zen.proteus.move.OurBullets
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BulletShadowsTest {
    // Enemy wave expanding south from (400, 550); we sit at (400, 250).
    private val wave =
        Wave(
            originX = 400.0,
            originY = 550.0,
            power = 3.0,
            fireTime = 95,
            directAngleRadians = PI,
            lateralDirection = 1.0,
        )

    @Test
    fun `a bullet flying into the wave casts a shadow`() {
        // Our bullet leaves (400, 250) heading due north at power 3.
        val bullet = OurBullets.Tracked(400.0, 250.0, 0.0, 11.0, 101, 3.0)
        val shadows = BulletShadows.intervals(wave, listOf(bullet), 400.0, 250.0, 100)
        assertEquals(1, shadows.size)
        // The shadow must cover GF 0: our bullet flies along the direct angle.
        assertTrue(shadows[0][0] <= 0.0 && shadows[0][1] >= 0.0)
    }

    @Test
    fun `a bullet flying away from the wave casts no shadow`() {
        // Heading due south: never crosses the incoming ring before it reaches us.
        val bullet = OurBullets.Tracked(400.0, 250.0, PI, 11.0, 101, 3.0)
        val shadows = BulletShadows.intervals(wave, listOf(bullet), 400.0, 250.0, 100)
        assertTrue(shadows.isEmpty())
    }

    @Test
    fun `no bullets means no shadows`() {
        assertTrue(BulletShadows.intervals(wave, emptyList(), 400.0, 250.0, 100).isEmpty())
    }
}
