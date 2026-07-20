package zen.proteus.aim

import zen.proteus.wave.GuessFactorBins

/**
 * The enemy's movement profile in guess-factor space: where were they when our
 * waves (real or virtual) passed them. Aimed at the smoothed density peak.
 * Recency-decayed so the profile tracks movement changes across a battle.
 * Kept per enemy in a static registry so it survives round rebuilds.
 */
internal class GfProfile {
    private val bins = DoubleArray(GuessFactorBins.COUNT)
    private var total = 0.0

    /** Records an interval sample (a wave that passed the enemy). */
    fun record(
        gfLo: Double,
        gfHi: Double,
    ) {
        decay()
        total = total * DECAY + 1.0
        for (i in GuessFactorBins.range(gfLo, gfHi)) bins[i] += 1.0
    }

    /** Records a point sample (a real bullet hit at a known GF). */
    fun recordPoint(guessFactor: Double) {
        decay()
        total = total * DECAY + HIT_WEIGHT
        bins[GuessFactorBins.index(guessFactor)] += HIT_WEIGHT
    }

    /** Guess factor of the smoothed density peak; 0 (straight at them) when empty. */
    fun bestGuessFactor(): Double {
        if (total < 1e-9) return 0.0
        return GuessFactorBins.guessFactorAt(GuessFactorBins.peakIndex(bins))
    }

    private fun decay() {
        for (i in bins.indices) bins[i] *= DECAY
    }

    private companion object {
        const val DECAY = 0.995
        const val HIT_WEIGHT = 2.0
    }
}
