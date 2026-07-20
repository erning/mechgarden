package zen.proteus.state

import kotlin.math.sqrt

/**
 * Hit-rate estimate with a Wilson score confidence interval. Strategy switches ask
 * [overlaps] instead of comparing point estimates, so decisions stay aware of
 * sample size: "possibly true" counts as true. With no samples the interval is
 * the whole [0, 1] range and every query overlaps.
 */
internal class HitRate {
    var shots = 0
        private set
    var hits = 0
        private set

    fun record(hit: Boolean) {
        shots++
        if (hit) hits++
    }

    val rate: Double
        get() = if (shots == 0) 0.0 else hits.toDouble() / shots

    /** True if the confidence interval of our hit rate intersects [minRate, maxRate]. */
    fun overlaps(
        minRate: Double,
        maxRate: Double,
    ): Boolean {
        if (shots == 0) return true
        val z = if (shots < SMALL_SAMPLE) Z_WIDE else Z_NARROW
        // Wilson score interval: unlike Wald it never degenerates to a point at
        // p = 0 or p = 1, so a lucky streak does not fake certainty.
        val zz = z * z
        val n = shots.toDouble()
        val p = rate
        val denominator = 1.0 + zz / n
        val center = (p + zz / (2.0 * n)) / denominator
        val halfWidth = z * sqrt(p * (1.0 - p) / n + zz / (4.0 * n * n)) / denominator
        return center + halfWidth >= minRate && center - halfWidth <= maxRate
    }

    private companion object {
        const val SMALL_SAMPLE = 50
        const val Z_WIDE = 1.282 // two-sided 80% while samples are scarce
        const val Z_NARROW = 0.842 // two-sided 60% once the estimate stabilizes
    }
}
