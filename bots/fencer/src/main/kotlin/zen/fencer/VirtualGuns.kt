package zen.fencer

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot

/**
 * Virtual-gun selection: several aim models each
 * propose an angle every shot; we fire one, but record what each *would* have
 * aimed. When a recorded shot's wave reaches the enemy we score which models
 * *would* have hit (aim within the enemy's angular width), and fire with the
 * model scoring best — defaulting to circular until another leads.
 *
 * The score is **recency-weighted** (review #5): each resolved shot decays the
 * running scores by [RETAIN] before crediting the hits, so a model that led only
 * while the statistical guns were cold can't dominate forever — once the GF guns
 * warm up and start out-hitting it, selection follows them. A plain lifetime
 * count never lets the late bloomer take over.
 *
 * Bot-local stats. The enemy can't see our bullets' direction, so these virtual
 * outcomes are honest signal.
 */
class VirtualGuns {
    enum class Aim { HEAD_ON, LINEAR, CIRCULAR, GUESS_FACTOR, GUESS_FACTOR_ROLLING, GUESS_FACTOR_ACCEL, GUESS_FACTOR_WALL }

    /** Recency-decayed virtual-hit score per model, and the matching decayed
     * count of resolved shots (shared denominator for the hit-rate estimate). */
    private val score = DoubleArray(Aim.values().size)
    private var attempts = 0.0
    private val ourWaves = mutableListOf<OurWave>()

    /** The model to fire with now: highest recent score, circular winning ties. */
    fun best(): Aim {
        var best = Aim.CIRCULAR
        var most = score[Aim.CIRCULAR.ordinal]
        for (aim in Aim.values()) {
            if (score[aim.ordinal] > most) {
                most = score[aim.ordinal]
                best = aim
            }
        }
        return best
    }

    /** Smoothed recent hit rate of [aim] (Beta-style prior so it starts ~PRIOR_RATE). */
    fun hitRate(aim: Aim): Double = (score[aim.ordinal] + PRIOR_WEIGHT * PRIOR_RATE) / (attempts + PRIOR_WEIGHT)

    /** Record a real shot, with every model's candidate angle (indexed by ordinal). */
    fun onFire(
        fireX: Double,
        fireY: Double,
        fireTime: Long,
        bulletSpeed: Double,
        anglesByAim: DoubleArray,
    ) {
        ourWaves += OurWave(fireX, fireY, fireTime, bulletSpeed, anglesByAim.copyOf())
    }

    /** Score recorded shots whose wave has reached the enemy at ([enemyX], [enemyY]). */
    fun update(
        now: Long,
        enemyX: Double,
        enemyY: Double,
    ) {
        val iter = ourWaves.iterator()
        while (iter.hasNext()) {
            val w = iter.next()
            val dist = hypot(enemyX - w.fireX, enemyY - w.fireY)
            if (w.radius(now) < dist) continue
            val actual = Angles.absoluteBearing(w.fireX, w.fireY, enemyX, enemyY)
            val tolerance = Math.toDegrees(atan(HALF_BOT / dist))
            // Decay every model's running score, then credit this shot's hits, so
            // the score tracks *recent* performance rather than an all-time total.
            for (aim in Aim.values()) {
                val hit = if (abs(Angles.normalizeRelative(w.angles[aim.ordinal] - actual)) < tolerance) 1.0 else 0.0
                score[aim.ordinal] = score[aim.ordinal] * RETAIN + hit
            }
            attempts = attempts * RETAIN + 1.0
            iter.remove()
        }
    }

    /** Snapshot size for decayed scores + decayed attempt count. */
    fun snapshotSize(): Int = score.size + 1

    fun snapshot(): DoubleArray =
        DoubleArray(score.size + 1).also { out ->
            System.arraycopy(score, 0, out, 0, score.size)
            out[score.size] = attempts
        }

    fun restore(data: DoubleArray) {
        if (data.size != snapshotSize()) return
        System.arraycopy(data, 0, score, 0, score.size)
        attempts = data[score.size]
    }

    private class OurWave(
        val fireX: Double,
        val fireY: Double,
        val fireTime: Long,
        val bulletSpeed: Double,
        val angles: DoubleArray,
    ) {
        fun radius(now: Long): Double = bulletSpeed * (now - fireTime)
    }

    private companion object {
        const val HALF_BOT = 18.0
        const val PRIOR_RATE = 0.4
        const val PRIOR_WEIGHT = 10.0

        /** Per-resolved-shot decay of the virtual-gun scores. Half-life ≈ 140
         * shots — slow enough to accumulate signal, fast enough to follow the GF
         * guns once they out-hit the early circular leader. */
        const val RETAIN = 0.998
    }
}
