package zen.ronin

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

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

    /** Half the bot's hull width (px) — the shared geometry constant for escape
     * envelopes, aim tolerance, shadow bands, and hull-crossing tests. */
    const val HALF_BOT = 18.0

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
}

/**
 * Wall smoothing: rotate a desired travel heading toward open space until a point
 * [stick] pixels ahead lies in the safe area, so the robot skims walls instead of
 * crashing. Angles degrees clockwise from north; orbit sense given by [clockwise].
 */
object WallSmoothing {
    const val DEFAULT_MARGIN = Kinematics.HALF_BOT + 22.0
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
 *
 * The live range is owned by the controller (Ronin) and threaded in as a parameter;
 * this object is stateless, like [Angles] and [Kinematics].
 */
object Distancing {
    /** Default engagement range for orbiting duelists (Fencer, SandboxDT, …). */
    const val BASE_TARGET = 450.0

    private const val GAIN = 55.0
    private const val MAX_TILT = 22.0

    /** Tilt toward [targetRange]: positive closes distance, negative opens it. The
     * controller adapts [targetRange] per scan from the enemy's advancing velocity
     * (a charger pushes it up — keep distance; a kiter pushes it down — close in). */
    fun tilt(
        distance: Double,
        targetRange: Double,
    ): Double = ((distance / targetRange - 1.0) * GAIN).coerceIn(-MAX_TILT, MAX_TILT)
}

/**
 * Distance the point ([x], [y]) can travel along [headingDeg] before reaching a
 * wall of the [fieldWidth]×[fieldHeight] field (0 if already outside / degenerate).
 * Shared by the tracker and the wave-feature sampler.
 */
fun distanceToWall(
    x: Double,
    y: Double,
    headingDeg: Double,
    fieldWidth: Double,
    fieldHeight: Double,
): Double {
    val rad = Math.toRadians(headingDeg)
    val dx = sin(rad)
    val dy = cos(rad)
    var d = Double.MAX_VALUE
    if (dx > WALL_EPS) d = minOf(d, (fieldWidth - x) / dx)
    if (dx < -WALL_EPS) d = minOf(d, -x / dx)
    if (dy > WALL_EPS) d = minOf(d, (fieldHeight - y) / dy)
    if (dy < -WALL_EPS) d = minOf(d, -y / dy)
    return if (d == Double.MAX_VALUE) 0.0 else d.coerceAtLeast(0.0)
}

private const val WALL_EPS = 1e-9

/** Guess factor → histogram bin index for a profile whose center bin is [mid]
 * (bin count `2*mid+1`). The single source for the GF↔bin mapping. */
fun gfToBin(
    guessFactor: Double,
    mid: Int,
): Int = (mid + Math.round(guessFactor.coerceIn(-1.0, 1.0) * mid)).toInt()

/** Mass held in the ±[halfWindow] bin window around [center] (clamped to the
 * histogram bounds). Shared by the DC gun and the GF guns' peak-find. */
fun windowMass(
    hist: DoubleArray,
    center: Int,
    halfWindow: Int,
): Double {
    var sum = 0.0
    val hi = (center + halfWindow).coerceAtMost(hist.size - 1)
    var i = (center - halfWindow).coerceAtLeast(0)
    while (i <= hi) {
        sum += hist[i]
        i++
    }
    return sum
}

/** Index of the bin whose ±[halfWindow] window holds the most mass; the center bin
 * [mid] wins ties and the all-empty case (head-on fallback). The single peak-find
 * used by the DC gun and the GF guns. */
fun peakGfBin(
    hist: DoubleArray,
    mid: Int,
    halfWindow: Int,
): Int {
    var best = mid
    var bestMass = windowMass(hist, mid, halfWindow)
    for (i in hist.indices) {
        if (i == mid) continue
        val mass = windowMass(hist, i, halfWindow)
        if (mass > bestMass) {
            bestMass = mass
            best = i
        }
    }
    return best
}

/**
 * Shared feature-bucketing for the gun segmentation and the surf-danger ensemble —
 * one source of truth for the bucket boundaries both layers slice on.
 */
object Segments {
    const val ACCEL_EPS = 0.5

    fun dist3(d: Double): Int = (d / 200.0).toInt().coerceIn(0, 2)

    fun dist5(d: Double): Int = (d / 160.0).toInt().coerceIn(0, 4)

    fun lat3(lat: Double): Int = (lat / Kinematics.MAX_VELOCITY * 3).toInt().coerceIn(0, 2)

    fun lat5(lat: Double): Int = (lat / Kinematics.MAX_VELOCITY * 5).toInt().coerceIn(0, 4)

    fun wall3(ratio: Double): Int =
        when {
            ratio < 0.5 -> 0
            ratio < 1.0 -> 1
            else -> 2
        }

    fun accel3(sign: Int): Int = sign + 1

    fun accelSign(deltaSpeed: Double): Int =
        when {
            deltaSpeed > ACCEL_EPS -> 1
            deltaSpeed < -ACCEL_EPS -> -1
            else -> 0
        }
}
