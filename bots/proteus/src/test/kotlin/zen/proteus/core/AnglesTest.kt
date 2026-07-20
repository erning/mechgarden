package zen.proteus.core

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals

class AnglesTest {
    @Test
    fun `normalizeRelative wraps into minus pi to pi`() {
        assertEquals(0.5, Angles.normalizeRelative(0.5), 1e-12)
        assertEquals(-PI + 0.25, Angles.normalizeRelative(PI + 0.25), 1e-12)
        assertEquals(PI / 2.0, Angles.normalizeRelative(PI / 2.0 + Angles.TWO_PI), 1e-12)
    }

    @Test
    fun `normalizeAbsolute wraps into zero to two pi`() {
        assertEquals(0.5, Angles.normalizeAbsolute(0.5), 1e-12)
        assertEquals(PI / 2.0, Angles.normalizeAbsolute(-PI * 1.5), 1e-12)
        assertEquals(0.0, Angles.normalizeAbsolute(Angles.TWO_PI), 1e-12)
    }

    @Test
    fun `absoluteBearingRadians follows the robocode compass`() {
        // North is 0, east is PI/2, south is PI, west is -PI/2.
        assertEquals(0.0, Angles.absoluteBearingRadians(0.0, 0.0, 0.0, 10.0), 1e-12)
        assertEquals(PI / 2.0, Angles.absoluteBearingRadians(0.0, 0.0, 10.0, 0.0), 1e-12)
        assertEquals(PI, Angles.absoluteBearingRadians(0.0, 0.0, 0.0, -10.0), 1e-12)
        assertEquals(PI * 1.5, Angles.absoluteBearingRadians(0.0, 0.0, -10.0, 0.0), 1e-12)
    }

    @Test
    fun `projection lands on the bearing line`() {
        val bearing = Angles.absoluteBearingRadians(100.0, 200.0, 160.0, 260.0)
        val distance = kotlin.math.hypot(60.0, 60.0)
        assertEquals(160.0, Angles.projectX(100.0, bearing, distance), 1e-9)
        assertEquals(260.0, Angles.projectY(200.0, bearing, distance), 1e-9)
    }
}
