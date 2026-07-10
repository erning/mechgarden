package zen.mirage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RamThreatDetectorTest {
    @Test
    fun activatesAfterAShortDirectPursuitStreak() {
        val detector = RamThreatDetector()

        repeat(5) { tick ->
            detector.observe(
                frame(
                    time = tick.toLong() + 1L,
                    distance = 500.0 - tick * 5.0,
                    advancing = 8.0,
                    lateral = 0.0,
                    distanceRate = -5.0,
                ),
            )
        }
        assertFalse(detector.active())

        detector.observe(frame(time = 6, distance = 475.0, advancing = 8.0, lateral = 0.0, distanceRate = -5.0))

        assertTrue(detector.active())
        assertTrue(detector.snapshot().confidence >= 0.3)
        assertEquals((475.0 - Kinematics.HALF_BOT * 2.0) / 5.0, detector.snapshot().collisionTicks, 1e-9)
        assertEquals(Angles.PI, detector.snapshot().pursuitHeadingRadians, 1e-9)
    }

    @Test
    fun doesNotClassifyAHorizontalOrbiterAsARammer() {
        val detector = RamThreatDetector()

        repeat(10) { tick ->
            detector.observe(
                frame(
                    time = tick.toLong() + 1L,
                    distance = 450.0,
                    advancing = 0.0,
                    lateral = 8.0,
                    distanceRate = 0.0,
                    enemyHeading = Angles.HALF_PI,
                ),
            )
        }

        assertFalse(detector.active())
    }

    @Test
    fun collisionActivatesTheLatchImmediately() {
        val detector = RamThreatDetector()

        detector.recordCollision()

        assertTrue(detector.active())
        assertEquals(0.0, detector.snapshot().collisionTicks, 1e-9)
    }

    private fun frame(
        time: Long,
        distance: Double,
        advancing: Double,
        lateral: Double,
        distanceRate: Double,
        enemyHeading: Double = Angles.PI,
    ): Tracker.Frame =
        Tracker.Frame(
            self = RobotState(time, 400.0, 300.0, 100.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            enemy =
                EnemyState(
                    time = time,
                    x = 400.0,
                    y = 300.0 + distance,
                    energy = 100.0,
                    velocity = 8.0,
                    headingRadians = enemyHeading,
                    absoluteBearingRadians = 0.0,
                    distance = distance,
                ),
            derived =
                EnemyDerived(
                    lateralVelocity = lateral,
                    advancingVelocity = advancing,
                    lateralDirection = 0,
                    bearingRateRadians = 0.0,
                    turnRateRadians = 0.0,
                    acceleration = 0.0,
                    distanceRate = distanceRate,
                    energyDelta = 0.0,
                ),
        )
}
