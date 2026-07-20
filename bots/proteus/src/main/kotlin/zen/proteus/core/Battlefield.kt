package zen.proteus.core

import kotlin.math.cos
import kotlin.math.sin

/**
 * The battlefield rectangle and wall smoothing. Robocode robots are axis-aligned
 * 36x36 squares; [WALL_MARGIN] keeps the whole square inside the field with a
 * small safety buffer.
 */
internal class Battlefield(
    val width: Double,
    val height: Double,
) {
    /** Clamps a coordinate so a robot square centered there stays in the field. */
    fun clampX(x: Double): Double = x.coerceIn(ROBOT_HALF_SIZE, width - ROBOT_HALF_SIZE)

    fun clampY(y: Double): Double = y.coerceIn(ROBOT_HALF_SIZE, height - ROBOT_HALF_SIZE)

    /**
     * Rotates [desiredRadians] away from the walls: tries growing offsets on both
     * sides ([preferredSide] first) until a [stick]-long projection from (x, y)
     * stays inside the field. Returns [desiredRadians] unchanged when boxed in.
     */
    fun smoothWall(
        x: Double,
        y: Double,
        desiredRadians: Double,
        preferredSide: Double,
        stick: Double = STICK_LENGTH,
    ): Double {
        if (fits(x, y, desiredRadians, stick)) return desiredRadians
        val first = if (preferredSide >= 0.0) 1.0 else -1.0
        for (i in 1..MAX_SMOOTH_STEPS) {
            val preferred = desiredRadians + first * i * SMOOTH_STEP_RADIANS
            if (fits(x, y, preferred, stick)) return preferred
            val alternate = desiredRadians - first * i * SMOOTH_STEP_RADIANS
            if (fits(x, y, alternate, stick)) return alternate
        }
        return desiredRadians
    }

    private fun fits(
        x: Double,
        y: Double,
        angleRadians: Double,
        stick: Double,
    ): Boolean {
        val endX = x + sin(angleRadians) * stick
        val endY = y + cos(angleRadians) * stick
        return endX >= WALL_MARGIN &&
            endX <= width - WALL_MARGIN &&
            endY >= WALL_MARGIN &&
            endY <= height - WALL_MARGIN
    }

    companion object {
        const val ROBOT_HALF_SIZE = 18.0
        const val WALL_MARGIN = ROBOT_HALF_SIZE + 2.0
        const val STICK_LENGTH = 160.0
        val SMOOTH_STEP_RADIANS: Double = Math.toRadians(5.0)
        const val MAX_SMOOTH_STEPS = 36
    }
}
