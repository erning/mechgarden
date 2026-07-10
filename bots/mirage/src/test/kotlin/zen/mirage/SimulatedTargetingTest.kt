package zen.mirage

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulatedTargetingTest {
    @Test
    fun keepsSimpleGunExpertsSeparate() {
        val predictions =
            SimulatedTargeting.predictions(
                sourceX = 400.0,
                sourceY = 100.0,
                usX = 400.0,
                usY = 400.0,
                usHeadingRadians = Angles.HALF_PI,
                usVelocity = 8.0,
                usTurnRateRadians = 0.08,
                directAngleRadians = 0.0,
                orbitDirection = 1,
                maxEscapeRadians = 0.6,
                bulletSpeed = 14.0,
                fieldWidth = 800.0,
                fieldHeight = 600.0,
            )

        assertEquals(0.0, predictions[SimulatedTargeting.Expert.HEAD_ON], 1e-12)
        assertTrue(predictions[SimulatedTargeting.Expert.LINEAR] > 0.0)
        assertTrue(
            abs(
                predictions[SimulatedTargeting.Expert.CIRCULAR] -
                    predictions[SimulatedTargeting.Expert.LINEAR],
            ) > 0.02,
        )
    }

    @Test
    fun wallLinearStopsItsProjectionAtTheBattlefieldEdge() {
        val predictions =
            SimulatedTargeting.predictions(
                sourceX = 400.0,
                sourceY = 100.0,
                usX = 760.0,
                usY = 400.0,
                usHeadingRadians = Angles.HALF_PI,
                usVelocity = 8.0,
                usTurnRateRadians = 0.0,
                directAngleRadians = Angles.absoluteBearing(400.0, 100.0, 760.0, 400.0),
                orbitDirection = 1,
                maxEscapeRadians = 0.6,
                bulletSpeed = 14.0,
                fieldWidth = 800.0,
                fieldHeight = 600.0,
            )

        assertTrue(
            abs(predictions[SimulatedTargeting.Expert.WALL_LINEAR]) <
                abs(predictions[SimulatedTargeting.Expert.LINEAR]),
        )
    }
}
