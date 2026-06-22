package zen.mirage

import kotlin.math.abs

/**
 * Precise maximum escape angle (precise MEA).
 *
 * The textbook MEA `asin(MAX_V / bulletSpeed)` assumes the target is at rest and
 * can instantly reach full speed perpendicular to the line of fire — it ignores
 * walls, the target's current velocity, and the body-turn limit. Against a
 * competent mover near a wall it overstates the reachable angle, which both
 * *spreads the surf-danger histogram too thin* (real visits cluster near GF 0
 * but get smeared across bins that are physically unreachable) and *mis-scales
 * the gun's guess factors*. Top-tier guns and surfers (Diamond, DrussGT) replace
 * it with a precise MEA: roll the target's kinematics forward under its fastest
 * wall-smoothed escape in each direction until the bullet catches up, and take
 * the largest bearing offset it reached. That is what this object computes.
 *
 * It is a pure function of the fire-time geometry. Angles in radians (0 = north,
 * clockwise). Returns a half-angle: the bearing offset the target can reach on
 * *one* side; the escape is symmetric in open space so the GF range is
 * [-preciseMea, +preciseMea]. Wall asymmetry makes the two sides differ, so the
 * caller takes the larger of the two.
 */
object PreciseMea {
    /** Upper bound on the escape simulation. The bullet always catches a target at
     *  distance d within roughly d/bulletSpeed ticks; 150 is generous headroom and
     *  caps the cost when the target is pinned against a wall. */
    private const val MAX_TICKS = 150

    /**
     * Largest bearing offset the target can reach from [sourceX], [sourceY] before a
     * bullet of [bulletSpeed] (fired at [fireTime]) catches it, starting from pose
     * [target]. Rolls the target's fastest wall-smoothed escape in both orbit
     * directions and returns the bigger offset's absolute value.
     */
    fun halfEscapeRadians(
        sourceX: Double,
        sourceY: Double,
        fireTime: Long,
        bulletSpeed: Double,
        target: Kinematics.Pose,
        now: Long,
        fieldWidth: Double,
        fieldHeight: Double,
    ): Double {
        val a = escapeBearing(sourceX, sourceY, fireTime, bulletSpeed, target, now, +1, fieldWidth, fieldHeight)
        val b = escapeBearing(sourceX, sourceY, fireTime, bulletSpeed, target, now, -1, fieldWidth, fieldHeight)
        return maxOf(abs(a), abs(b))
    }

    /**
     * Per-direction escape half-angles: [0] is the reachable offset toward one
     * orbit sense, [1] toward the other. Walls make these asymmetric, and a
     * direction-aware surfer/gun uses each side's own MEA rather than a symmetric
     * max — that is the BeepBoop/DrussGT precise-MEA form. Both entries are
     * non-negative (magnitudes).
     */
    fun directionalEscapeRadians(
        sourceX: Double,
        sourceY: Double,
        fireTime: Long,
        bulletSpeed: Double,
        target: Kinematics.Pose,
        now: Long,
        fieldWidth: Double,
        fieldHeight: Double,
    ): DoubleArray {
        val a = abs(escapeBearing(sourceX, sourceY, fireTime, bulletSpeed, target, now, +1, fieldWidth, fieldHeight))
        val b = abs(escapeBearing(sourceX, sourceY, fireTime, bulletSpeed, target, now, -1, fieldWidth, fieldHeight))
        return doubleArrayOf(a, b)
    }

    /** Bearing offset (radians, signed) the target reaches escaping in [dir] before
     *  the bullet arrives. Positive is clockwise from source→targetInitial. */
    private fun escapeBearing(
        sourceX: Double,
        sourceY: Double,
        fireTime: Long,
        bulletSpeed: Double,
        target: Kinematics.Pose,
        now: Long,
        dir: Int,
        fieldWidth: Double,
        fieldHeight: Double,
    ): Double {
        val directAngle = Angles.absoluteBearing(sourceX, sourceY, target.x, target.y)
        var x = target.x
        var y = target.y
        var heading = target.headingRadians
        var velocity = target.velocity
        var bestOffset = 0.0
        var ticks = 0
        while (ticks < MAX_TICKS) {
            // Drive perpendicular to source→us (pure orbit, no distancing tilt — the
            // goal is to maximize the bearing offset, not hold a range), wall-smoothed.
            val centerToUs = Angles.absoluteBearing(sourceX, sourceY, x, y)
            val desired =
                WallSmoothing.smoothedHeadingRadians(
                    x,
                    y,
                    centerToUs + dir * Angles.HALF_PI,
                    dir > 0,
                    fieldWidth,
                    fieldHeight,
                )
            var angle = Angles.normalizeRelative(desired - heading)
            var driveSign = 1
            if (abs(angle) > Angles.HALF_PI) {
                angle = Angles.normalizeRelative(angle + Angles.PI)
                driveSign = -1
            }
            val maxTurn = Kinematics.maxTurnRateRadians(velocity)
            heading = Angles.normalizeAbsolute(heading + angle.coerceIn(-maxTurn, maxTurn))
            velocity = Kinematics.nextVelocity(velocity, driveSign)
            x += kotlin.math.sin(heading) * velocity
            y += kotlin.math.cos(heading) * velocity
            ticks++
            val reach = bulletSpeed * (now + ticks - fireTime)
            if (reach * reach >= (x - sourceX) * (x - sourceX) + (y - sourceY) * (y - sourceY)) break
            val offset = Angles.normalizeRelative(Angles.absoluteBearing(sourceX, sourceY, x, y) - directAngle)
            if (abs(offset) > abs(bestOffset)) bestOffset = offset
        }
        return bestOffset
    }
}
