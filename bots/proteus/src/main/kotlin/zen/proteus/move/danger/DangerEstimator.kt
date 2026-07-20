package zen.proteus.move.danger

import zen.proteus.move.danger.models.AvgLinearModel
import zen.proteus.move.danger.models.CircularModel
import zen.proteus.move.danger.models.CurrentGfModel
import zen.proteus.move.danger.models.FlattenerModel
import zen.proteus.move.danger.models.HitBinsModel
import zen.proteus.move.danger.models.HotModel
import zen.proteus.move.danger.models.KnnDangerModel
import zen.proteus.move.danger.models.LinearModel
import zen.proteus.state.HitRate
import zen.proteus.wave.GuessFactorBins
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The danger ensemble: several hypotheses about the enemy's gun, gated by the
 * enemy's hit-rate confidence interval ("a gun that hits this well belongs to
 * this class") and weighted by how well each model predicted real bullet
 * outcomes (rolling score, cubed). Statistical bins and the flattener are
 * always on; the simulated simple guns activate only while the enemy hits like
 * a simple gun. Bullet shadows discount the combined danger afterwards.
 *
 * One instance per enemy (static registry in [Mover]), so learning persists
 * across rounds.
 */
internal class DangerEstimator {
    private val models: List<DangerModel> =
        listOf(
            HitBinsModel(),
            FlattenerModel(),
            HotModel(),
            LinearModel(),
            CircularModel(),
            AvgLinearModel(),
            CurrentGfModel(),
            KnnDangerModel(),
        )
    private val enemyHitRate = HitRate()
    private val scores = HashMap<DangerModel, Double>()

    /** Combined danger mass over [gfLo, gfHi] for [wave] evaluated at (evalX, evalY). */
    fun danger(
        wave: EnemyWave,
        evalX: Double,
        evalY: Double,
        gfLo: Double,
        gfHi: Double,
        shadows: List<DoubleArray>?,
    ): Double {
        var weighted = 0.0
        var weightSum = 0.0
        for (model in models) {
            if (!enemyHitRate.overlaps(model.minHitRate, model.maxHitRate)) continue
            val bins = model.binsFor(wave, evalX, evalY) ?: continue
            val weight = weightOf(model)
            if (weight <= 0.0) continue
            weighted += weight * normalizedMass(bins, gfLo, gfHi)
            weightSum += weight
        }
        var value = if (weightSum > 0.0) weighted / weightSum else 0.0
        if (value > 0.0 && shadows != null) {
            for (shadow in shadows) {
                val overlapLo = max(gfLo, shadow[0])
                val overlapHi = min(gfHi, shadow[1])
                if (overlapLo < overlapHi) {
                    var shadowed = 0.0
                    var shadowWeight = 0.0
                    for (model in models) {
                        if (!enemyHitRate.overlaps(model.minHitRate, model.maxHitRate)) continue
                        val bins = model.binsFor(wave, evalX, evalY) ?: continue
                        val weight = weightOf(model)
                        if (weight <= 0.0) continue
                        shadowed += weight * normalizedMass(bins, overlapLo, overlapHi)
                        shadowWeight += weight
                    }
                    if (shadowWeight > 0.0) {
                        value -= SHADOW_TRUST * shadowed / shadowWeight
                    }
                }
            }
        }
        return value.coerceAtLeast(0.0)
    }

    /** The wave hit us (or collided with our bullet) at [actualGf]. */
    fun onWaveResolved(
        wave: EnemyWave,
        actualGf: Double,
        hitUs: Boolean,
    ) {
        if (hitUs) enemyHitRate.record(true)
        for (model in models) {
            // Score models on how much danger they assigned to the real outcome;
            // unbiased by our dodging when it came from a mid-air collision.
            val bins = model.binsFor(wave, wave.fireSelf.x, wave.fireSelf.y)
            if (bins != null) {
                val score = normalizedMass(bins, actualGf - SCORE_HALF_WIDTH, actualGf + SCORE_HALF_WIDTH)
                val old = scores[model] ?: 1.0
                scores[model] = old * SCORE_DECAY + score * (1.0 - SCORE_DECAY)
            }
            model.learn(wave, actualGf)
            model.forget(wave)
        }
    }

    /** The wave passed us harmlessly. */
    fun onWavePassed(wave: EnemyWave) {
        enemyHitRate.record(false)
        for (model in models) {
            model.onPass(wave)
            model.forget(wave)
        }
    }

    private fun weightOf(model: DangerModel): Double {
        val score = scores[model] ?: 1.0
        return score.pow(3.0)
    }

    private fun normalizedMass(
        bins: DoubleArray,
        gfLo: Double,
        gfHi: Double,
    ): Double {
        var total = 0.0
        for (value in bins) total += value
        return GuessFactorBins.mass(bins, total, gfLo, gfHi)
    }

    private companion object {
        const val SCORE_HALF_WIDTH = 0.05
        const val SCORE_DECAY = 0.98
        const val SHADOW_TRUST = 0.98
    }
}
