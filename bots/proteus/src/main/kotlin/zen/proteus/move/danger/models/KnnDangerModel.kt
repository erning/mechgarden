package zen.proteus.move.danger.models

import zen.proteus.aim.Features
import zen.proteus.knn.KnnModel
import zen.proteus.move.danger.DangerModel
import zen.proteus.move.danger.EnemyWave
import zen.proteus.wave.GuessFactorBins

/**
 * KNN danger model: given our movement features at the enemy's fire time, where
 * did their bullets actually land in GF space. Neighbors vote with point
 * samples (narrow intervals); the density array is cached per wave. This is the
 * adaptive counterpart to the simulated simple guns — it learns their actual
 * gun instead of dodging hypotheses.
 */
internal class KnnDangerModel : DangerModel {
    private val tree = KnnModel(CAPACITY, Features.WEIGHTS)
    private val cache = HashMap<EnemyWave, DoubleArray?>()

    override val minHitRate = 0.0
    override val maxHitRate = 1.0

    override fun binsFor(
        wave: EnemyWave,
        evalX: Double,
        evalY: Double,
    ): DoubleArray? = cache.getOrPut(wave) { computeBins(wave) }

    override fun learn(
        wave: EnemyWave,
        actualGf: Double,
    ) {
        tree.add(KnnModel.Entry(wave.features, actualGf - POINT_HALF_WIDTH, actualGf + POINT_HALF_WIDTH, false))
    }

    override fun forget(wave: EnemyWave) {
        cache.remove(wave)
    }

    private fun computeBins(wave: EnemyWave): DoubleArray? {
        if (tree.size < MIN_DATA) return null
        val bins = DoubleArray(GuessFactorBins.COUNT)
        val step = 2.0 / (GuessFactorBins.COUNT - 1)
        for (i in bins.indices) {
            val gf = GuessFactorBins.guessFactorAt(i)
            bins[i] = tree.densityAt(wave.features, gf - step / 2.0, gf + step / 2.0, includeDidHit = true)
        }
        return bins
    }

    private companion object {
        const val CAPACITY = 5000
        const val MIN_DATA = 30
        const val POINT_HALF_WIDTH = 0.05
    }
}
