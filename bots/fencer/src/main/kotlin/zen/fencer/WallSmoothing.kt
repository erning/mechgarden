package zen.fencer

import kotlin.math.cos
import kotlin.math.sin

/**
 * Wall smoothing: nudge a desired travel heading so the robot skims along the
 * walls instead of crashing into them (wall hits cost energy and stall you —
 * see docs/robocode-physics.md).
 *
 * Angles are degrees clockwise from north; positions use Robocode's frame
 * (+x east, +y north).
 */
object WallSmoothing {
    /** Half the side of a robot's bounding box, in pixels. */
    private const val HALF_BOT = 18.0

    /** Keep-away band from each wall (a bit beyond the bounding box). */
    const val DEFAULT_MARGIN = HALF_BOT + 22.0

    /** How far ahead we probe for a wall, in pixels. */
    const val DEFAULT_STICK = 140.0

    /** Rotation increment while searching for a safe heading. */
    private const val STEP_DEG = 8.0

    /** Max rotation steps (45 * 8° = 360°), so the search always terminates. */
    private const val MAX_STEPS = 45

    /**
     * Rotate [desiredHeadingDeg] toward open space until a point [stick] pixels
     * ahead lies inside the safe area, turning in the orbit direction
     * ([clockwise] = turn right). Returns a safe absolute heading in degrees.
     */
    fun smoothedHeading(
        x: Double,
        y: Double,
        desiredHeadingDeg: Double,
        clockwise: Boolean,
        fieldWidth: Double,
        fieldHeight: Double,
        margin: Double = DEFAULT_MARGIN,
        stick: Double = DEFAULT_STICK,
    ): Double {
        val dir = if (clockwise) 1.0 else -1.0
        var heading = Angles.normalizeAbsolute(desiredHeadingDeg)
        repeat(MAX_STEPS) {
            val rad = Math.toRadians(heading)
            val ahead = x + sin(rad) * stick
            val aboveY = y + cos(rad) * stick
            val safe =
                ahead in margin..(fieldWidth - margin) &&
                    aboveY in margin..(fieldHeight - margin)
            if (safe) return heading
            heading = Angles.normalizeAbsolute(heading + dir * STEP_DEG)
        }
        return heading
    }
}
