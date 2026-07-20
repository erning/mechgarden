package zen.proteus.wave

import robocode.Rules
import zen.proteus.core.Angles
import zen.proteus.core.Battlefield
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * An enemy bullet modelled as an expanding ring. [fireTime] is the scan tick at
 * which we detected the energy drop; the engine spawns the bullet at the start
 * of that turn — at the enemy's position one tick before detection, ([originX],
 * [originY]) — and moves it in the same turn, so the radius at time t is
 * (t - fireTime + 1) * speed.
 *
 * Guess factors are relative to [directAngleRadians] (bearing origin -> our
 * position at fire time), scaled by [lateralDirection] and the classic maximum
 * escape angle asin(8 / bulletSpeed); walls are ignored until precise MEA lands.
 */
internal class Wave(
    val originX: Double,
    val originY: Double,
    val power: Double,
    val fireTime: Long,
    val directAngleRadians: Double,
    val lateralDirection: Double,
    val bulletFired: Boolean = true,
) {
    val speed: Double = Rules.getBulletSpeed(power)
    val maxEscapeAngleRadians: Double = asin(Rules.MAX_VELOCITY / speed)

    var visitGfLo = Double.POSITIVE_INFINITY
        private set
    var visitGfHi = Double.NEGATIVE_INFINITY
        private set

    val hasVisitInterval: Boolean
        get() = visitGfLo <= visitGfHi

    fun radius(time: Long): Double = (time - fireTime + 1) * speed

    fun distanceTo(
        x: Double,
        y: Double,
    ): Double = hypot(x - originX, y - originY)

    /** Ticks until the ring reaches a point [distance] from the origin. */
    fun ticksUntilArrival(
        distance: Double,
        time: Long,
    ): Double = max(0.0, distance - radius(time)) / speed

    fun guessFactor(angleRadians: Double): Double =
        lateralDirection *
            Angles.normalizeRelative(angleRadians - directAngleRadians) /
            maxEscapeAngleRadians

    /** Maps an absolute-angle interval to an ordered GF interval. */
    fun gfInterval(
        loRadians: Double,
        hiRadians: Double,
    ): DoubleArray {
        // Full-circle interval (origin inside the robot square): every GF possible.
        if (hiRadians - loRadians >= Angles.TWO_PI) {
            return doubleArrayOf(-FULL_RANGE_GF, FULL_RANGE_GF)
        }
        val gfLo = guessFactor(loRadians)
        val gfHi = guessFactor(hiRadians)
        return doubleArrayOf(min(gfLo, gfHi), max(gfLo, gfHi))
    }

    /** Records that the ring covered the absolute-angle interval [loRadians, hiRadians]. */
    fun recordVisit(
        loRadians: Double,
        hiRadians: Double,
    ) {
        val gf = gfInterval(loRadians, hiRadians)
        visitGfLo = min(visitGfLo, gf[0])
        visitGfHi = max(visitGfHi, gf[1])
    }

    /**
     * Precise intersection: the absolute-angle interval subtended by the part of
     * the ring band (r0, r1] overlapping the robot square at (cx, cy). Null when
     * there is no overlap; [-PI, PI] when the origin sits inside the square.
     */
    fun intersection(
        cx: Double,
        cy: Double,
        r0: Double,
        r1: Double,
    ): DoubleArray? {
        val half = Battlefield.ROBOT_HALF_SIZE
        if (abs(cx - originX) <= half && abs(cy - originY) <= half) {
            return doubleArrayOf(-PI, PI)
        }
        val angles = ArrayList<Double>(8)
        val edgeX = doubleArrayOf(cx - half, cx + half)
        val edgeY = doubleArrayOf(cy - half, cy + half)
        // Corners whose distance falls inside the band.
        for (x in edgeX) {
            for (y in edgeY) {
                val d = hypot(x - originX, y - originY)
                if (d in r0..r1) angles.add(atan2(x - originX, y - originY))
            }
        }
        // Intersections of the band's two circles with the four edges.
        for (r in doubleArrayOf(r0, r1)) {
            for (x in edgeX) {
                val dx = x - originX
                if (abs(dx) <= r) {
                    val dy = sqrt(r * r - dx * dx)
                    for (y in doubleArrayOf(originY + dy, originY - dy)) {
                        if (y in cy - half..cy + half) {
                            angles.add(atan2(x - originX, y - originY))
                        }
                    }
                }
            }
            for (y in edgeY) {
                val dy = y - originY
                if (abs(dy) <= r) {
                    val dx = sqrt(r * r - dy * dy)
                    for (x in doubleArrayOf(originX + dx, originX - dx)) {
                        if (x in cx - half..cx + half) {
                            angles.add(atan2(x - originX, y - originY))
                        }
                    }
                }
            }
        }
        if (angles.isEmpty()) return null
        val centerRadians = atan2(cx - originX, cy - originY)
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (angle in angles) {
            val rel = Angles.normalizeRelative(angle - centerRadians)
            lo = min(lo, rel)
            hi = max(hi, rel)
        }
        return doubleArrayOf(centerRadians + lo, centerRadians + hi)
    }

    companion object {
        // GF range used for full-circle intervals; the bins clamp to [-1, 1].
        const val FULL_RANGE_GF = 2.0
    }
}
