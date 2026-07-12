package zen.mirage

import kotlin.math.atan2

/**
 * Neutral angle helpers, all in radians (Robocode frame: +x east,
 * +y north; heading/bearing 0 = north, increasing clockwise — see
 * docs/robocode-physics.md). No robot stats, no strategy: pure functions usable by
 * any layer to normalize angles or compute bearings. Pair with the *Radians robot
 * APIs (headingRadians, bearingRadians, setTurnRightRadians, ...) so no unit
 * conversion is needed anywhere.
 */
object Angles {
    val PI = Math.PI
    val HALF_PI = PI / 2.0
    val TWO_PI = 2.0 * PI

    /** Normalize an angle to (-PI, PI] radians. */
    fun normalizeRelative(angle: Double): Double {
        // Simulation loops usually move by less than one revolution per tick;
        // keep their common path off the floating-point remainder instruction.
        if (angle > -PI && angle <= PI) return angle
        if (angle > PI && angle <= PI + TWO_PI) return angle - TWO_PI
        if (angle <= -PI && angle > -PI - TWO_PI) return angle + TWO_PI
        var a = angle % TWO_PI
        if (a <= -PI) a += TWO_PI
        if (a > PI) a -= TWO_PI
        return a
    }

    /** Normalize an angle to [0, 2*PI) radians. */
    fun normalizeAbsolute(angle: Double): Double {
        // Headings normally need at most one wrap; retain modulo for arbitrary input.
        if (angle >= 0.0 && angle < TWO_PI) return angle
        if (angle >= TWO_PI && angle < 2.0 * TWO_PI) return angle - TWO_PI
        if (angle < 0.0 && angle > -TWO_PI) return angle + TWO_PI
        var a = angle % TWO_PI
        if (a < 0.0) a += TWO_PI
        return a
    }

    /** Absolute bearing (radians, 0 = north, clockwise) from (x1,y1) to (x2,y2). */
    fun absoluteBearing(
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
    ): Double {
        val angle = atan2(x2 - x1, y2 - y1)
        return if (angle < 0.0) angle + TWO_PI else angle
    }
}
