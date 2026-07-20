package zen.proteus.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Angular math on Robocode's compass: north is 0, angles grow clockwise, and
 * runtime code uses radians throughout (see bots/proteus/AGENTS.md).
 */
internal object Angles {
    const val TWO_PI: Double = 2.0 * PI

    /** Normalizes to (-PI, PI]. */
    fun normalizeRelative(angleRadians: Double): Double {
        var a = angleRadians % TWO_PI
        if (a <= -PI) {
            a += TWO_PI
        } else if (a > PI) {
            a -= TWO_PI
        }
        return a
    }

    /** Normalizes to [0, 2*PI). */
    fun normalizeAbsolute(angleRadians: Double): Double {
        val a = angleRadians % TWO_PI
        return if (a < 0.0) a + TWO_PI else a
    }

    /** Absolute bearing from (fromX, fromY) to (toX, toY) on the Robocode compass. */
    fun absoluteBearingRadians(
        fromX: Double,
        fromY: Double,
        toX: Double,
        toY: Double,
    ): Double = normalizeAbsolute(atan2(toX - fromX, toY - fromY))

    fun projectX(
        x: Double,
        angleRadians: Double,
        distance: Double,
    ): Double = x + sin(angleRadians) * distance

    fun projectY(
        y: Double,
        angleRadians: Double,
        distance: Double,
    ): Double = y + cos(angleRadians) * distance
}
