package zen.fencer

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Robocode movement physics as **stateless pure functions** — usable by any
 * robot to plan motion, predict where someone will be, or lead a shot. No
 * enemy stats, no strategy, no state. Robocode frame: +x east, +y north;
 * heading is degrees clockwise from north (see docs/robocode-physics.md).
 *
 * The constants are the engine's: max speed 8 px/tick, accelerate 1 px/tick²,
 * brake 2 px/tick², body turn rate `10 − 0.75·|v|` °/tick (shrinks with speed).
 */
object Kinematics {
    const val MAX_VELOCITY = 8.0
    const val ACCELERATION = 1.0
    const val DECELERATION = 2.0

    /** Max body turn this tick (degrees), given the current speed. */
    fun maxTurnRateDeg(velocity: Double): Double = 10.0 - 0.75 * abs(velocity)

    /**
     * Next velocity when driving toward [driveSign] (+1 forward, −1 backward),
     * exactly the engine's rule (`RobotPeer.getNewVelocity`, verified against the
     * 1.10.3 bytecode): the goal speed is [maxSpeed] (`setMaxVelocity`) further
     * capped by the **auto-brake envelope** for [remainingDistance] (`setAhead`'s
     * remaining travel — the engine slows so it can stop within it; ∞ = cruise).
     * Moving along the drive: `max(v−2, min(goal, v+1))`. Moving *against* it the
     * engine brakes **through zero in one tick** — part of the tick spent braking
     * at 2, the remainder already accelerating at 1 the other way ([maxDecel]) —
     * not a flat −2 (a flat −2 overshoots by up to 0.5 px/tick at |v|<2, which is
     * exactly the brake-to-a-stop regime GoTo surfing lives in).
     */
    fun nextVelocity(
        velocity: Double,
        driveSign: Int,
        maxSpeed: Double = MAX_VELOCITY,
        remainingDistance: Double = Double.POSITIVE_INFINITY,
    ): Double {
        val s = velocity * driveSign // speed component along the intended drive direction
        val goal = minOf(maxSpeed, maxVelocityForDistance(remainingDistance)).coerceIn(0.0, MAX_VELOCITY)
        val next =
            if (s >= 0.0) {
                maxOf(s - DECELERATION, minOf(goal, s + ACCELERATION))
            } else {
                // The engine's floor of v−ACCELERATION can't bind here (goal ≥ 0 > s).
                minOf(goal, s + maxDecel(-s))
            }
        return (next * driveSign).coerceIn(-MAX_VELOCITY, MAX_VELOCITY)
    }

    /** Engine's one-tick speed change available when moving opposite the drive:
     * `speed/2` of the tick brakes to zero, the rest accelerates the other way. */
    private fun maxDecel(speed: Double): Double {
        val decelTime = speed / DECELERATION
        return minOf(1.0, decelTime) * DECELERATION + maxOf(0.0, 1.0 - decelTime) * ACCELERATION
    }

    /** The highest speed from which the engine's auto-brake still stops within
     * [distance] (`RobotPeer.getMaxVelocity`): with `t = max(1, ⌈(√(4d+1)−1)/2⌉)`
     * ticks left to brake, allow `2(t−1)` plus the per-tick share of what braking
     * from there doesn't cover. */
    fun maxVelocityForDistance(distance: Double): Double {
        if (distance.isInfinite()) return MAX_VELOCITY
        val decelTime = maxOf(1.0, Math.ceil((Math.sqrt(4.0 * distance + 1.0) - 1.0) / 2.0))
        val decelDist = decelTime / 2.0 * (decelTime - 1.0) * DECELERATION
        return (decelTime - 1.0) * DECELERATION + (distance - decelDist) / decelTime
    }

    /** Immutable pose for forward simulation — pure kinematics, no time/energy. */
    data class Pose(
        val x: Double,
        val y: Double,
        val headingDeg: Double,
        val velocity: Double,
    )

    /**
     * Advance [pose] one tick while steering the body toward [goHeadingDeg],
     * reversing (driving backward) when that faces the goal with less turning.
     * Matches the engine's order: **turn first** (capped by the turn rate at the
     * *current* velocity), **then accelerate**, **then move** at the new velocity.
     * [remainingDistance] models `setAhead`'s remaining travel — the engine
     * auto-brakes to stop within it (∞ = cruise along the heading).
     */
    fun step(
        pose: Pose,
        goHeadingDeg: Double,
        maxSpeed: Double = MAX_VELOCITY,
        remainingDistance: Double = Double.POSITIVE_INFINITY,
    ): Pose {
        var angle = Angles.normalizeRelative(goHeadingDeg - pose.headingDeg)
        var driveSign = 1
        if (abs(angle) > 90.0) {
            angle = Angles.normalizeRelative(angle + 180.0)
            driveSign = -1
        }
        val maxTurn = maxTurnRateDeg(pose.velocity)
        val heading = Angles.normalizeAbsolute(pose.headingDeg + angle.coerceIn(-maxTurn, maxTurn))
        val velocity = nextVelocity(pose.velocity, driveSign, maxSpeed, remainingDistance)
        val rad = Math.toRadians(heading)
        return Pose(pose.x + sin(rad) * velocity, pose.y + cos(rad) * velocity, heading, velocity)
    }

    /**
     * Simulate [ticks] ticks from [from], each tick steering toward the heading
     * the [goHeadingAt] policy returns for the current pose. Pure: the caller's
     * policy carries the *intent* (orbit, go-to, …); this only applies physics.
     */
    fun predict(
        from: Pose,
        ticks: Int,
        goHeadingAt: (Pose) -> Double,
    ): Pose {
        var p = from
        repeat(ticks) { p = step(p, goHeadingAt(p)) }
        return p
    }
}
