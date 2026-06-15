package zen.ronin

import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Segmented guess-factor gun. Fired shots (and optional tick waves) learn where
 * the enemy reached inside the escape envelope for the caller-provided segment;
 * [aim] points at the guess factor whose bot-width window covers the most mass.
 */
class GfGun(
    private val segments: Int = 9,
    private val bins: Int = 31,
    private val retain: Double = 1.0,
) {
    private val table = Array(segments) { DoubleArray(bins) }
    private val mid = bins / 2
    private val learnWaves = mutableListOf<GfWave>()

    fun aim(
        directAngleDeg: Double,
        orbitSign: Int,
        maxEscapeDeg: Double,
        segment: Int,
        halfBotWidthGf: Double = 0.0,
    ): Double = directAngleDeg + peak(segment, halfBotWidthGf) * maxEscapeDeg * orbitSign

    fun onFire(
        fireX: Double,
        fireY: Double,
        fireTime: Long,
        bulletSpeed: Double,
        directAngleDeg: Double,
        orbitSign: Int,
        maxEscapeDeg: Double,
        segment: Int,
        weight: Double = 1.0,
    ) {
        learnWaves += GfWave(fireX, fireY, fireTime, bulletSpeed, directAngleDeg, orbitSign, maxEscapeDeg, segment, weight)
    }

    fun update(
        now: Long,
        enemyX: Double,
        enemyY: Double,
    ) {
        val iter = learnWaves.iterator()
        while (iter.hasNext()) {
            val w = iter.next()
            val d = hypot(enemyX - w.fireX, enemyY - w.fireY)
            if (w.bulletSpeed * (now - w.fireTime) < d) continue
            val actual = Angles.absoluteBearing(w.fireX, w.fireY, enemyX, enemyY)
            val gf = (Angles.normalizeRelative(actual - w.directAngleDeg) / w.maxEscapeDeg * w.orbitSign).coerceIn(-1.0, 1.0)
            register(w.segment, gf, w.weight)
            iter.remove()
        }
    }

    private fun register(
        segment: Int,
        guessFactor: Double,
        weight: Double,
    ) {
        val histogram = table[segment]
        if (retain < 1.0) {
            for (i in histogram.indices) histogram[i] *= retain
        }
        val center = gfToBin(guessFactor, mid)
        for (i in histogram.indices) {
            val d = (center - i).toDouble()
            histogram[i] += weight / (d * d + 1.0)
        }
    }

    private fun peak(
        segment: Int,
        halfBotWidthGf: Double,
    ): Double {
        val histogram = table[segment]
        val halfWindow = (halfBotWidthGf * mid).roundToInt().coerceIn(0, mid)
        val best = peakGfBin(histogram, mid, halfWindow)
        return (best - mid).toDouble() / mid
    }

    private class GfWave(
        val fireX: Double,
        val fireY: Double,
        val fireTime: Long,
        val bulletSpeed: Double,
        val directAngleDeg: Double,
        val orbitSign: Int,
        val maxEscapeDeg: Double,
        val segment: Int,
        val weight: Double,
    )
}

/**
 * Virtual-gun selection. Several aim models each propose an angle every shot; we
 * fire one but record what each *would* have aimed. When a wave reaches the enemy
 * we score which models *would* have hit, and fire with the best — defaulting to
 * circular until another leads. Scores are recency-decayed so a model that led
 * only while the GF guns were cold can't dominate forever.
 */
class VirtualGuns {
    enum class Aim { HEAD_ON, LINEAR, CIRCULAR, GF_DISTLAT, GF_ROLL, GF_ACCEL, GF_WALL, GF_DC }

    private val score = DoubleArray(AIM.size)
    private var attempts = 0.0
    private val ourWaves = mutableListOf<OurWave>()

    fun best(): Aim {
        var best = Aim.CIRCULAR
        var most = score[Aim.CIRCULAR.ordinal]
        for (aim in AIM) {
            if (score[aim.ordinal] > most) {
                most = score[aim.ordinal]
                best = aim
            }
        }
        return best
    }

    fun hitRate(aim: Aim): Double = (score[aim.ordinal] + PRIOR_WEIGHT * PRIOR_RATE) / (attempts + PRIOR_WEIGHT)

    fun onFire(
        fireX: Double,
        fireY: Double,
        fireTime: Long,
        bulletSpeed: Double,
        anglesByAim: DoubleArray,
    ) {
        // Adopt the caller's array — Gun builds it fresh per shot and doesn't retain
        // or mutate it, so no defensive copy is needed.
        ourWaves += OurWave(fireX, fireY, fireTime, bulletSpeed, anglesByAim)
    }

    fun update(
        now: Long,
        enemyX: Double,
        enemyY: Double,
    ) {
        val iter = ourWaves.iterator()
        while (iter.hasNext()) {
            val w = iter.next()
            val d = hypot(enemyX - w.fireX, enemyY - w.fireY)
            if (w.radius(now) < d) continue
            val actual = Angles.absoluteBearing(w.fireX, w.fireY, enemyX, enemyY)
            val tolerance = Math.toDegrees(kotlin.math.atan(Kinematics.HALF_BOT / d))
            for (aim in AIM) {
                val hit = if (kotlin.math.abs(Angles.normalizeRelative(w.angles[aim.ordinal] - actual)) < tolerance) 1.0 else 0.0
                score[aim.ordinal] = score[aim.ordinal] * RETAIN + hit
            }
            attempts = attempts * RETAIN + 1.0
            iter.remove()
        }
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
        const val PRIOR_RATE = 0.4
        const val PRIOR_WEIGHT = 10.0
        const val RETAIN = 0.998

        /** Cached enum array — iterating this avoids the per-call clone of Aim.values(). */
        private val AIM = Aim.values()
    }
}
