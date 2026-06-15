package zen.fencer

import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Segmented guess-factor gun. Fired shots, and optional tick waves, learn where
 * the enemy reached inside the escape envelope for the caller-provided segment.
 */
class GuessFactorGun(
    private val segments: Int = 9,
    private val bins: Int = 31,
    private val retain: Double = 1.0,
) {
    private val table = Array(segments) { DoubleArray(bins) }
    private val mid = bins / 2
    private val learnWaves = mutableListOf<GfWave>()

    /** Candidate firing angle now: aim at the guess factor whose bot-width
     * window ([halfBotWidthGf], in GF units) covers the most visit mass. */
    fun aim(
        directAngleDeg: Double,
        orbitSign: Int,
        maxEscapeDeg: Double,
        segment: Int,
        halfBotWidthGf: Double = 0.0,
    ): Double = directAngleDeg + peak(segment, halfBotWidthGf) * maxEscapeDeg * orbitSign

    /** Record a fired shot's fire-time situation, to learn from when it lands.
     * [weight] scales its training contribution (1.0 = a real shot; tick waves
     * pass a fraction). */
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

    /** Resolve shots whose wave has reached the enemy: learn the guess factor the
     * enemy actually reached (a visit), into that shot's segment. */
    fun update(
        now: Long,
        enemyX: Double,
        enemyY: Double,
    ) {
        val iter = learnWaves.iterator()
        while (iter.hasNext()) {
            val w = iter.next()
            val dist = hypot(enemyX - w.fireX, enemyY - w.fireY)
            if (w.bulletSpeed * (now - w.fireTime) < dist) continue
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
        // retain < 1 fades old observations first, making this an anti-surfer
        // gun that tracks the enemy's recent guess-factor bias.
        if (retain < 1.0) {
            for (i in histogram.indices) histogram[i] *= retain
        }
        val center = (mid + Math.round(guessFactor.coerceIn(-1.0, 1.0) * mid)).toInt()
        for (i in histogram.indices) {
            val d = (center - i).toDouble()
            histogram[i] += weight / (d * d + 1.0)
        }
    }

    /** Guess factor whose [halfBotWidthGf]-wide window holds the most visit mass
     * for [segment] (0 = head-on if cold). */
    private fun peak(
        segment: Int,
        halfBotWidthGf: Double,
    ): Double {
        val histogram = table[segment]
        val halfWindow = (halfBotWidthGf * mid).roundToInt().coerceIn(0, mid)
        var best = mid
        var bestMass = windowMass(histogram, mid, halfWindow)
        for (i in histogram.indices) {
            val mass = windowMass(histogram, i, halfWindow)
            if (mass > bestMass) {
                bestMass = mass
                best = i
            }
        }
        return (best - mid).toDouble() / mid
    }

    private fun windowMass(
        histogram: DoubleArray,
        center: Int,
        halfWindow: Int,
    ): Double {
        var sum = 0.0
        for (i in (center - halfWindow).coerceAtLeast(0)..(center + halfWindow).coerceAtMost(histogram.size - 1)) {
            sum += histogram[i]
        }
        return sum
    }

    /** Snapshot size for all segments' flattened histograms. */
    fun snapshotSize(): Int = table.size * bins

    fun snapshot(): DoubleArray {
        val out = DoubleArray(snapshotSize())
        for (s in table.indices) System.arraycopy(table[s], 0, out, s * bins, bins)
        return out
    }

    fun restore(data: DoubleArray) {
        if (data.size != snapshotSize()) return
        for (s in table.indices) System.arraycopy(data, s * bins, table[s], 0, bins)
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
