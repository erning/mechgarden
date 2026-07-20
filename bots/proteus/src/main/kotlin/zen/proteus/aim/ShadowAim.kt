package zen.proteus.aim

import robocode.Rules
import zen.proteus.knn.KnnModel
import zen.proteus.move.Mover
import zen.proteus.move.OurBullets
import zen.proteus.move.danger.EnemyWave
import zen.proteus.state.BotState
import zen.proteus.wave.BulletShadows
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Active bullet shadowing (M6): at fire time we do not always shoot the
 * density peak. Candidate angles around the peak are scored jointly —
 *
 *   score = hitProbability / postShadowDanger ^ k
 *   k = 2 * (theirHitRate / ourHitRate * theirAvgPower / ourAvgPower) ^ 0.25
 *
 * postShadowDanger is the danger over all surfed enemy waves after adding the
 * shadow our candidate bullet would cast. The more accurate and heavier the
 * enemy shoots, the more often a shot deliberately goes for the shadow instead
 * of the hit. Runs only in the ticks right before the gun is ready (the cheap
 * path — straight to the peak — covers everything else).
 */
internal class ShadowAim(
    private val mover: Mover,
) {
    /** Picks the fire angle's guess factor; falls back to [peakGf] when the
     *  gun has no data or no shadow candidate wins. */
    fun pickGuessFactor(
        self: BotState,
        power: Double,
        peakGf: Double,
        directAngleRadians: Double,
        maxEscapeAngleRadians: Double,
        lateralDirection: Double,
        neighbors: List<KnnModel.Neighbor>,
        model: KnnModel,
        waves: List<EnemyWave>,
        ourHitRate: Double,
        theirHitRate: Double,
        ourAvgPower: Double,
        theirAvgPower: Double,
        time: Long,
    ): Double {
        if (neighbors.isEmpty() || waves.isEmpty()) return peakGf

        val hitRateRatio = (theirHitRate / max(ourHitRate, 0.01)).coerceIn(0.5, 2.0)
        val powerRatio = (theirAvgPower / max(ourAvgPower, 0.1)).coerceIn(0.5, 2.0)
        val exponent = 2.0 * (hitRateRatio * powerRatio).pow(0.25)
        val baseBullets = mover.ourBulletsInFlight()

        fun angleOf(gf: Double): Double = directAngleRadians + gf * maxEscapeAngleRadians * lateralDirection

        var best = peakGf
        var bestScore = score(peakGf, self, power, angleOf(peakGf), neighbors, model, waves, baseBullets, time, exponent)
        for (offset in OFFSETS) {
            for (sign in intArrayOf(1, -1)) {
                val candidate = (peakGf + sign * offset).coerceIn(-1.0, 1.0)
                if (candidate == best) continue
                val score =
                    score(candidate, self, power, angleOf(candidate), neighbors, model, waves, baseBullets, time, exponent)
                if (score > bestScore * (1.0 + WIN_MARGIN)) {
                    bestScore = score
                    best = candidate
                }
            }
        }
        return best
    }

    private fun score(
        gf: Double,
        self: BotState,
        power: Double,
        angleRadians: Double,
        neighbors: List<KnnModel.Neighbor>,
        model: KnnModel,
        waves: List<EnemyWave>,
        baseBullets: List<OurBullets.Tracked>,
        time: Long,
        exponent: Double,
    ): Double {
        val hitProbability = model.densityOf(neighbors, gf - HALF_WIDTH, gf + HALF_WIDTH)
        if (hitProbability <= 0.0) return 0.0
        val candidateBullets =
            baseBullets + OurBullets.Tracked(self.x, self.y, angleRadians, Rules.getBulletSpeed(power), time + 1, power)
        var postDanger = 0.0
        for (entry in waves) {
            val wave = entry.wave
            val weight =
                (WAVE_WEIGHT_BASE + wave.power) /
                    sqrt(4.0 + max(1.0, wave.ticksUntilArrival(wave.distanceTo(self.x, self.y), time)))
            val shadowIntervals = BulletShadows.intervals(wave, candidateBullets, self.x, self.y, time)
            // Only danger near where WE are counts: the region around our
            // current guess factor on this wave.
            val currentGf =
                wave.guessFactor(
                    zen.proteus.core.Angles
                        .absoluteBearingRadians(wave.originX, wave.originY, self.x, self.y),
                )
            postDanger +=
                weight *
                mover.dangerWithShadows(entry, self, currentGf - PLAN_RANGE, currentGf + PLAN_RANGE, shadowIntervals)
        }
        return hitProbability / (postDanger + DANGER_FLOOR).pow(exponent)
    }

    private companion object {
        val OFFSETS = doubleArrayOf(0.2, 0.4, 0.7)
        const val HALF_WIDTH = 0.06
        const val WAVE_WEIGHT_BASE = 0.2
        const val DANGER_FLOOR = 0.01
        const val WIN_MARGIN = 0.10
        const val PLAN_RANGE = 0.5
    }
}
