package zen.mirage

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

class PreciseMeaTest {
    @Test
    fun reusedBearingMatchesTheDirectSimulation() {
        val scenarios =
            listOf(
                Kinematics.Pose(620.0, 420.0, Angles.HALF_PI, 8.0),
                Kinematics.Pose(760.0, 300.0, Angles.PI, -4.0),
                Kinematics.Pose(120.0, 80.0, 0.0, 0.0),
            )

        for (target in scenarios) {
            val expected =
                maxOf(
                    abs(referenceEscapeBearing(target, direction = 1)),
                    abs(referenceEscapeBearing(target, direction = -1)),
                )
            val actual =
                PreciseMea.halfEscapeRadians(
                    SOURCE_X,
                    SOURCE_Y,
                    FIRE_TIME,
                    BULLET_SPEED,
                    target,
                    NOW,
                    FIELD_WIDTH,
                    FIELD_HEIGHT,
                )

            assertEquals(expected, actual, 1e-12)
        }
    }

    /** Reference form used before the production loop carried its last bearing. */
    private fun referenceEscapeBearing(
        target: Kinematics.Pose,
        direction: Int,
    ): Double {
        val directAngleRadians = Angles.absoluteBearing(SOURCE_X, SOURCE_Y, target.x, target.y)
        var x = target.x
        var y = target.y
        var headingRadians = target.headingRadians
        var velocity = target.velocity
        var bestOffsetRadians = 0.0
        var ticks = 0
        while (ticks < MAX_TICKS) {
            val centerToUsRadians = Angles.absoluteBearing(SOURCE_X, SOURCE_Y, x, y)
            val desiredHeadingRadians =
                WallSmoothing.smoothedHeadingRadians(
                    x,
                    y,
                    centerToUsRadians + direction * Angles.HALF_PI,
                    direction > 0,
                    FIELD_WIDTH,
                    FIELD_HEIGHT,
                )
            var turnRadians = Angles.normalizeRelative(desiredHeadingRadians - headingRadians)
            var driveSign = 1
            if (abs(turnRadians) > Angles.HALF_PI) {
                turnRadians = Angles.normalizeRelative(turnRadians + Angles.PI)
                driveSign = -1
            }
            val maxTurnRadians = Kinematics.maxTurnRateRadians(velocity)
            headingRadians = Angles.normalizeAbsolute(headingRadians + turnRadians.coerceIn(-maxTurnRadians, maxTurnRadians))
            velocity = Kinematics.nextVelocity(velocity, driveSign)
            x += kotlin.math.sin(headingRadians) * velocity
            y += kotlin.math.cos(headingRadians) * velocity
            ticks++
            val reach = BULLET_SPEED * (NOW + ticks - FIRE_TIME)
            if (reach * reach >= (x - SOURCE_X) * (x - SOURCE_X) + (y - SOURCE_Y) * (y - SOURCE_Y)) break
            val offsetRadians =
                Angles.normalizeRelative(Angles.absoluteBearing(SOURCE_X, SOURCE_Y, x, y) - directAngleRadians)
            if (abs(offsetRadians) > abs(bestOffsetRadians)) bestOffsetRadians = offsetRadians
        }
        return bestOffsetRadians
    }

    private companion object {
        const val SOURCE_X = 400.0
        const val SOURCE_Y = 300.0
        const val FIRE_TIME = 0L
        const val NOW = 0L
        const val BULLET_SPEED = 14.0
        const val FIELD_WIDTH = 800.0
        const val FIELD_HEIGHT = 600.0
        const val MAX_TICKS = 150
    }
}
