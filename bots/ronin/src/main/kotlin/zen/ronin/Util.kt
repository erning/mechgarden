package zen.ronin

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Neutral geometry + movement-physics helpers for Ronin (Robocode frame: +x east,
 * +y north; heading in degrees clockwise from north — see docs/robocode-physics.md).
 * No robot stats, no strategy: pure functions and immutable value types usable by
 * any layer to plan motion, predict positions, or lead shots.
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

/**
 * Robocode movement physics as stateless pure functions (engine constants: max
 * speed 8 px/tick, accel 1 px/tick², brake 2 px/tick², body turn 10 − 0.75·|v|
 * °/tick). The velocity rule mirrors `RobotPeer.getNewVelocity` exactly.
 */
object Kinematics {
    const val MAX_VELOCITY = 8.0
    const val ACCELERATION = 1.0
    const val DECELERATION = 2.0

    /** Max body turn this tick (degrees), given the current speed. */
    fun maxTurnRateDeg(velocity: Double): Double = 10.0 - 0.75 * abs(velocity)

    /** Next velocity under Robocode's rule for drive [driveSign], capped by
     * [maxSpeed] and the auto-brake envelope for [remainingDistance]. */
    fun nextVelocity(
        velocity: Double,
        driveSign: Int,
        maxSpeed: Double = MAX_VELOCITY,
        remainingDistance: Double = Double.POSITIVE_INFINITY,
    ): Double {
        val s = velocity * driveSign
        val goal = minOf(maxSpeed, maxVelocityForDistance(remainingDistance)).coerceIn(0.0, MAX_VELOCITY)
        val next =
            if (s >= 0.0) {
                maxOf(s - DECELERATION, minOf(goal, s + ACCELERATION))
            } else {
                minOf(goal, s + maxDecel(-s))
            }
        return (next * driveSign).coerceIn(-MAX_VELOCITY, MAX_VELOCITY)
    }

    /** One-tick speed change when moving opposite the drive (brake-through-zero). */
    private fun maxDecel(speed: Double): Double {
        val decelTime = speed / DECELERATION
        return minOf(1.0, decelTime) * DECELERATION + maxOf(0.0, 1.0 - decelTime) * ACCELERATION
    }

    /** Highest speed from which the engine's auto-brake still stops within [distance]. */
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

    /** Advance [pose] one tick steering toward [goHeadingDeg], reversing when that
     * faces the goal with less turning. Engine order: turn, accelerate, move. */
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

    /** Simulate [ticks] ticks, each steering toward the heading [goHeadingAt] returns. */
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

/**
 * Wall smoothing: rotate a desired travel heading toward open space until a point
 * [stick] pixels ahead lies in the safe area, so the robot skims walls instead of
 * crashing. Angles degrees clockwise from north; orbit sense given by [clockwise].
 */
object WallSmoothing {
    private const val HALF_BOT = 18.0

    const val DEFAULT_MARGIN = HALF_BOT + 22.0
    const val DEFAULT_STICK = 140.0
    private const val STEP_DEG = 8.0
    private const val MAX_STEPS = 45

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

/**
 * Distancing — the inward/outward tilt on the orbit heading so we hold a preferred
 * engagement range while surfing. Positive tilt closes distance, negative opens it.
 */
object Distancing {
    /** Default engagement range for orbiting duelists (Fencer, SandboxDT, …). */
    const val BASE_TARGET = 450.0

    /** Live engagement range — adapted per-opponent by the main controller based on
     * the enemy's radial (advancing) velocity: a charging opponent pushes this up
     * (keep distance), a kiting opponent pushes it down (close in). Duelists that
     * orbit at ~constant range leave it at [BASE_TARGET]. */
    var targetRange = BASE_TARGET

    private const val GAIN = 55.0
    private const val MAX_TILT = 22.0

    fun tilt(distance: Double): Double = ((distance / targetRange - 1.0) * GAIN).coerceIn(-MAX_TILT, MAX_TILT)
}

/** Euclidean distance. */
fun dist(
    x1: Double,
    y1: Double,
    x2: Double,
    y2: Double,
): Double = sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2))
