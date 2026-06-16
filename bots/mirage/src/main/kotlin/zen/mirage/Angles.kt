package zen.mirage

import kotlin.math.atan2

/**
 * Neutral angle helpers for Mirage, all in radians (Robocode frame: +x east,
 * +y north; heading/bearing 0 = north, increasing clockwise — see
 * docs/robocode-physics.md). No robot stats, no strategy: pure functions usable
 * by any layer to normalize angles or compute bearings. Pair with the *Radians
 * robot APIs (headingRadians, bearingRadians, setTurnRightRadians, ...) so no
 * unit conversion is needed anywhere.
 */
object Angles {
    val PI = Math.PI
    val TWO_PI = 2.0 * PI

    /** Normalize an angle to (-PI, PI] radians. */
    fun normalizeRelative(angle: Double): Double {
        var a = angle % TWO_PI
        if (a <= -PI) a += TWO_PI
        if (a > PI) a -= TWO_PI
        return a
    }

    /** Normalize an angle to [0, 2*PI) radians. */
    fun normalizeAbsolute(angle: Double): Double {
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
    ): Double = normalizeAbsolute(atan2(x2 - x1, y2 - y1))
}
