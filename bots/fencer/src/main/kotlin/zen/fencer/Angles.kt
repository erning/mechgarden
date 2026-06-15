package zen.fencer

import kotlin.math.atan2

/**
 * Angle helpers for Robocode's coordinate convention: headings are measured in
 * degrees **clockwise from north** (0 = north, 90 = east). See
 * docs/robocode-physics.md.
 */
object Angles {
    /** Normalize an angle to the range (-180, 180] degrees. */
    fun normalizeRelative(angleDeg: Double): Double {
        var a = angleDeg % 360.0
        if (a <= -180.0) a += 360.0
        if (a > 180.0) a -= 360.0
        return a
    }

    /** Normalize an angle to the range [0, 360) degrees. */
    fun normalizeAbsolute(angleDeg: Double): Double {
        var a = angleDeg % 360.0
        if (a < 0.0) a += 360.0
        return a
    }

    /**
     * Absolute bearing (degrees clockwise from north) from point (x1, y1) to
     * point (x2, y2) in Robocode's coordinate system, where +x is east and +y
     * is north.
     */
    fun absoluteBearing(
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
    ): Double {
        // atan2(east, north) gives the clockwise-from-north angle directly.
        return normalizeAbsolute(Math.toDegrees(atan2(x2 - x1, y2 - y1)))
    }
}
