package zen.proteus.move.danger.models

import zen.proteus.move.danger.DangerModel
import zen.proteus.move.danger.EnemyWave
import zen.proteus.wave.GuessFactorBins

/** Statistical GF model: where enemy bullets actually landed, recency-decayed. */
internal class HitBinsModel : DangerModel {
    private val bins = DoubleArray(GuessFactorBins.COUNT)
    private var total = 0.0

    override val minHitRate = 0.0
    override val maxHitRate = 1.0

    override fun binsFor(
        wave: EnemyWave,
        evalX: Double,
        evalY: Double,
    ): DoubleArray = bins

    override fun learn(
        wave: EnemyWave,
        actualGf: Double,
    ) {
        for (i in bins.indices) bins[i] *= DECAY
        total = total * DECAY + 1.0
        bins[GuessFactorBins.index(actualGf)] += 1.0
    }

    fun totalMass(): Double = total

    private companion object {
        const val DECAY = 0.995
    }
}

/** Flattener: where we have already been, so their learning gun finds us gone. */
internal class FlattenerModel : DangerModel {
    private val bins = DoubleArray(GuessFactorBins.COUNT)
    private var total = 0.0

    override val minHitRate = 0.0
    override val maxHitRate = 1.0

    override fun binsFor(
        wave: EnemyWave,
        evalX: Double,
        evalY: Double,
    ): DoubleArray = bins

    override fun onPass(wave: EnemyWave) {
        for (i in bins.indices) bins[i] *= DECAY
        total = total * DECAY + 1.0
        for (i in GuessFactorBins.range(wave.wave.visitGfLo, wave.wave.visitGfHi)) {
            bins[i] += 1.0
        }
    }

    fun totalMass(): Double = total

    private companion object {
        const val DECAY = 0.995
    }
}
