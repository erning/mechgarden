package zen.proteus.wave

import zen.proteus.move.OurBullets
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * Bullet shadows: where our in-flight bullets cross an enemy wave's ring. An
 * enemy bullet aimed through the shadowed angular interval would collide with
 * one of ours mid-flight, so that part of the wave is (nearly) safe.
 *
 * Shadow angles are collected over the ticks our bullet spends inside the ring
 * band and widened by the enemy bullet segment's angular width, then mapped to
 * GF space. This is the standard (t, t) shadow; the half-weight cross-tick
 * terms of "correct bullet shadowing" are left for a later milestone.
 */
internal object BulletShadows {
    /** GF intervals of [wave] shadowed by [bullets], as seen from (selfX, selfY). */
    fun intervals(
        wave: Wave,
        bullets: List<OurBullets.Tracked>,
        selfX: Double,
        selfY: Double,
        time: Long,
    ): List<DoubleArray> {
        if (bullets.isEmpty()) return emptyList()
        // Only ticks before the wave reaches us matter; everything after is moot.
        val lastTick =
            time + 2 +
                ((wave.distanceTo(selfX, selfY) - wave.radius(time)) / wave.speed).toInt() + 2
        val result = ArrayList<DoubleArray>(bullets.size)
        for (bullet in bullets) {
            var lo = Double.POSITIVE_INFINITY
            var hi = Double.NEGATIVE_INFINITY
            for (t in (time + 1)..lastTick) {
                val x = bullet.xAt(t)
                val y = bullet.yAt(t)
                val distance = wave.distanceTo(x, y)
                val r1 = wave.radius(t)
                val r0 = r1 - wave.speed
                if (distance in r0..r1) {
                    val angle = atan2(x - wave.originX, y - wave.originY)
                    // The enemy bullet's own segment spans wave.speed / distance
                    // radians at this range; widen the shadow by that on both sides.
                    val widen = wave.speed / max(distance, 1.0)
                    lo = min(lo, angle - widen)
                    hi = max(hi, angle + widen)
                }
                // Only stop after the crossing is done; before the bullet reaches
                // the band it is still approaching and must keep simulating.
                if (lo <= hi && distance > r1 + bullet.speed + WAVE_CLEARANCE) break
            }
            if (lo <= hi) {
                val gfLo = wave.guessFactor(lo)
                val gfHi = wave.guessFactor(hi)
                result.add(doubleArrayOf(min(gfLo, gfHi), max(gfLo, gfHi)))
            }
        }
        return result
    }

    // Once our bullet is this far beyond the ring it cannot cross back in.
    private const val WAVE_CLEARANCE = 40.0
}
