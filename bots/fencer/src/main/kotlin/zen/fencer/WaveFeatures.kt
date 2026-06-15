package zen.fencer

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The situation **we** (the dodger) were in when an enemy wave was fired — the
 * feature vector the [DangerBuffers] segmenters slice. Sampled once per wave
 * from the fire-time snapshot (the enemy aims at what it saw then), raw and
 * unbucketed: each buffer buckets the axes it cares about.
 */
class WaveFeatures(
    /** Our distance from the wave source, px. */
    val distance: Double,
    /** Our speed across the source→us line, px/tick (absolute). */
    val lateralAbs: Double,
    /** Our speed change at fire: +1 accelerating, 0 cruising, −1 braking
     * (|Δv| under [ACCEL_EPS] counts as cruise). */
    val accelSign: Int,
    /** Ticks since our speed last dropped (or direction flipped) at fire time —
     * the oscillation-phase axis an anti-surfer gun keys on. */
    val ticksSinceDecel: Long,
    /** Room our current travel direction has before a wall, expressed as
     * wall-ticks / bullet-flight-ticks (under ~0.5 the wall truncates our
     * escape; near/above 1 it barely matters). */
    val wallForwardRatio: Double,
) {
    companion object {
        const val ACCEL_EPS = 0.5

        /** Build the fire-time feature vector for a wave shot at [us] (our
         * fire-time snapshot) from ([sourceX], [sourceY]); [prevSpeedAbs] is our
         * speed one scan before the fire-time snapshot (for the accel sign) and
         * [ticksSinceDecel] our decel clock at fire. */
        fun at(
            us: Snapshot,
            sourceX: Double,
            sourceY: Double,
            bulletSpeed: Double,
            prevSpeedAbs: Double,
            ticksSinceDecel: Long,
            fieldWidth: Double,
            fieldHeight: Double,
        ): WaveFeatures {
            val sourceToUs = Angles.absoluteBearing(sourceX, sourceY, us.x, us.y)
            val lateral = us.velocity * sin(Math.toRadians(us.headingDeg - sourceToUs))
            val distance = kotlin.math.hypot(us.x - sourceX, us.y - sourceY)
            val dSpeed = abs(us.velocity) - prevSpeedAbs
            val accelSign =
                when {
                    dSpeed > ACCEL_EPS -> 1
                    dSpeed < -ACCEL_EPS -> -1
                    else -> 0
                }
            val travelHeading = if (us.velocity >= 0.0) us.headingDeg else us.headingDeg + 180.0
            val wallSpace = distanceToWall(us.x, us.y, travelHeading, fieldWidth, fieldHeight)
            val flightTicks = (distance / bulletSpeed).coerceAtLeast(1.0)
            val wallTicks = wallSpace / Kinematics.MAX_VELOCITY
            return WaveFeatures(
                distance = distance,
                lateralAbs = abs(lateral),
                accelSign = accelSign,
                ticksSinceDecel = ticksSinceDecel,
                wallForwardRatio = wallTicks / flightTicks,
            )
        }

        /** Distance from (x, y) along [headingDeg] to the first arena wall. */
        private fun distanceToWall(
            x: Double,
            y: Double,
            headingDeg: Double,
            fieldWidth: Double,
            fieldHeight: Double,
        ): Double {
            val rad = Math.toRadians(headingDeg)
            val dx = sin(rad)
            val dy = cos(rad)
            var dist = Double.MAX_VALUE
            if (dx > EPS) dist = minOf(dist, (fieldWidth - x) / dx)
            if (dx < -EPS) dist = minOf(dist, -x / dx)
            if (dy > EPS) dist = minOf(dist, (fieldHeight - y) / dy)
            if (dy < -EPS) dist = minOf(dist, -y / dy)
            return if (dist == Double.MAX_VALUE) 0.0 else dist.coerceAtLeast(0.0)
        }

        private const val EPS = 1e-9
    }
}
