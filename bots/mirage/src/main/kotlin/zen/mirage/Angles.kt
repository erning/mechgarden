package zen.mirage

import kotlin.math.atan2

/**
 * Neutral angle helpers for Mirage (Robocode frame: +x east, +y north; heading in
 * degrees clockwise from north — see docs/robocode-physics.md). No robot stats, no
 * strategy: pure functions usable by any layer to normalize angles or compute bearings.
 */
object Angles {
    /** Normalize an angle to (-180, 180] degrees. */
    fun normalizeRelative(angleDeg: Double): Double {
        var a = angleDeg % 360.0
        if (a <= -180.0) a += 360.0
        if (a > 180.0) a -= 360.0
        return a
    }

    /** Normalize an angle to [0, 360) degrees. */
    fun normalizeAbsolute(angleDeg: Double): Double {
        var a = angleDeg % 360.0
        if (a < 0.0) a += 360.0
        return a
    }

    /** Absolute bearing (degrees clockwise from north) from (x1,y1) to (x2,y2). */
    fun absoluteBearing(
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
    ): Double = normalizeAbsolute(Math.toDegrees(atan2(x2 - x1, y2 - y1)))
}
