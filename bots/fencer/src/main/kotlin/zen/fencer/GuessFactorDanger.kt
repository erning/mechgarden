package zen.fencer

/**
 * Surf-danger histogram over guess factors. Visits register the hull interval
 * swept by a passing wave; hits register a sharp point observation.
 */
class GuessFactorDanger(
    private val bins: Int = 47,
) {
    init {
        require(bins % 2 == 1) { "bin count must be odd so GF 0 is centered, was $bins" }
    }

    private val weight = DoubleArray(bins)
    private val mid = bins / 2

    private fun index(guessFactor: Double): Int = (mid + Math.round(guessFactor.coerceIn(-1.0, 1.0) * mid)).toInt()

    /** Record a wave that swept our hull across [lowGf, highGf]: the weight is
     * spread uniformly over exactly the covered bins. */
    fun registerInterval(
        lowGf: Double,
        highGf: Double,
        weight: Double,
    ) {
        val lo = index(minOf(lowGf, highGf))
        val hi = index(maxOf(lowGf, highGf))
        val per = weight / (hi - lo + 1)
        for (i in lo..hi) this.weight[i] += per
    }

    /** Record a precise observation (a real bullet's hit position) at
     * [guessFactor]: a sharp quartic kernel, informing only its vicinity. */
    fun registerSharp(
        guessFactor: Double,
        weight: Double,
    ) {
        val center = index(guessFactor)
        for (i in this.weight.indices) {
            val d = (center - i).toDouble()
            this.weight[i] += weight / (d * d * d * d + 1.0)
        }
    }

    /** Fade every bin by [retain] — the rolling (anti-surfer-gun) profiles call
     * this before registering, so they track the enemy gun's *recent* aim. */
    fun fade(retain: Double) {
        for (i in weight.indices) weight[i] *= retain
    }

    /** Total accumulated weight — a confidence proxy (how much this histogram has
     * actually observed), used to fuse a sparse fine profile with a mature one. */
    fun totalWeight(): Double = weight.sum()

    /** Raw bin weights snapshot. */
    fun snapshot(): DoubleArray = weight.copyOf()

    fun restore(data: DoubleArray) {
        if (data.size == weight.size) System.arraycopy(data, 0, weight, 0, weight.size)
    }

    /** Mean danger share over [lowGf, highGf] — the hull-coverage read: the
     * average (per-bin, normalized by total observed weight) danger across the
     * interval our hull would occupy, comparable across sample sizes and to the
     * other bounded risk terms in the surf cost. */
    fun shareWindow(
        lowGf: Double,
        highGf: Double,
    ): Double {
        val total = weight.sum()
        if (total <= 0.0) return 0.0
        val lo = index(minOf(lowGf, highGf))
        val hi = index(maxOf(lowGf, highGf))
        var sum = 0.0
        for (i in lo..hi) sum += weight[i]
        return sum / (hi - lo + 1) / total
    }

    /** Per-bin normalized shares (each bin's fraction of the total observed
     * weight; zeros when empty) — the bake-time export: a [shareWindow] read
     * equals the mean of these over the window's bins, so a danger profile can
     * be fused once per wave instead of per read. */
    fun shares(): DoubleArray {
        val total = weight.sum()
        val out = DoubleArray(weight.size)
        if (total <= 0.0) return out
        for (i in weight.indices) out[i] = weight[i] / total
        return out
    }

    companion object {
        /** Bins of the shared GF axis (all baked profiles use this width). */
        const val BINS = 47

        /** Bin index of [guessFactor] on a [BINS]-wide axis — the same mapping
         * instances use, exposed for window reads over baked arrays. */
        fun binIndex(guessFactor: Double): Int {
            val mid = BINS / 2
            return (mid + Math.round(guessFactor.coerceIn(-1.0, 1.0) * mid)).toInt()
        }

        /** Mean of [bins] over the GF window [lowGf, highGf] — the baked-array
         * equivalent of [shareWindow]. */
        fun windowMean(
            bins: DoubleArray,
            lowGf: Double,
            highGf: Double,
        ): Double {
            val lo = binIndex(minOf(lowGf, highGf))
            val hi = binIndex(maxOf(lowGf, highGf))
            var sum = 0.0
            for (i in lo..hi) sum += bins[i]
            return sum / (hi - lo + 1)
        }
    }
}
