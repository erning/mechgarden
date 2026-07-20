package zen.proteus.wave

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** 151-bin guess-factor density over GF in [-1, 1] with exponential-kernel smoothing. */
internal object GuessFactorBins {
    const val COUNT = 151
    const val MAX_GF = 1.0

    private const val KERNEL_LAMBDA = 6.0
    private val KERNEL_RADIUS = (KERNEL_LAMBDA * 3).toInt()

    fun index(guessFactor: Double): Int =
        (
            (guessFactor.coerceIn(-MAX_GF, MAX_GF) + MAX_GF) /
                (2.0 * MAX_GF) * (COUNT - 1)
        ).roundToInt()

    /** Bin index range covering the GF interval [gfLo, gfHi], clamped to the bins. */
    fun range(
        gfLo: Double,
        gfHi: Double,
    ): IntRange {
        val lo = index(gfLo).coerceIn(0, COUNT - 1)
        val hi = index(gfHi).coerceIn(0, COUNT - 1)
        return lo..hi
    }

    /** Kernel-smoothed, [total]-normalized mass of [bins] over the GF interval. */
    fun mass(
        bins: DoubleArray,
        total: Double,
        gfLo: Double,
        gfHi: Double,
    ): Double {
        if (total <= 0.0) return 0.0
        var sum = 0.0
        val range = range(gfLo, gfHi)
        for (i in range) sum += smoothedAt(bins, i)
        return sum / total
    }

    /** Bin index of the smoothed peak, or the center bin when [bins] is empty. */
    fun peakIndex(bins: DoubleArray): Int {
        var best = (COUNT - 1) / 2
        var bestValue = Double.NEGATIVE_INFINITY
        for (i in bins.indices) {
            val value = smoothedAt(bins, i)
            if (value > bestValue) {
                bestValue = value
                best = i
            }
        }
        return best
    }

    /** Guess factor at the center of bin [index]. */
    fun guessFactorAt(index: Int): Double = index / (COUNT - 1).toDouble() * 2.0 * MAX_GF - MAX_GF

    private fun smoothedAt(
        bins: DoubleArray,
        i: Int,
    ): Double {
        var sum = 0.0
        val from = (i - KERNEL_RADIUS).coerceAtLeast(0)
        val to = (i + KERNEL_RADIUS).coerceAtMost(COUNT - 1)
        for (j in from..to) {
            sum += bins[j] * exp(-abs(i - j) / KERNEL_LAMBDA)
        }
        return sum
    }
}
