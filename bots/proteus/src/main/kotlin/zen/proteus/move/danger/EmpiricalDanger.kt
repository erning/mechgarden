package zen.proteus.move.danger

import zen.proteus.wave.GuessFactorBins

/**
 * Empirical GF danger. Two bin sets: where enemy bullets were actually aimed
 * (real hits on us plus mid-air bullet collisions, which are unbiased by our
 * dodging) and where we have already been (visits, the flattener term). Each set
 * is normalized so dangers from different waves combine comparably.
 */
internal class EmpiricalDanger {
    private val hitBins = DoubleArray(GuessFactorBins.COUNT)
    private val visitBins = DoubleArray(GuessFactorBins.COUNT)
    private var hitTotal = 0.0
    private var visitTotal = 0.0

    fun recordHit(guessFactor: Double) {
        decay(hitBins)
        hitTotal = hitTotal * DECAY + 1.0
        hitBins[GuessFactorBins.index(guessFactor)] += 1.0
    }

    fun recordVisit(
        gfLo: Double,
        gfHi: Double,
    ) {
        decay(visitBins)
        visitTotal = visitTotal * DECAY + 1.0
        val range = GuessFactorBins.range(gfLo, gfHi)
        for (i in range) visitBins[i] += 1.0
    }

    /** Danger of covering the GF interval [gfLo, gfHi]; higher is worse. */
    fun danger(
        gfLo: Double,
        gfHi: Double,
    ): Double =
        HIT_WEIGHT * GuessFactorBins.mass(hitBins, hitTotal, gfLo, gfHi) +
            GuessFactorBins.mass(visitBins, visitTotal, gfLo, gfHi)

    /** [danger] discounted where our in-flight bullets shadow the wave: an enemy
     *  bullet aimed there would collide with ours, so that region is nearly safe. */
    fun danger(
        gfLo: Double,
        gfHi: Double,
        shadows: List<DoubleArray>?,
    ): Double {
        var value = danger(gfLo, gfHi)
        if (value <= 0.0 || shadows == null) return value
        for (shadow in shadows) {
            val overlapLo = kotlin.math.max(gfLo, shadow[0])
            val overlapHi = kotlin.math.min(gfHi, shadow[1])
            if (overlapLo < overlapHi) {
                value -= SHADOW_TRUST * danger(overlapLo, overlapHi)
            }
        }
        return value.coerceAtLeast(0.0)
    }

    /** Recency decay: recent aim/visit evidence outweighs stale evidence. */
    private fun decay(bins: DoubleArray) {
        for (i in bins.indices) bins[i] *= DECAY
    }

    private companion object {
        const val HIT_WEIGHT = 6.0
        const val DECAY = 0.995

        // Residual danger kept inside bullet shadows (we do not fully trust them).
        const val SHADOW_TRUST = 0.98
    }
}
