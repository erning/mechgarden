package zen.fencer

import kotlin.math.cos
import kotlin.math.sin

/**
 * Enemy **prediction** layer: turn
 * the latest observed facts into a *future* state — explicitly a prediction, not
 * a fact. Two deterministic models:
 *
 * - [Mode.LINEAR]: the enemy keeps its current velocity and heading (a line).
 * - [Mode.CIRCULAR]: it also keeps its current turn rate (an arc).
 *
 * Position is clamped to the arena so we never predict the enemy through a wall.
 * The result is a [Kinematics.Pose] `ticks` ahead of the starting state; how much
 * to *trust* it (with staleness) is [Uncertainty]'s job, kept separate.
 *
 * The gun is the main consumer: linear/circular aiming projects the enemy to the
 * bullet's impact tick (solved iteratively, since impact time depends on the
 * predicted distance). Pure projection — no enemy stats, no strategy.
 */
object MotionModel {
    enum class Mode { LINEAR, CIRCULAR }

    /** Half a robot's bounding box — keep predictions off the very edge. */
    private const val HALF_BOT = 18.0

    /**
     * Predict the enemy [ticks] ticks ahead of [start], under [mode], holding
     * speed constant and (for CIRCULAR) advancing heading by [turnRateDeg]/tick.
     */
    fun predict(
        start: Kinematics.Pose,
        turnRateDeg: Double,
        ticks: Int,
        mode: Mode,
        fieldWidth: Double,
        fieldHeight: Double,
    ): Kinematics.Pose {
        val omega = if (mode == Mode.CIRCULAR) turnRateDeg else 0.0
        var heading = start.headingDeg
        var x = start.x
        var y = start.y
        repeat(ticks) {
            heading = Angles.normalizeAbsolute(heading + omega)
            val rad = Math.toRadians(heading)
            x = (x + sin(rad) * start.velocity).coerceIn(HALF_BOT, fieldWidth - HALF_BOT)
            y = (y + cos(rad) * start.velocity).coerceIn(HALF_BOT, fieldHeight - HALF_BOT)
        }
        return Kinematics.Pose(x, y, heading, start.velocity)
    }
}
