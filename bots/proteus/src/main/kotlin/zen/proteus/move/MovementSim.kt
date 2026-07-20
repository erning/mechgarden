package zen.proteus.move

import robocode.Rules
import zen.proteus.core.Angles
import zen.proteus.core.Battlefield
import zen.proteus.core.Kinematics
import zen.proteus.state.BotState
import kotlin.math.abs

/**
 * Exact per-tick physics for candidate movement, mirroring the engine's order:
 * turn the body (rate limited by current velocity), accelerate, move, then clamp
 * at the walls (a wall hit stops the robot).
 */
internal class MovementSim(
    private val field: Battlefield,
) {
    data class State(
        var x: Double,
        var y: Double,
        var headingRadians: Double,
        var velocity: Double,
    ) {
        fun distanceTo(other: BotState): Double = kotlin.math.hypot(other.x - x, other.y - y)
    }

    /** Advances [state] one tick toward [desiredRadians] at [targetVelocity]. */
    fun step(
        state: State,
        desiredRadians: Double,
        targetVelocity: Double,
        wallSmoothSide: Double,
    ) {
        val smoothedRadians =
            field.smoothWall(state.x, state.y, desiredRadians, wallSmoothSide)
        val maxTurnRadians = Kinematics.maxBodyTurnRadians(state.velocity)
        val turnRadians =
            Angles
                .normalizeRelative(smoothedRadians - state.headingRadians)
                .coerceIn(-maxTurnRadians, maxTurnRadians)
        state.headingRadians = Angles.normalizeAbsolute(state.headingRadians + turnRadians)
        state.velocity = nextVelocity(state.velocity, targetVelocity)
        state.x = field.clampX(state.x + Angles.projectX(0.0, state.headingRadians, state.velocity))
        state.y = field.clampY(state.y + Angles.projectY(0.0, state.headingRadians, state.velocity))
    }

    companion object {
        /** Engine acceleration model: 1.0 speeding up, 2.0 braking or reversing. */
        fun nextVelocity(
            velocity: Double,
            target: Double,
        ): Double {
            val braking =
                velocity * target < 0.0 || abs(target) < abs(velocity)
            val rate = if (braking) Rules.DECELERATION else Rules.ACCELERATION
            return (velocity + (target - velocity).coerceIn(-rate, rate))
                .coerceIn(-Rules.MAX_VELOCITY, Rules.MAX_VELOCITY)
        }
    }
}
